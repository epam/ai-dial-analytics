package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.configuration.ScanProperties;
import com.epam.aidial.analytics.observability.TransitionLog;
import com.epam.aidial.analytics.service.enrichment.model.InputBinding;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.enrichment.model.ReadyWhen;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TransformOutput;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.CursorCodec;
import com.epam.aidial.analytics.service.enrichment.support.CursorColumns;
import com.epam.aidial.analytics.service.enrichment.support.PipelineAuthorizer;
import com.epam.aidial.analytics.service.enrichment.support.ScanSourceMetadata;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;
import com.epam.aidial.analytics.service.pipeline.EnrichmentFreshness;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.SensitiveGuardStubs;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumn;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumnRole;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What the scan <b>issues</b>: the runner's page, pinned byte for byte, and the dry-run preview's reads that share
 * its plan — same joins, same clamp, a narrower select list and the opposite order.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScanServiceTest {

    private static final String NAME = "tox-live";
    private static final String SOURCE = DialUsageLogTestModel.ENTITY;
    private static final String SESSION = DialUsageLogTestModel.ENRICHMENT_COLUMN;
    private static final String ENRICHMENT = "usage_client_identity";

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
    @Mock
    private CursorCodec codec;
    @Mock
    private EnrichmentFreshness freshness;
    @Mock
    private TableDefinitionRepository definitions;

    private ScanService service;

    @BeforeEach
    void setUp() {
        service = new ScanService(pipelines, authorizer, scanSources, sensitiveGuard, filterValidator, filterTranslator,
                new QueryDslConfiguration().clickhouseRenderContext(), executor, codec,
                new ScanProperties(Duration.ofSeconds(5), 100, 1000), freshness,
                new PipelineBudgetProperties(Duration.ofSeconds(30), DataSize.ofGigabytes(1)), new TransitionLog(),
                definitions);
        given(scanSources.entityModel(SOURCE)).willReturn(DialUsageLogTestModel.modelWithEnrichment());
        given(scanSources.cursorColumns(SOURCE))
                .willReturn(new CursorColumns("_ingested_at", "event_id", SOURCE, null));
        // A filter naming an enrichment column no input binds: it must still draw the join.
        given(filterValidator.analyze(anyString(), any(), anyString(), anyBoolean())).willReturn(
                new FilterSqlValidator.FilterAnalysis(new ComparisonNode(ComparisonOp.NE, List.of()), Set.of(SESSION)));
        given(filterTranslator.toCondition(any(), any()))
                .willReturn(DSL.condition("\"usage_client_identity\".\"client_session_id\" != ''"));
        given(freshness.bound(any(), anyString())).willReturn(Optional.empty());
        given(executor.executeScan(any(), any(), any())).willReturn(new ArrayList<>());
        SensitiveGuardStubs.givenEntitledCaller(sensitiveGuard);
    }

    /**
     * The runner's scan page, byte for byte as it rendered before the preview's reads were made to share its
     * plan. The preview is an operator's need; the scan's statement is the runner's contract, and the plan
     * extraction must not have moved it.
     */
    @Test
    void runnerScanRendersExactlyTheStatementItRenderedBeforeThePlanWasShared() {
        runnerScan(rowGrain("deployment"), null, null, null);

        assertThat(executed(1).getFirst()).isEqualTo(RUNNER_ROW_SCAN);
    }

    @Test
    void runnerGroupScanRendersExactlyTheStatementItRenderedBeforeThePlanWasShared() {
        runnerScan(group("deployment"), null, null, null);

        assertThat(executed(1).getFirst()).isEqualTo(RUNNER_GROUP_SCAN);
    }

    /**
     * The runner's page with every predicate source at once — a cursor, a {@code (from, to]} window and a partial
     * freshness clamp — on a partitioned source: one partition floor at the later lower bound, the seek, and the
     * clamp's {@code atMost}. Pinned so the preview's reuse of these helpers cannot move the runner's statement.
     */
    @Test
    void runnerScanWithCursorWindowAndPartialClampRendersExactlyItsStatement() {
        given(scanSources.cursorColumns(SOURCE)).willReturn(new CursorColumns("_ingested_at", "event_id", SOURCE, "request_time"));
        given(freshness.bound(any(), anyString())).willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, 9_000L, "e9")));
        given(codec.decode("tok", SOURCE)).willReturn(new CursorCodec.Cursor(SOURCE, List.of("5000", "e5")));

        runnerScan(rowGrain("deployment"), "tok", Instant.ofEpochMilli(1_000L), Instant.ofEpochMilli(20_000L));

        assertThat(executed(1).getFirst()).isEqualTo(RUNNER_CURSOR_WINDOW_CLAMP_SCAN);
    }

    /**
     * An enrichment-sourced group key is projected under its qualified name and aliased to the plain grain key,
     * with a NULL or empty key excluded by the read.
     */
    @Test
    void runnerGroupScanOnAnEnrichmentSourcedKeyRendersExactlyItsStatement() {
        runnerScan(resolved(SESSION, "client_session_id", "deployment"), null, null, null);

        assertThat(executed(1).getFirst()).isEqualTo(RUNNER_ALIASED_GROUP_SCAN);
    }

    /** {@code ready_when.signal} is projected as the computed verdict, not as the columns it reads. */
    @Test
    void runnerGroupScanWithReadySignalRendersExactlyItsStatement() {
        final ResolvedEnrichPipeline signalled = group("deployment");
        final ReadyWhen readyWhen = new ReadyWhen();
        readyWhen.setSignal("\"" + SESSION + "\" != ''");
        signalled.pipeline().setReadyWhen(readyWhen);

        runnerScan(signalled, null, null, null);

        assertThat(executed(1).getFirst()).isEqualTo(RUNNER_SIGNAL_GROUP_SCAN);
    }

    @Test
    void previewSampleCarriesOnlyTheGrainKeyAndTheInputsNewestFirst() {
        final ScanService.RowSample sample = service.previewRows(rowGrain("deployment"), 5, null, null);

        final String sql = executed(1).getFirst();
        // The filter's enrichment column still draws its join and still filters; it is not returned.
        assertThat(sql).contains("left outer join \"usage_client_identity\"")
                .contains("where ((\"usage_client_identity\".\"client_session_id\" != '')");
        assertThat(sql).startsWith("select \"dial_usage_log\".\"event_id\" \"event_id\", "
                + "\"dial_usage_log\".\"deployment\" \"deployment\" from");
        assertThat(sql).doesNotContain("__cursor_v").doesNotContain("\"_ingested_at\" \"_ingested_at\"");
        assertThat(sql).endsWith("order by toUnixTimestamp64Milli(\"_ingested_at\") desc, toString(\"event_id\") desc limit 5");
        assertThat(sample.columns()).extracting(PreviewColumn::name, PreviewColumn::role).containsExactly(
                tuple("event_id", PreviewColumnRole.GRAIN_KEY),
                tuple("deployment", PreviewColumnRole.INPUT));
    }

    @Test
    void enrichmentWithNoBoundWithholdsThePreviewWithoutStatement() {
        given(freshness.bound(any(), anyString())).willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, null, null)));

        final ScanService.RowSample sample = service.previewRows(rowGrain("deployment"), 5, null, null);

        assertThat(sample.heldBy()).isEqualTo(ENRICHMENT);
        assertThat(sample.rows()).isEmpty();
        assertThat(sample.columns()).isNotEmpty();
        verify(executor, times(0)).executeScan(any(), any(), any());
    }

    @Test
    void partialClampIsProbedPastTheBoundForTheNewestVersionAlone() {
        given(freshness.bound(any(), anyString())).willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, 1_000L, "e1")));
        given(executor.executeScan(any(), any(), any()))
                .willReturn(new ArrayList<>())
                .willReturn(new ArrayList<>(List.of(Map.of("__cursor_v", 9_000L))));

        final ScanService.RowSample sample = service.previewRows(rowGrain("deployment"), 5, null, null);

        final List<String> statements = executed(2);
        assertThat(statements.getFirst()).contains("<= (1000, 'e1')");
        assertThat(statements.get(1)).startsWith("select toUnixTimestamp64Milli(\"_ingested_at\") \"__cursor_v\" from")
                .contains("> (1000, 'e1')")
                .endsWith("order by toUnixTimestamp64Milli(\"_ingested_at\") desc limit 1");
        assertThat(sample.aboveBound()).isEqualTo(9_000L);
    }

    /**
     * With a {@code from} as well as the bound, the probe renders one partition floor, at the later of the two —
     * the window and the seek share it, as they do on the runner's page.
     */
    @Test
    void clampProbeWithWindowRendersOnePartitionFloorAtTheLaterBound() {
        given(scanSources.cursorColumns(SOURCE)).willReturn(new CursorColumns("_ingested_at", "event_id", SOURCE, "request_time"));
        given(freshness.bound(any(), anyString())).willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, 9_000L, "e9")));

        service.previewRows(rowGrain("deployment"), 5, Instant.ofEpochMilli(1_000L), null);

        final String probe = executed(2).get(1);
        assertThat(probe).contains("> (9000, 'e9')").contains("\"_ingested_at\" > fromUnixTimestamp64Milli(1000)");
        assertThat(probe.split("min\\(", -1)).hasSize(2);
        assertThat(probe).contains("where \"_ingested_at\" >= fromUnixTimestamp64Milli(9000) settings final=0)");
    }

    /** A named group asks the plan alone whether an enrichment withholds everything: no statement is issued. */
    @Test
    void withholdingEnrichmentIsAnsweredWithoutStatement() {
        given(freshness.bound(any(), anyString())).willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, null, null)));

        assertThat(service.withholdingEnrichment(group("deployment"))).isEqualTo(ENRICHMENT);
        given(freshness.bound(any(), anyString())).willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, 9_000L, "e9")));
        assertThat(service.withholdingEnrichment(group("deployment"))).isNull();
        verify(executor, times(0)).executeScan(any(), any(), any());
    }

    @Test
    void theGroupChoiceSkipsAnEmptyKeyAndLooksAtTheLastDayFirst() {
        given(executor.executeScan(any(), any(), any()))
                .willReturn(new ArrayList<>())
                .willReturn(new ArrayList<>(List.of(Map.of("__group_key", "chat-9"))));

        final ScanService.GroupPick pick = service.pickGroup(group("deployment"));

        final List<String> statements = executed(2);
        assertThat(statements.getFirst())
                .startsWith("select toString(\"dial_usage_log\".\"chat_id\") \"__group_key\" from")
                .contains("\"dial_usage_log\".\"chat_id\" is not null")
                .contains("toString(\"dial_usage_log\".\"chat_id\") != ''")
                .contains("\"_ingested_at\" > fromUnixTimestamp64Milli(")
                .endsWith("limit 1");
        assertThat(statements.get(1)).doesNotContain("fromUnixTimestamp64Milli(");
        assertThat(pick.groupKey()).isEqualTo("chat-9");
        assertThat(pick.columns()).extracting(PreviewColumn::name).containsExactly("deployment");
    }

    @Test
    void groupChoiceHeldByAnEnrichmentReadsNothing() {
        given(freshness.bound(any(), anyString())).willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, null, null)));

        final ScanService.GroupPick pick = service.pickGroup(group("deployment"));

        assertThat(pick.heldBy()).isEqualTo(ENRICHMENT);
        assertThat(pick.groupKey()).isNull();
        verify(executor, times(0)).executeScan(any(), any(), any());
    }

    private List<String> executed(int expected) {
        final ArgumentCaptor<SelectQuery<Record>> captor = ArgumentCaptor.captor();
        verify(executor, times(expected)).executeScan(captor.capture(), any(), any());
        return captor.getAllValues().stream().map(select -> select.getSQL(ParamType.INLINED)).toList();
    }

    /** The runner's scan of {@code resolved}, through its own entry point, with a 50-row page. */
    private void runnerScan(ResolvedEnrichPipeline resolved, String cursor, Instant from, Instant to) {
        given(pipelines.resolveForScan(NAME)).willReturn(resolved);
        service.scan(NAME, cursor, 50, from, to);
    }

    private static ResolvedEnrichPipeline rowGrain(String... columns) {
        return resolved(null, "event_id", columns);
    }

    private static ResolvedEnrichPipeline group(String... columns) {
        return resolved("chat_id", "chat_id", columns);
    }

    /**
     * An {@code llm} pipeline on {@link #SOURCE}, row-grain or grouped by {@code groupBy}, binding {@code columns},
     * whose target is keyed on {@code grainKey}.
     */
    private static ResolvedEnrichPipeline resolved(String groupBy, String grainKey, String... columns) {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName(NAME);
        pipeline.setTarget("tox_label");
        pipeline.setInputTable(SOURCE);
        pipeline.setFilterSql("\"" + SESSION + "\" != ''");
        if (groupBy != null) {
            pipeline.setTriggerKind(TriggerKind.GROUP);
            pipeline.setTriggerGroupBy(groupBy);
        } else {
            pipeline.setTriggerKind(TriggerKind.SCHEDULE);
            pipeline.setTriggerCron("0 * * * * *");
        }
        final EnrichSpec spec = new EnrichSpec();
        final List<InputBinding> bindings = new ArrayList<>();
        for (final String column : columns) {
            final InputBinding binding = new InputBinding();
            binding.setVar("v_" + column);
            binding.setColumn(column);
            bindings.add(binding);
        }
        spec.setInputBindings(bindings);
        final TransformOutput output = new TransformOutput();
        output.setName("label");
        spec.getTransform().setType(TransformType.LLM);
        spec.getTransform().setOutputs(List.of(output));
        final OutputBinding outputBinding = new OutputBinding();
        outputBinding.setVar("label");
        outputBinding.setColumn("label");
        spec.setOutputBindings(List.of(outputBinding));
        return new ResolvedEnrichPipeline(pipeline, spec, SOURCE, grainKey, "_ingested_at");
    }

    /** The runner's row-grain page as rendered before the plan extraction, captured from that revision. */
    private static final String RUNNER_ROW_SCAN = "select \"usage_client_identity\".\"client_session_id\" \"usage_client_identity.client_session_id\", "
            + "\"dial_usage_log\".\"deployment\" \"deployment\", \"dial_usage_log\".\"event_id\" \"event_id\", "
            + "\"dial_usage_log\".\"_ingested_at\" \"_ingested_at\", toUnixTimestamp64Milli(\"_ingested_at\") "
            + "\"__cursor_v\", toString(\"event_id\") \"__cursor_id\" from \"dial_usage_log\" left outer join "
            + "\"usage_client_identity\" on \"dial_usage_log\".\"event_id\" = \"usage_client_identity\".\"event_id\" "
            + "where ((\"usage_client_identity\".\"client_session_id\" != '') and (\"_ingested_at\" <= now64(3) - "
            + "toIntervalMillisecond(5000))) order by toUnixTimestamp64Milli(\"_ingested_at\") asc, "
            + "toString(\"event_id\") asc limit 50";

    /** The runner's group dirty-mark page as rendered before the plan extraction, captured from that revision. */
    private static final String RUNNER_GROUP_SCAN = "select \"usage_client_identity\".\"client_session_id\" \"usage_client_identity.client_session_id\", "
            + "\"dial_usage_log\".\"chat_id\" \"chat_id\", \"dial_usage_log\".\"_ingested_at\" \"_ingested_at\", "
            + "\"dial_usage_log\".\"event_id\" \"event_id\", toUnixTimestamp64Milli(\"_ingested_at\") \"__cursor_v\", "
            + "toString(\"event_id\") \"__cursor_id\" from \"dial_usage_log\" left outer join "
            + "\"usage_client_identity\" on \"dial_usage_log\".\"event_id\" = \"usage_client_identity\".\"event_id\" "
            + "where ((\"usage_client_identity\".\"client_session_id\" != '') and (\"_ingested_at\" <= now64(3) - "
            + "toIntervalMillisecond(5000))) order by toUnixTimestamp64Milli(\"_ingested_at\") asc, "
            + "toString(\"event_id\") asc limit 50";

    /** The runner's row-grain page past a cursor, inside a window and under a partial clamp, as rendered on this revision. */
    private static final String RUNNER_CURSOR_WINDOW_CLAMP_SCAN = "select \"usage_client_identity\".\"client_session_id\" \"usage_client_identity.client_session_id\", "
            + "\"dial_usage_log\".\"deployment\" \"deployment\", \"dial_usage_log\".\"event_id\" \"event_id\", "
            + "\"dial_usage_log\".\"_ingested_at\" \"_ingested_at\", toUnixTimestamp64Milli(\"_ingested_at\") "
            + "\"__cursor_v\", toString(\"event_id\") \"__cursor_id\" from \"dial_usage_log\" left outer join "
            + "\"usage_client_identity\" on \"dial_usage_log\".\"event_id\" = \"usage_client_identity\".\"event_id\" "
            + "where ((\"usage_client_identity\".\"client_session_id\" != '') and (\"_ingested_at\" <= now64(3) - "
            + "toIntervalMillisecond(5000)) and (\"_ingested_at\" > fromUnixTimestamp64Milli(1000)) and "
            + "(\"_ingested_at\" <= fromUnixTimestamp64Milli(20000)) and "
            + "((toUnixTimestamp64Milli(\"_ingested_at\"), toString(\"event_id\")) > (5000, 'e5')) and "
            + "(\"dial_usage_log\".\"request_time\" >= (select min(\"request_time\") from \"dial_usage_log\" where "
            + "\"_ingested_at\" >= fromUnixTimestamp64Milli(5000) settings final=0)) and "
            + "((toUnixTimestamp64Milli(\"_ingested_at\"), toString(\"event_id\")) <= (9000, 'e9'))) order by "
            + "toUnixTimestamp64Milli(\"_ingested_at\") asc, toString(\"event_id\") asc limit 50";

    /** The runner's dirty-mark page over an enrichment-sourced group key, as rendered on this revision. */
    private static final String RUNNER_ALIASED_GROUP_SCAN = "select \"usage_client_identity\".\"client_session_id\" \"usage_client_identity.client_session_id\", "
            + "\"dial_usage_log\".\"_ingested_at\" \"_ingested_at\", \"dial_usage_log\".\"event_id\" \"event_id\", "
            + "\"usage_client_identity\".\"client_session_id\" \"client_session_id\", "
            + "toUnixTimestamp64Milli(\"_ingested_at\") \"__cursor_v\", toString(\"event_id\") \"__cursor_id\" from "
            + "\"dial_usage_log\" left outer join \"usage_client_identity\" on \"dial_usage_log\".\"event_id\" = "
            + "\"usage_client_identity\".\"event_id\" where (\"usage_client_identity\".\"client_session_id\" is not "
            + "null and (toString(\"usage_client_identity\".\"client_session_id\") != '') and "
            + "(\"usage_client_identity\".\"client_session_id\" != '') and (\"_ingested_at\" <= now64(3) - "
            + "toIntervalMillisecond(5000))) order by toUnixTimestamp64Milli(\"_ingested_at\") asc, "
            + "toString(\"event_id\") asc limit 50";

    /** The runner's dirty-mark page carrying the ready_when.signal verdict, as rendered on this revision. */
    private static final String RUNNER_SIGNAL_GROUP_SCAN = "select \"usage_client_identity\".\"client_session_id\" \"usage_client_identity.client_session_id\", "
            + "\"dial_usage_log\".\"chat_id\" \"chat_id\", \"dial_usage_log\".\"_ingested_at\" \"_ingested_at\", "
            + "\"dial_usage_log\".\"event_id\" \"event_id\", toUnixTimestamp64Milli(\"_ingested_at\") \"__cursor_v\", "
            + "toString(\"event_id\") \"__cursor_id\", (\"usage_client_identity\".\"client_session_id\" != '') "
            + "\"__signal\" from \"dial_usage_log\" left outer join \"usage_client_identity\" on "
            + "\"dial_usage_log\".\"event_id\" = \"usage_client_identity\".\"event_id\" where "
            + "((\"usage_client_identity\".\"client_session_id\" != '') and (\"_ingested_at\" <= now64(3) - "
            + "toIntervalMillisecond(5000))) order by toUnixTimestamp64Milli(\"_ingested_at\") asc, "
            + "toString(\"event_id\") asc limit 50";
}
