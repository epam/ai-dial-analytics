package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.configuration.GroupFetchProperties;
import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.configuration.ScanProperties;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.EnrichDeclaration;
import com.epam.aidial.analytics.service.enrichment.model.GroupPage;
import com.epam.aidial.analytics.service.enrichment.model.InputBinding;
import com.epam.aidial.analytics.service.enrichment.model.MemberOrder;
import com.epam.aidial.analytics.service.enrichment.model.MemberSelect;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TransformOutput;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.CursorColumns;
import com.epam.aidial.analytics.service.enrichment.support.PipelineAuthorizer;
import com.epam.aidial.analytics.service.enrichment.support.ScanSourceMetadata;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.SensitiveGuardStubs;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What the group-input assembly <b>issues</b>, as opposed to what it returns: the statements, and how the
 * declaration's bound columns are divided between them.
 *
 * <p>That division is the whole subject here. A statement projecting several heavy columns pays for each of
 * them concurrently — on the order of a GiB per heavy column to return a single member — so a declaration
 * binding two of them can exceed a budget that either half clears alone. The
 * assembly therefore reads one heavy column per statement. The result is identical either way — which is
 * exactly why the end-to-end coverage in {@code GroupEnrichmentIntegrationTest} cannot see the difference,
 * and why the guard against folding the reads back together has to look at the SQL.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupFetchServiceTest {

    private static final String NAME = "conv-pipeline";
    private static final String SOURCE = DialUsageLogTestModel.ENTITY;
    private static final String GROUP_KEY = "chat-a";
    private static final String REQUEST_BODY = DialUsageLogTestModel.payload("request_body");
    private static final String ASSEMBLED_RESPONSE = DialUsageLogTestModel.payload("assembled_response");

    @Mock
    private PipelineService pipelines;
    @Mock
    private PipelineAuthorizer authorizer;
    @Mock
    private ScanSourceMetadata scanSources;
    @Mock
    private SensitiveProjectionGuard sensitiveGuard;
    @Mock
    private FilterSqlValidator filterValidator;
    @Mock
    private FilterTranslator filterTranslator;
    @Mock
    private StructuredQueryExecutor executor;

    private GroupFetchService service;

    @BeforeEach
    void setUp() {
        final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
        service = new GroupFetchService(pipelines, authorizer, scanSources, sensitiveGuard,
                filterValidator,
                filterTranslator, ctx, executor, new JsonMapperConfiguration().jsonMapper(),
                new ScanProperties(Duration.ZERO, 100, 1000),
                new GroupFetchProperties(100, 1000, DataSize.ofMegabytes(10)),
                new PipelineBudgetProperties(Duration.ofSeconds(30), DataSize.ofGigabytes(1)));

        given(scanSources.entityModel(SOURCE)).willReturn(DialUsageLogTestModel.model());
        given(scanSources.cursorColumns(SOURCE)).willReturn(new CursorColumns("_ingested_at", "event_id"));
        // A one-column ordering key, so a member is addressed by (request_time, event_id) and the aliases
        // the two phases match on are __ok0 and __ok1.
        given(scanSources.orderingKey(SOURCE)).willReturn(List.of("request_time"));
        given(filterValidator.analyze(anyString(), any(), anyString(), anyBoolean()))
                .willReturn(new FilterSqlValidator.FilterAnalysis(null, Set.of()));
        SensitiveGuardStubs.givenEntitledCaller(sensitiveGuard);
    }

    /**
     * Two heavy columns bound by one declaration are projected by one statement each, and the inexpensive
     * bound column rides along with the first rather than costing a statement of its own — three bound
     * columns, three reads in total including the selection phase.
     */
    @Test
    void twoHeavyColumnsAreProjectedByOneStatementEach() {
        givenPipelineBinding(REQUEST_BODY, ASSEMBLED_RESPONSE, "deployment");
        givenOneMemberThenItsPayload();

        final GroupPage page = service.fetch(NAME, GROUP_KEY, 100);

        assertThat(page.rows()).hasSize(1);
        final List<String> statements = executedStatements(3);
        // The selection phase: no heavy column, and it is the one that carries the group-key predicate.
        assertThat(statements.get(0)).doesNotContain("request_body").doesNotContain("assembled_response");
        // Neither projection statement names the other's heavy column, which is the property that keeps
        // the peak the largest single column instead of the sum of both.
        assertThat(statements.get(1)).contains("request_body").doesNotContain("assembled_response");
        assertThat(statements.get(2)).contains("assembled_response").doesNotContain("request_body");
        assertThat(statements.get(1)).contains("deployment");
    }

    /**
     * A {@code string} group key is compared on the bare column, so a lookup index on it stays usable; a key of any
     * other type through {@code toString}, since the caller's key arrives as text. Neither is a column of an aligned
     * enrichment, so neither is pre-selected.
     */
    @Test
    void stringGroupKeyIsComparedBareAndAnyOtherThroughToString() {
        givenPipelineBinding("deployment");
        givenOneMemberThenItsPayload();
        service.fetch(NAME, GROUP_KEY, 100);
        assertThat(executedStatements(2).getFirst()).contains("\"chat_id\" = 'chat-a'").doesNotContain("toString(\"chat_id\")")
                .doesNotContain("SETTINGS final = 0");
    }

    @Test
    void numericGroupKeyIsComparedThroughToStringAndNotPreselected() {
        givenGroupPipeline("total_tokens", "deployment");
        givenOneMemberThenItsPayload();
        service.fetch(NAME, "42", 100);
        assertThat(executedStatements(2).getFirst()).contains("\"total_tokens\") = '42'").contains("toString(")
                .doesNotContain("SETTINGS final = 0");
    }

    /**
     * A declaration binding one heavy column is read exactly as before the split: two statements, the
     * selection phase and one projection. The split costs nothing where there is nothing to split.
     */
    @Test
    void oneHeavyColumnStillCostsOneProjection() {
        givenPipelineBinding(REQUEST_BODY);
        givenOneMemberThenItsPayload();

        service.fetch(NAME, GROUP_KEY, 100);

        final List<String> statements = executedStatements(2);
        assertThat(statements.get(1)).contains("request_body");
    }

    /**
     * The preview's member path: the count reads one past the runner's cap with no bound column projected, and
     * only the top five members are read with their bodies — never a cap's worth of heavy rows.
     */
    @Test
    void previewCountsPastTheCapWithoutBodiesAndReadsBodiesForTheTopFiveOnly() {
        givenPipelineBinding(REQUEST_BODY);
        final List<Map<String, Object>> pool = new ArrayList<>();
        // Ranked newest first, as the selection orders them.
        for (int i = 100; i >= 0; i--) {
            pool.add(member(i));
        }
        final List<Map<String, Object>> bodies = new ArrayList<>();
        for (int i = 100; i > 95; i--) {
            final Map<String, Object> body = member(i);
            body.put(REQUEST_BODY, "{\"i\":" + i + "}");
            bodies.add(body);
        }
        given(executor.executeScan(any(), any(), any(), any())).willReturn(pool).willReturn(bodies);

        final GroupFetchService.MemberSample sample = service.previewMembers(resolved(), GROUP_KEY, 5);

        final List<String> statements = executedStatements(2);
        // The default cap is 100 here, so the count reads 101 rows, and names no body.
        assertThat(statements.get(0)).doesNotContain("request_body").endsWith("limit 101");
        assertThat(statements.get(1)).contains("request_body").endsWith("limit 5");
        assertThat(sample.counted()).isEqualTo(101);
        assertThat(sample.cap()).isEqualTo(100);
        assertThat(sample.capIsDefault()).isTrue();
        // Only the bound input comes back, in the assembly's ascending order.
        assertThat(sample.rows()).hasSize(5).allSatisfy(row -> assertThat(row).containsOnlyKeys(REQUEST_BODY));
        assertThat(sample.rows().getFirst()).containsEntry(REQUEST_BODY, "{\"i\":96}");
    }

    /** A {@code member_select.limit} below the default is the declaration's own narrowing, not the runner's cut. */
    @Test
    void declaredLimitBelowTheDefaultIsTheCap() {
        givenPipelineBinding(REQUEST_BODY);
        final ResolvedEnrichPipeline narrowed = resolved();
        final MemberSelect select = new MemberSelect();
        select.setLimit(10);
        narrowed.pipeline().setMemberSelect(select);
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of());

        final GroupFetchService.MemberSample sample = service.previewMembers(narrowed, GROUP_KEY, 5);

        assertThat(executedStatements(1).getFirst()).endsWith("limit 11");
        assertThat(sample.cap()).isEqualTo(10);
        assertThat(sample.capIsDefault()).isFalse();
        assertThat(sample.rows()).isEmpty();
    }

    /** A limit equal to the default is still the declaration's choice: nothing the runner cuts, so no cut note. */
    @Test
    void declaredLimitEqualToTheDefaultIsNotTheRunnersCut() {
        givenPipelineBinding(REQUEST_BODY);
        final ResolvedEnrichPipeline atDefault = resolved();
        final MemberSelect select = new MemberSelect();
        select.setLimit(100);
        atDefault.pipeline().setMemberSelect(select);
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of());

        final GroupFetchService.MemberSample sample = service.previewMembers(atDefault, GROUP_KEY, 5);

        assertThat(executedStatements(1).getFirst()).endsWith("limit 101");
        assertThat(sample.cap()).isEqualTo(100);
        assertThat(sample.capIsDefault()).isFalse();
    }

    /**
     * A source with no ordering key cannot be point-looked-up, so the count projects only the member's key and order
     * columns, and a second read — limited to the sample — is the one that projects the bound column.
     */
    @Test
    void previewOnSourceWithNoOrderingKeyCountsKeyOnlyThenReadsTheSample() {
        givenPipelineBinding(REQUEST_BODY);
        given(scanSources.orderingKey(SOURCE)).willReturn(List.of());
        final List<Map<String, Object>> pool = new ArrayList<>();
        for (int i = 10; i >= 0; i--) {
            pool.add(member(i));
        }
        final List<Map<String, Object>> bodies = new ArrayList<>();
        for (int i = 10; i > 5; i--) {
            final Map<String, Object> body = member(i);
            body.put(REQUEST_BODY, "{\"i\":" + i + "}");
            bodies.add(body);
        }
        given(executor.executeScan(any(), any(), any(), any())).willReturn(pool).willReturn(bodies);

        final GroupFetchService.MemberSample sample = service.previewMembers(resolved(), GROUP_KEY, 5);

        final List<String> statements = executedStatements(2);
        assertThat(statements.get(0)).doesNotContain("request_body").doesNotContain("__ok").endsWith("limit 101");
        assertThat(statements.get(0)).startsWith("select toUnixTimestamp64Milli(\"_ingested_at\") \"__cursor_v\", "
                + "toString(\"event_id\") \"__cursor_id\" from");
        assertThat(statements.get(1)).contains("request_body").doesNotContain("__ok").endsWith("limit 6");
        assertThat(sample.counted()).isEqualTo(11);
        assertThat(sample.selected()).isEqualTo(5);
        assertThat(sample.rows()).hasSize(5).allSatisfy(row -> assertThat(row).containsOnlyKeys(REQUEST_BODY));
        assertThat(sample.rows().getFirst()).containsEntry(REQUEST_BODY, "{\"i\":6}");
    }

    /**
     * A group pipeline binding no column (its inputs all JSONata, or none) is sampled as one empty row per member
     * under no columns: one statement, and the member count stays visible instead of reading as an empty group.
     */
    @Test
    void previewWithNoBoundColumnReturnsAnEmptyRowPerMember() {
        givenPipelineBinding();
        given(executor.executeScan(any(), any(), any(), any())).willReturn(new ArrayList<>(List.of(member(2), member(1))));

        final GroupFetchService.MemberSample sample = service.previewMembers(resolved(), GROUP_KEY, 5);

        assertThat(executedStatements(1)).hasSize(1);
        assertThat(sample.columns()).isEmpty();
        assertThat(sample.rows()).hasSize(2).allSatisfy(row -> assertThat(row).isEmpty());
        assertThat(sample.selected()).isEqualTo(2);
    }

    /**
     * A declaration stored before every write refused a computed ranking is refused here too, before any statement:
     * the selection phase would otherwise order the members by the stand-in tuple rendered in the field's place.
     */
    @Test
    void storedRankingByComputedFieldIsRefusedBeforeAnyStatement() {
        givenPipelineBinding(REQUEST_BODY);
        final MemberOrder order = new MemberOrder();
        order.setColumn(DialUsageLogTestModel.payload("request_body_full"));
        final MemberSelect select = new MemberSelect();
        select.setOrderBy(List.of(order));
        resolved().pipeline().setMemberSelect(select);
        given(scanSources.entityModel(SOURCE)).willReturn(DialUsageLogTestModel.withComputedFields(DialUsageLogTestModel.model()));

        assertThatThrownBy(() -> service.fetch(NAME, GROUP_KEY, 100))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("member_select.order_by references 'dial_usage_log_payload.request_body_full'")
                .hasMessageContaining("bound as a transform input");
        verify(executor, never()).executeScan(any(), any(), any(), any());
    }

    private ResolvedEnrichPipeline resolved() {
        return pipelines.resolveForScan(NAME);
    }

    /** One member as the selection phase returns it, addressed by its lookup key and ordered by its version. */
    private static Map<String, Object> member(int i) {
        final Map<String, Object> member = new LinkedHashMap<>();
        member.put("__ok0", "2026-09-15 00:00:00." + String.format("%03d", i));
        member.put("__ok1", "e-" + i);
        member.put("__cursor_v", 1_000L + i);
        member.put("__cursor_id", "e-" + i);
        member.put("chat_id", GROUP_KEY);
        return member;
    }

    /** The SQL of every statement the assembly ran, in order, asserting how many there were. */
    private List<String> executedStatements(int expected) {
        final ArgumentCaptor<SelectQuery<Record>> captor = ArgumentCaptor.captor();
        verify(executor, times(expected)).executeScan(captor.capture(), any(), any(), any());
        return captor.getAllValues().stream().map(select -> select.getSQL(ParamType.INLINED)).toList();
    }

    /** A group pipeline on {@link #SOURCE} binding {@code columns} as its inputs, in order. */
    private void givenPipelineBinding(String... columns) {
        givenGroupPipeline("chat_id", columns);
    }

    /** As {@link #givenPipelineBinding}, grouping by {@code groupBy}. */
    private void givenGroupPipeline(String groupBy, String... columns) {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName(NAME);
        pipeline.setTarget("conv_label");
        pipeline.setInputTable(SOURCE);
        pipeline.setTriggerKind(TriggerKind.GROUP);
        pipeline.setTriggerGroupBy(groupBy);
        final EnrichSpec spec = new EnrichSpec();
        final List<InputBinding> bindings = new ArrayList<>();
        for (final String column : columns) {
            final InputBinding binding = new InputBinding();
            binding.setVar("v" + column.hashCode());
            binding.setColumn(column);
            bindings.add(binding);
        }
        // Set through the spec rather than mutated in place: the bindings live inside the transform now,
        // and the resolving accessor returns the nested list.
        spec.setInputBindings(bindings);
        // A complete declaration: group assembly refuses one missing a member its kind requires.
        final TransformOutput output = new TransformOutput();
        output.setName("label");
        spec.getTransform().setType(TransformType.LLM);
        spec.getTransform().setOutputs(List.of(output));
        final OutputBinding outputBinding = new OutputBinding();
        outputBinding.setVar("label");
        outputBinding.setColumn("label");
        spec.setOutputBindings(List.of(outputBinding));
        given(pipelines.resolveForScan(NAME)).willReturn(new ResolvedEnrichPipeline(
                pipeline, spec, SOURCE, "chat_id", "_ingested_at"));
        // The declaration the service reads back is derived from the same pair.
        assertThat(new EnrichDeclaration(pipeline, spec).triggerKind()).isEqualTo(TriggerKind.GROUP);
    }

    /**
     * One member from the selection phase, and the same member from every projection read. Each read is
     * answered with the lookup-key aliases the member arrived under, which is what the merge matches on;
     * the projected values themselves are irrelevant to this test and are left out.
     */
    private void givenOneMemberThenItsPayload() {
        final Map<String, Object> member = new LinkedHashMap<>();
        member.put("__ok0", "2026-09-15 00:00:00.000");
        member.put("__ok1", "e-1");
        member.put("__cursor_v", "2026-09-15 00:00:00.000");
        member.put("__cursor_id", "e-1");
        member.put("chat_id", GROUP_KEY);
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of(member));
    }
}
