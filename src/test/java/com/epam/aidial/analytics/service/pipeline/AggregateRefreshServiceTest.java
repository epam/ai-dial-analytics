package com.epam.aidial.analytics.service.pipeline;

import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseInsertSelectExecutor;
import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.configuration.AggregateProperties;
import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.PipelineConflictException;
import com.epam.aidial.analytics.exception.PipelineNotFoundException;
import com.epam.aidial.analytics.exception.PipelineReadBudgetExceededException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.SensitiveColumnNotEntitledException;
import com.epam.aidial.analytics.observability.ClientSpanTracer;
import com.epam.aidial.analytics.observability.CursorPosition;
import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.observability.TransitionLog;
import com.epam.aidial.analytics.service.enrichment.support.CursorColumns;
import com.epam.aidial.analytics.service.enrichment.support.ScanSourceMetadata;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.GroupKey;
import com.epam.aidial.analytics.service.pipeline.model.Measure;
import com.epam.aidial.analytics.service.pipeline.model.RefreshOutcome;
import com.epam.aidial.analytics.service.pipeline.preview.PipelinePreview;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNote;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNoteCode;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNoteSeverity;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewValueCapper;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.logging.log4j.Level;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit coverage for {@link AggregateRefreshService} — the probe/write handshake around one window.
 *
 * <p>The cases here are the ones whose failure is silent. An empty probe that advanced the cursor anyway
 * would skip input permanently; a write whose range did not match the probed one would break the
 * idempotence everything else rests on; a disabled pipeline that still wrote would make the preview gate
 * decorative. None of them would surface as an error at runtime.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AggregateRefreshServiceTest {

    private static final String NAME = "usage_daily_rollup";
    private static final String INPUT = "dial_usage_log";
    private static final String ENRICHMENT = "usage_client_identity";
    private static final int WINDOW_LIMIT = 3;
    /** The shared pipeline-read ceiling; generous here, since these tests stub the executor entirely. */
    private static final PipelineBudgetProperties READ_BUDGET = new PipelineBudgetProperties(
            Duration.ofSeconds(30), DataSize.ofGigabytes(1));

    private static final AggregateProperties.Preview PREVIEW_BUDGET = new AggregateProperties.Preview(
            Duration.ofSeconds(30), DataSize.ofGigabytes(2), DataSize.ofGigabytes(1));

    @Mock
    private PipelineRepository pipelines;
    @Mock
    private PipelineStateWriter stateWriter;
    @Mock
    private AggregateSpecValidator validator;
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
    private ClickHouseInsertSelectExecutor insertSelect;
    @Mock
    private AggregateMetrics metrics;
    @Mock
    private EnrichmentFreshness freshness;

    private AggregateRefreshService service;
    private InMemorySpanExporter spans;

    @BeforeEach
    void setUp() {
        final var ctx = new QueryDslConfiguration().clickhouseRenderContext();
        final var compiler = new AggregateCompiler(new ExprTranslator(new ValueExprToObjectMapper()), ctx);
        spans = InMemorySpanExporter.create();
        final var sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spans))
                        .build())
                .build();
        final var sqlCompiler = new AggregateSqlCompiler(new ExprTranslator(new ValueExprToObjectMapper()),
                new FilterTranslator(new ExprTranslator(new ValueExprToObjectMapper())), ctx);
        service = new AggregateRefreshService(pipelines, stateWriter, validator, compiler, sqlCompiler, scanSources,
                freshness, SPEC_CODEC, sensitiveGuard, filterValidator, filterTranslator, executor, insertSelect,
                com.epam.aidial.analytics.service.security.RowPolicyFilterStubs.inert(),
                ctx, metrics,
                new AggregateProperties(true, "0 * * * * *", 1, Duration.ofSeconds(5), WINDOW_LIMIT, 20, PREVIEW_BUDGET),
                READ_BUDGET,
                new ClientSpanTracer(sdk), new TransitionLog(),
                new PreviewValueCapper(new JsonMapperConfiguration().jsonMapper()));

        given(pipelines.findById(NAME)).willReturn(Optional.of(pipeline()));
        given(scanSources.entityModel(INPUT)).willReturn(DialUsageLogTestModel.model());
        given(scanSources.cursorColumns(INPUT)).willReturn(new CursorColumns("_ingested_at", "event_id"));
        given(filterValidator.analyze(anyString(), any(), anyString(), anyBoolean()))
                .willReturn(new FilterSqlValidator.FilterAnalysis(null, java.util.Set.of()));
        SensitiveGuardStubs.givenEntitledCaller(sensitiveGuard);
        given(insertSelect.insertSelect(anyString(), anyString(), anyBoolean(), any(), any())).willReturn(7L);
    }

    /**
     * The real codec over the application's configured mapper: the declaration is stored as JSON on the
     * entity now, so a stub here would hide whether a spec actually round-trips.
     */
    private static final PipelineSpecCodec SPEC_CODEC =
            new PipelineSpecCodec(new JsonMapperConfiguration().jsonMapper());

    private static PipelineEntity pipeline() {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName(NAME);
        pipeline.setKind(PipelineKind.AGGREGATE);
        pipeline.setTarget("usage_daily");
        pipeline.setInputTable(INPUT);
        pipeline.setTriggerCron("0 0 * * * *");
        pipeline.setEnabled(true);
        pipeline.setSpec(SPEC_CODEC.write(spec()));
        return pipeline;
    }

    private static AggregateSpec spec() {
        final GroupKey key = new GroupKey();
        key.setColumn("deployment");
        final Measure measure = new Measure();
        measure.setName("call_count");
        measure.setFn("count");
        final AggregateSpec spec = new AggregateSpec();
        spec.setGroupBy(List.of(key));
        spec.setMeasures(List.of(measure));
        return spec;
    }

    /**
     * The probe's single result row: how many rows the window held, and its start and end cursor tuples. The
     * probe derives all of them inside ClickHouse, so what comes back is one row regardless of the window's
     * size — including for an empty window, which returns {@code count 0} and null tuples rather than no row.
     *
     * <p>The start tuple defaults to the end tuple, which is what a single-row window really returns and is
     * the right default for every case that is not about the clamp: it makes the whole window sit at or below
     * any bound the end satisfies, so a test that says nothing about enrichments gets no clamping behaviour.
     * {@link #probeReturns(long, Long, String, Long, String)} sets it explicitly where the distance between
     * the two ends is the point.
     */
    private void probeReturns(long matched, Long version, String identity) {
        probeReturns(matched, version, identity, version, identity);
    }

    private void probeReturns(long matched, Long version, String identity, Long startVersion, String startIdentity) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("__window_n", matched);
        row.put("__cursor_v", version);
        row.put("__cursor_id", identity);
        row.put("__start_v", startVersion);
        row.put("__start_id", startIdentity);
        // The probe carries pipeline.budget.* now, so it is the two-argument overload that answers.
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of(row));
    }

    /** The probe statement the run composed, rendered. */
    private String probeSql() {
        final ArgumentCaptor<SelectQuery<Record>> probe = ArgumentCaptor.forClass(SelectQuery.class);
        verify(executor).executeScan(probe.capture(), any(), any(), any());
        return probe.getValue().getSQL(ParamType.INLINED).toLowerCase();
    }

    @Test
    void theProbeJoinsAnEnrichmentOnlyWhenTheMembershipFilterReadsOne() {
        // The probe projects the cursor tuple and nothing else, so the filter is the only reference that can
        // reach an enrichment from here. A declaration naming one in a group key or a measure must leave the
        // cheapest statement of the refresh unjoined — which is the whole reason the referenced set is
        // derived per statement rather than once per compile.
        given(scanSources.entityModel(INPUT)).willReturn(DialUsageLogTestModel.modelWithEnrichment());
        final PipelineEntity pipeline = pipeline();
        final GroupKey key = new GroupKey();
        key.setColumn(DialUsageLogTestModel.ENRICHMENT_COLUMN);
        key.setAs("client_session_id");
        final AggregateSpec regrained = spec();
        regrained.setGroupBy(List.of(key));
        pipeline.setSpec(SPEC_CODEC.write(regrained));
        given(pipelines.findById(NAME)).willReturn(Optional.of(pipeline));
        probeReturns(2L, 2_000L, "evt-b");

        service.refresh(NAME);

        assertThat(probeSql()).doesNotContain("usage_client_identity");
        // ...while the write it pinned does carry the join, for the same group key.
        final ArgumentCaptor<String> write = ArgumentCaptor.forClass(String.class);
        verify(insertSelect).insertSelect(anyString(), write.capture(), anyBoolean(), any(), any());
        assertThat(write.getValue().toLowerCase()).contains("left outer join \"usage_client_identity\"");
    }

    @Test
    void theProbeCarriesTheJoinWhenTheMembershipFilterReadsAnEnrichment() {
        // The other half of the rule above, and the one that fails at execution rather than at compile: the
        // probe composes its own FROM, so a filter reaching an enrichment without the join is well-formed
        // SQL naming a table it never joined.
        given(scanSources.entityModel(INPUT)).willReturn(DialUsageLogTestModel.modelWithEnrichment());
        final FilterNode predicate = new ComparisonNode(ComparisonOp.NE, List.of());
        given(filterValidator.analyze(anyString(), any(), anyString(), anyBoolean())).willReturn(
                new FilterSqlValidator.FilterAnalysis(
                        predicate, java.util.Set.of(DialUsageLogTestModel.ENRICHMENT_COLUMN)));
        given(filterTranslator.toCondition(any(), any())).willReturn(DSL.trueCondition());
        final PipelineEntity filtered = pipeline();
        filtered.setFilterSql("\"" + DialUsageLogTestModel.ENRICHMENT_COLUMN + "\" != ''");
        given(pipelines.findById(NAME)).willReturn(Optional.of(filtered));
        probeReturns(2L, 2_000L, "evt-b");

        service.refresh(NAME);

        assertThat(probeSql()).contains("left outer join \"usage_client_identity\"");
    }

    @Test
    void anEmptyProbeWritesNothingAndLeavesTheCursorWhereItIs() {
        // An aggregate over an empty window still yields a row — count 0, null tuple. "Nothing new" is the
        // count, never an absent row, so a no-op test keyed on an empty result would never fire and the
        // refresh would pin its window to a null end tuple.
        probeReturns(0L, null, null);

        final RefreshOutcome outcome = service.refresh(NAME);

        assertThat(outcome.rowsWritten()).isZero();
        assertThat(outcome.hasMore()).isFalse();
        verifyNoInteractions(insertSelect);
        // Nulls mean "do not advance": advancing over a window that was never written would skip input.
        verify(stateWriter).recordSuccess(NAME, null, null, 0L, false, null);
    }

    @Test
    void theWriteIsPinnedToTheTupleTheProbeChose() {
        probeReturns(2L, 2_000L, "evt-b");
        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        service.refresh(NAME);

        // The written range must be the probed range, or a retry would cover a different window and the
        // recompute would stop being repeatable.
        verify(insertSelect).insertSelect(eq("usage_daily"), sql.capture(), anyBoolean(), any(), any());
        assertThat(sql.getValue()).contains("<= (2000, 'evt-b')");
        verify(stateWriter).recordSuccess(NAME, 2_000L, "evt-b", 7L, false, null);
    }

    @Test
    void windowEndingPastAnEnrichmentsPositionIsClampedToIt() {
        // The probed window spans (1000, evt-a) .. (2000, evt-b), but the enrichment has only been
        // materialized through (1500, evt-mid). Aggregating up to evt-b would read rows whose enrichment
        // cells are still empty and write a group nothing ever revisits.
        probeReturns(2L, 2_000L, "evt-b", 1_000L, "evt-a");
        clampedAt(1_500L, "evt-mid");
        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        service.refresh(NAME);

        verify(insertSelect).insertSelect(eq("usage_daily"), sql.capture(), anyBoolean(), any(), any());
        assertThat(sql.getValue())
                .as("the write is pinned to the enrichment's position, not to the probed end")
                .contains("<= (1500, 'evt-mid')")
                .doesNotContain("<= (2000, 'evt-b')");
        // The cursor stops at the clamp too, so the rows above it are re-read on a later tick once the
        // enrichment has caught up. Advancing to the probed end would skip them permanently.
        verify(stateWriter).recordSuccess(NAME, 1_500L, "evt-mid", 7L, false,
                new EnrichmentFreshness.Bound(ENRICHMENT, 1_500L, "evt-mid"));
    }

    /** The clamp line names the bound as every position line does: the version, and the identity only as a digest. */
    @Test
    void clampedWindowLineCarriesTheBoundsDigestNotItsIdentity() {
        probeReturns(2L, 2_000L, "evt-b", 1_000L, "evt-a");
        clampedAt(1_500L, "evt-mid");

        try (LogCapture log = LogCapture.of(AggregateRefreshService.class)) {
            service.refresh(NAME);

            assertThat(log.messagesAt(Level.INFO)).filteredOn(line -> line.contains("window clamped"))
                    .singleElement().satisfies(line -> assertThat(line)
                            .contains("at " + CursorPosition.of(1_500L, "evt-mid"))
                            .doesNotContain("evt-mid"));
        }
    }

    @Test
    void windowEndingBeforeAnEnrichmentsPositionIsUntouched() {
        probeReturns(2L, 2_000L, "evt-b");
        clampedAt(9_000L, "evt-z");
        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        service.refresh(NAME);

        // A refresh already behind its enrichments must not be dragged anywhere: clamping unconditionally
        // to the enrichment's position would move a healthy rollup's window backwards.
        verify(insertSelect).insertSelect(eq("usage_daily"), sql.capture(), anyBoolean(), any(), any());
        assertThat(sql.getValue()).contains("<= (2000, 'evt-b')");
        verify(stateWriter).recordSuccess(NAME, 2_000L, "evt-b", 7L, false, null);
    }

    @Test
    void windowWhollyAboveAnEnrichmentsPositionWritesNothingAndHoldsTheCursor() {
        // Every probed row sits above the bound, so the clamped window holds no rows at all.
        probeReturns(2L, 5_000L, "evt-y", 4_000L, "evt-x");
        clampedAt(1_500L, "evt-mid");

        final RefreshOutcome outcome = service.refresh(NAME);

        // No statement worth running, and the cursor must not move: it would be sound but would report
        // progress for a run that wrote nothing.
        verifyNoInteractions(insertSelect);
        assertThat(outcome.rowsWritten()).isZero();
        verify(stateWriter).recordSuccess(NAME, null, null, 0L, false,
                new EnrichmentFreshness.Bound(ENRICHMENT, 1_500L, "evt-mid"));
    }

    @Test
    void enrichmentThatHasMaterializedNothingWithholdsTheRunBeforeAnyQuery() {
        given(freshness.bound(any(), eq(INPUT)))
                .willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, null, null)));

        final RefreshOutcome outcome = service.refresh(NAME);

        // Resolved before the probe, so a rollup whose enrichment has written nothing costs no ClickHouse
        // work at all — which matters because this is the state every such pipeline is in right after a
        // deploy, until the first materialize establishes a mark.
        verifyNoInteractions(executor);
        verifyNoInteractions(insertSelect);
        assertThat(outcome.rowsWritten()).isZero();
        verify(stateWriter).recordSuccess(NAME, null, null, 0L, false,
                new EnrichmentFreshness.Bound(ENRICHMENT, null, null));
    }

    @Test
    void refreshJoiningNoEnrichmentRendersTheSameStatementItAlwaysDid() {
        // The regression guard for every rollup that predates the clamp: with nothing to clamp against the
        // window, its predicates and the rendered statement must be what they were before.
        probeReturns(2L, 2_000L, "evt-b");
        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        service.refresh(NAME);

        verify(insertSelect).insertSelect(eq("usage_daily"), sql.capture(), anyBoolean(), any(), any());
        assertThat(sql.getValue())
                .contains("<= (2000, 'evt-b')")
                .doesNotContain("left outer join");
        verify(stateWriter).recordSuccess(NAME, 2_000L, "evt-b", 7L, false, null);
    }

    /** The referenced enrichments have been materialized only through this position. */
    private void clampedAt(long version, String identity) {
        given(freshness.bound(any(), eq(INPUT)))
                .willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, version, identity)));
    }

    @Test
    void probeThatFillsItsLimitReportsMoreWorkRemaining() {
        probeReturns(3L, 3L, "c");

        final RefreshOutcome outcome = service.refresh(NAME);

        // Distinguishes "caught up" from "still draining", which is what re-arms the pipeline for the next
        // tick instead of its own cron.
        assertThat(outcome.hasMore()).isTrue();
        verify(stateWriter).recordSuccess(NAME, 3L, "c", 7L, true, null);
    }

    @Test
    void theProbeAggregatesInsideClickHouseRatherThanReadingItsWindow() {
        // The point of the probe's shape, and the only thing a well-meaning "simplification" would quietly
        // undo: it asks for one row — a count and the end tuple — rather than selecting the window and
        // taking its last row in Java, which would move up to aggregate.window-limit rows per tick for the
        // same two values. Both forms are equally correct, which is exactly why nothing else catches it.
        probeReturns(2L, 2_000L, "evt-b");
        final ArgumentCaptor<SelectQuery<Record>> probe = ArgumentCaptor.forClass(SelectQuery.class);

        service.refresh(NAME);

        verify(executor).executeScan(probe.capture(), any(), any(), any());
        final String sql = probe.getValue().getSQL(ParamType.INLINED);
        assertThat(sql)
                .as("the probe collapses its window to one row inside the engine")
                .contains("count(*)")
                .contains("argMax")
                // The window's own limit stays inside the subquery: the end tuple is the last row of the
                // first N in cursor order, not the greatest row in the input, and capping is when they differ.
                .contains("limit " + WINDOW_LIMIT);
        // Both halves of the tuple come from the row that maximises the tuple, never from two independent
        // maxima — which would compose an end tuple no row ever had.
        assertThat(sql).doesNotContain("max(\"probed\".\"__cursor_v\")");
    }

    @Test
    void theStoredCursorBecomesTheWindowsLowerBound() {
        final PipelineEntity resumed = pipeline();
        resumed.setCursorVersion(500L);
        resumed.setCursorIdentity("evt-prev");
        given(pipelines.findById(NAME)).willReturn(Optional.of(resumed));
        probeReturns(1L, 900L, "evt-next");
        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        service.refresh(NAME);

        verify(insertSelect).insertSelect(anyString(), sql.capture(), anyBoolean(), any(), any());
        assertThat(sql.getValue()).contains("> (500, 'evt-prev')");
    }

    @Test
    void disabledPipelineCannotWrite() {
        final PipelineEntity disabled = pipeline();
        disabled.setEnabled(false);
        given(pipelines.findById(NAME)).willReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.refresh(NAME))
                .isInstanceOf(PipelineConflictException.class)
                .hasMessageContaining("preview");
        verifyNoInteractions(insertSelect);
        verify(stateWriter, never()).recordSuccess(anyString(), any(), any(), anyLong(), anyBoolean(), any());
    }

    @Test
    void previewIsAllowedWhileDisabledAndWritesNothing() {
        final PipelineEntity disabled = pipeline();
        disabled.setEnabled(false);
        given(pipelines.findById(NAME)).willReturn(Optional.of(disabled));
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of(
                sampleRow("gpt-4", 2L, 1_000L, 7L), sampleRow("gpt-5", 1L, 900L, 7L)));

        final PipelinePreview preview = service.preview(NAME);

        // The gate exists to be usable before enabling; it must never write, and it returns the sample's own
        // SELECT rather than a refresh statement.
        assertThat(preview.rows()).hasSize(2);
        assertThat(preview.statement()).startsWith("select").doesNotContainIgnoringCase("insert");
        // The window count is the target's size once caught up, and both helper columns are stripped.
        assertThat(preview.totalRows()).isEqualTo(7L);
        assertThat(preview.rows().getFirst()).containsOnlyKeys("deployment", "call_count");
        verifyNoInteractions(insertSelect);
    }

    @Test
    void anEmptyPreviewReportsZeroRowsAndWhy() {
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of());

        final PipelinePreview preview = service.preview(NAME);

        assertThat(preview.totalRows()).isZero();
        assertThat(preview.rows()).isEmpty();
        assertThat(preview.notes()).extracting(PreviewNote::code).containsExactly(PreviewNoteCode.NO_MATCHING_ROWS);
        // No filter is declared, so the note must not send the author looking for one.
        assertThat(preview.notes().getFirst().message()).contains("input '" + INPUT + "' holds no rows yet").doesNotContain("filter");
    }

    @Test
    void dateKeyCellBecomesItsCalendarDate() {
        assertThat(AggregateRefreshService.asDate(java.time.ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC)))
                .isEqualTo(java.time.LocalDate.of(2026, 1, 1));
        assertThat(AggregateRefreshService.asDate(java.time.LocalDateTime.of(2026, 1, 1, 0, 0)))
                .isEqualTo(java.time.LocalDate.of(2026, 1, 1));
        assertThat(AggregateRefreshService.asDate(java.time.LocalDate.of(2026, 1, 1))).isEqualTo(java.time.LocalDate.of(2026, 1, 1));
        assertThat(AggregateRefreshService.asDate("not temporal")).isEqualTo("not temporal");
    }

    @Test
    void anEmptyFilteredPreviewPointsAtTheFilter() {
        final PipelineEntity filtered = pipeline();
        filtered.setFilterSql("\"deployment\" = 'none'");
        given(pipelines.findById(NAME)).willReturn(Optional.of(filtered));
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of());

        final PipelinePreview preview = service.preview(NAME);

        assertThat(preview.notes()).extracting(PreviewNote::code).containsExactly(PreviewNoteCode.NO_MATCHING_ROWS);
        assertThat(preview.notes().getFirst().message()).contains("check the filter against the input");
    }

    @Test
    void previewReportsAnEnrichmentWithNoBoundAsHoldingTheRefreshBack() {
        given(freshness.bound(any(), eq(INPUT))).willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, null, null)));
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of(sampleRow("gpt-4", 2L, 1_000L, 1L)));

        final PipelinePreview preview = service.preview(NAME);

        // The sample stays unclamped; the note carries the one fact the old refresh statement got wrong.
        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.notes()).singleElement().satisfies(note -> {
            assertThat(note.code()).isEqualTo(PreviewNoteCode.HELD_BY_ENRICHMENT);
            assertThat(note.subject()).isEqualTo(ENRICHMENT);
        });
    }

    @Test
    void previewReportsAnEnrichmentBehindTheNewestSampledGroupWithTheGap() {
        given(freshness.bound(any(), eq(INPUT)))
                .willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, 1_000L, "a")));
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of(
                sampleRow("gpt-4", 2L, 3_601_000L, 2L), sampleRow("gpt-5", 1L, 500L, 2L)));

        final PipelinePreview preview = service.preview(NAME);

        assertThat(preview.notes()).singleElement().satisfies(note -> {
            assertThat(note.code()).isEqualTo(PreviewNoteCode.ENRICHMENT_BEHIND);
            assertThat(note.severity()).isEqualTo(PreviewNoteSeverity.INFO);
            assertThat(note.subject()).isEqualTo(ENRICHMENT);
            assertThat(note.message()).contains("1h");
        });
    }

    @Test
    void previewComparesTheWholeCursorTupleWithTheBound() {
        // Same version as the bound, past it by identity: the clamp would hold that row back, so it is behind.
        given(freshness.bound(any(), eq(INPUT)))
                .willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, 5_000L, "m")));
        final Map<String, Object> row = sampleRow("gpt-4", 2L, 5_000L, 1L);
        row.put(AggregateCompiler.LATEST_IDENTITY, "z");
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of(row));

        final PipelinePreview preview = service.preview(NAME);

        assertThat(preview.notes()).extracting(PreviewNote::code).containsExactly(PreviewNoteCode.ENRICHMENT_BEHIND);
        assertThat(preview.rows().getFirst()).doesNotContainKey(AggregateCompiler.LATEST_IDENTITY);
    }

    @Test
    void previewOfAnEnrichmentCaughtUpWithTheSampleCarriesNoNote() {
        given(freshness.bound(any(), eq(INPUT)))
                .willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, 5_000L, "a")));
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of(sampleRow("gpt-4", 2L, 5_000L, 1L)));

        assertThat(service.preview(NAME).notes()).isEmpty();
    }

    /** One grouped sample row as ClickHouse returns it, with the two helper columns the service strips. */
    private static Map<String, Object> sampleRow(String deployment, long calls, long latest, long total) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("deployment", deployment);
        row.put("call_count", calls);
        row.put(AggregateCompiler.LATEST_VERSION, latest);
        row.put(AggregateCompiler.LATEST_IDENTITY, "a");
        row.put(AggregateCompiler.TOTAL_GROUPS, total);
        return row;
    }

    /**
     * The preview's sample runs under {@code aggregate.preview.*} and is the only statement a preview runs:
     * it aggregates the whole input on every call, which is what took a host down, while the rendered
     * refresh statement is never executed and the window probe is not reached on this path.
     */
    @Test
    void previewSampleRunsUnderTheConfiguredResourceBudget() {
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of());
        final ArgumentCaptor<QuerySettings> settings = ArgumentCaptor.forClass(QuerySettings.class);

        service.preview(NAME);

        verify(executor).executeScan(any(), settings.capture(), any(), any());
        assertThat(settings.getValue().getAllSettings())
                .containsEntry("clickhouse_setting_max_execution_time",
                        String.valueOf(PREVIEW_BUDGET.timeout().toSeconds()))
                .containsEntry("clickhouse_setting_max_memory_usage",
                        String.valueOf(PREVIEW_BUDGET.maxMemory().toBytes()))
                // The half of the contrast with the probe above: this statement DOES aggregate, so it is
                // the one budget here that sends a spill threshold.
                .containsEntry("clickhouse_setting_max_bytes_before_external_group_by",
                        String.valueOf(PREVIEW_BUDGET.spillThreshold().toBytes()));
    }

    /**
     * The refresh window probe carries the shared {@code pipeline.budget.*} ceiling, not the preview's. A
     * probe's cost follows the physical layout of the tables it reads rather than the window it was asked
     * for, so it needs a ceiling of its own.
     *
     * <p>The two are told apart by the pipeline budget's <b>memory</b> ceiling and by its carrying <b>no
     * spill setting</b> — the probe has no {@code GROUP BY}, so there is no aggregation state to spill and
     * {@code max_bytes_before_external_group_by} is deliberately not sent. Those are structural facts
     * about the two budgets rather than a coincidence of their configured timeouts, which is what an
     * earlier version of this test compared and what made it break the moment a default moved.
     * Collapsing the two budgets onto one, or dropping this one, fails here.
     */
    @Test
    void refreshWindowProbeRunsUnderThePipelineReadBudget() {
        probeReturns(1L, 1L, "a");
        final ArgumentCaptor<QuerySettings> settings = ArgumentCaptor.forClass(QuerySettings.class);

        service.refresh(NAME);

        verify(executor).executeScan(any(), settings.capture(), any(), any());
        assertThat(settings.getValue().getAllSettings())
                .containsEntry("clickhouse_setting_max_execution_time",
                        String.valueOf(READ_BUDGET.timeout().toSeconds()))
                .containsEntry("clickhouse_setting_max_memory_usage",
                        String.valueOf(READ_BUDGET.maxMemory().toBytes()))
                .doesNotContainKey("clickhouse_setting_max_bytes_before_external_group_by");
    }

    @Test
    void previewStatementCarriesTheSettingsItRanUnderAndNoRefreshWindow() {
        given(executor.executeScan(any(), any(), any(), any())).willReturn(List.of());

        final String statement = service.preview(NAME).statement();

        // Run by hand, it reads the same rows under the same limits: the dedup settings and the preview budget,
        // sorted, and never the client-side query id. The test model is partition-local, so the per-partition
        // merge switch is among them.
        assertThat(statement).endsWith(" SETTINGS do_not_merge_across_partitions_select_final = 1, final = 1, "
                + "max_bytes_before_external_group_by = "
                + PREVIEW_BUDGET.spillThreshold().toBytes() + ", max_execution_time = "
                + PREVIEW_BUDGET.timeout().toSeconds() + ", max_memory_usage = " + PREVIEW_BUDGET.maxMemory().toBytes()
                + ", transform_null_in = 0");
        assertThat(statement).doesNotContain("query_id");
        // The sample is unwindowed: no safety lag, no seek, no refresh statement.
        assertThat(statement).doesNotContain("now64(3) - toIntervalMillisecond").doesNotContainIgnoringCase("insert");
    }

    @Test
    void refusedSensitiveColumnStopsTheWrite() {
        willThrow(new SensitiveColumnNotEntitledException("nope"))
                .given(validator).validate(anyString(), anyString(), anyString(), any(), any(), anyString());

        // Governance is re-applied at execution, so a column that became sensitive after registration stops
        // the rollup rather than quietly continuing to derive from it.
        assertThatThrownBy(() -> service.refresh(NAME))
                .isInstanceOf(SensitiveColumnNotEntitledException.class);
        verifyNoInteractions(insertSelect);
    }

    @Test
    void previewOverSensitiveColumnTheCallerMayNotReadIsRefusedBeforeAnythingIsRead() {
        willThrow(new SensitiveColumnNotEntitledException("nope"))
                .given(validator).validate(anyString(), anyString(), anyString(), any(), any(), anyString());

        // The refusal arming gives, with its 403 code rather than flattened into a 422, and no sample read.
        assertThatThrownBy(() -> service.preview(NAME)).isInstanceOf(SensitiveColumnNotEntitledException.class);
        verifyNoInteractions(executor);
    }

    @Test
    void refusedNullableMeasureStopsTheWriteAndLeavesTheCursorUnmoved() {
        willThrow(new PipelineValidationException("measure 'tokens' computes 'sum' over nullable input column"))
                .given(validator).validate(anyString(), anyString(), anyString(), any(), any(), anyString());

        // The nullability rule is re-applied at execution like every other rule on this path. An input column
        // that turns nullable after registration would otherwise silently change what every zero the pipeline
        // has ever written means — with nothing failing and nothing recording it.
        assertThatThrownBy(() -> service.refresh(NAME))
                .isInstanceOf(PipelineValidationException.class);
        verifyNoInteractions(insertSelect);
        verify(stateWriter, never()).recordSuccess(anyString(), any(), any(), anyLong(), anyBoolean(), any());
    }

    @Test
    void badFilterReportsUnderThePipelineErrorCode() {
        final PipelineEntity filtered = pipeline();
        filtered.setFilterSql("nonsense (");
        given(pipelines.findById(NAME)).willReturn(Optional.of(filtered));
        given(filterValidator.analyze(anyString(), any(), anyString(), anyBoolean()))
                .willThrow(new PipelineValidationException("filter is invalid: unexpected token"));

        // The filter validator is shared with the enrichment registry; answering a /v1/pipelines call with
        // `pipeline_validation_failed` would point a client at a rule that does not exist.
        assertThatThrownBy(() -> service.refresh(NAME))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("unexpected token");
    }

    /**
     * The kind guard in the shared loader. Both entry points take a name straight from the request path, so
     * before this the refusal came from the spec codec's internal invariant assertion and reached the caller
     * as {@code 500 internal_error} — see {@code AggregateRefreshService.requireAggregate}.
     *
     * <p>Only {@code refresh} is reachable with an enrich pipeline through the API: the preview endpoint
     * dispatches on the stored kind and routes one to {@code EnrichPreviewService}. This still calls both
     * because the guard is in the loader they share, and an enrich pipeline reaching the aggregate
     * machinery <em>by any route</em> must not compile a statement — the assertion is about the machinery,
     * not about which endpoint can still get here.
     */
    @Test
    void previewAndRefreshRefuseAnEnrichPipelineWithoutTouchingIt() {
        final PipelineEntity enrich = pipeline();
        enrich.setKind(PipelineKind.ENRICH);
        given(pipelines.findById(NAME)).willReturn(Optional.of(enrich));

        assertThatThrownBy(() -> service.refresh(NAME))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("is of kind 'enrich'")
                .hasMessageContaining("the aggregate refresh machinery serves the 'aggregate' kind");
        assertThatThrownBy(() -> service.preview(NAME))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("is of kind 'enrich'");

        verifyNoInteractions(insertSelect, stateWriter);
    }

    @Test
    void anUnknownPipelineIsNotFound() {
        given(pipelines.findById(NAME)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh(NAME)).isInstanceOf(PipelineNotFoundException.class);
        assertThatThrownBy(() -> service.preview(NAME)).isInstanceOf(PipelineNotFoundException.class);
    }

    @Test
    void everyRunIsRecordedAsMetricIncludingFailures() {
        probeReturns(1L, 1L, "a");
        service.refresh(NAME);
        verify(metrics).recordRun(eq(NAME), eq(true), eq(7L), any(), anyLong());

        willThrow(new IllegalStateException("target vanished"))
                .given(insertSelect).insertSelect(anyString(), anyString(), anyBoolean(), any(), any());
        assertThatThrownBy(() -> service.refresh(NAME)).isInstanceOf(IllegalStateException.class);
        // A rollup is served from stored rows, so a refresh that stopped looks like a quiet period at the
        // query surface — the failure has to reach the metric, not just the log.
        verify(metrics).recordRun(eq(NAME), eq(false), eq(0L), any(), eq(0L));
    }

    @Test
    void failedRunIsRecordedAgainstThePipelineNotJustReturned() {
        probeReturns(1L, 1L, "a");
        willThrow(new IllegalStateException("ClickHouse rejected the statement"))
                .given(insertSelect).insertSelect(anyString(), anyString(), anyBoolean(), any(), any());

        assertThatThrownBy(() -> service.refresh(NAME)).isInstanceOf(IllegalStateException.class);

        // On-demand and scheduled runs must leave the same trace: `GET /v1/pipelines/{name}` is where an
        // operator looks, and it would otherwise still be reporting the previous run's health.
        verify(stateWriter).recordFailure(NAME, "ClickHouse rejected the statement");
        verify(stateWriter, never()).recordSuccess(anyString(), any(), any(), anyLong(), anyBoolean(), any());
    }

    @Test
    void failureWithNoMessageIsStillRecordedLegibly() {
        probeReturns(1L, 1L, "a");
        willThrow(new NullPointerException()).given(insertSelect).insertSelect(anyString(), anyString(), anyBoolean(), any(), any());

        assertThatThrownBy(() -> service.refresh(NAME)).isInstanceOf(NullPointerException.class);

        // A null message stored as-is would clear `last_error` and read as a success.
        verify(stateWriter).recordFailure(NAME, "NullPointerException");
    }

    @Test
    void anIneligibleRefreshIsNotInstrumentedAtAll() {
        given(pipelines.findById("bogus")).willReturn(Optional.empty());
        final PipelineEntity disabled = pipeline();
        disabled.setEnabled(false);
        given(pipelines.findById(NAME)).willReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.refresh("bogus")).isInstanceOf(PipelineNotFoundException.class);
        assertThatThrownBy(() -> service.refresh(NAME)).isInstanceOf(PipelineConflictException.class);

        // Neither rejection executed a statement, so neither is a refresh run. It also matters that the
        // unknown name is not instrumented: `{name}` is a caller-supplied path variable and it tags every
        // series, so instrumenting it would let arbitrary spellings grow the meter registry without bound.
        verifyNoInteractions(metrics);
        verify(stateWriter, never()).recordFailure(anyString(), anyString());
        // Same reasoning for the span: tracing an unknown name would mint one per spelling.
        assertThat(spans.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void refreshRunsInsideAnInternalSpanNamingThePipeline() {
        probeReturns(2, 1_700_000_000_000L, "evt-9");

        service.refresh(NAME);

        // The ClickHouse client spans this run raises carry no statement text by design, so without a
        // parent identifying the pipeline nothing attributes them to a declaration.
        assertThat(spans.getFinishedSpanItems()).singleElement().satisfies(span -> {
            assertThat(span.getName()).isEqualTo("aggregate refresh");
            assertThat(span.getKind()).isEqualTo(SpanKind.INTERNAL);
            assertThat(span.getAttributes().asMap())
                    .containsEntry(AttributeKey.stringKey("pipeline.name"), NAME)
                    .containsEntry(AttributeKey.stringKey("pipeline.input"), INPUT)
                    .containsEntry(AttributeKey.stringKey("pipeline.target"), "usage_daily");
        });
    }

    @Test
    void successfulRefreshIsVisibleAtInfo() {
        probeReturns(2, 1_700_000_000_000L, "evt-9");

        try (LogCapture log = LogCapture.of(AggregateRefreshService.class)) {
            service.refresh(NAME);

            // Before this, the whole subsystem was silent at INFO until something failed — indistinguishable
            // from a scheduler that had stopped running.
            assertThat(log.messagesAt(Level.INFO)).singleElement().satisfies(line -> assertThat(line)
                    .contains(NAME)
                    .contains("7 row(s) written")
                    .contains("has_more=false"));
        }
    }

    @Test
    void failedRefreshStillReportsAtWarnNotInfo() {
        probeReturns(2, 1_700_000_000_000L, "evt-9");
        willThrow(new IllegalStateException("clickhouse said no"))
                .given(insertSelect).insertSelect(anyString(), anyString(), anyBoolean(), any(), any());

        try (LogCapture log = LogCapture.of(AggregateRefreshService.class)) {
            assertThatThrownBy(() -> service.refresh(NAME)).isInstanceOf(IllegalStateException.class);

            // A failure must not be reported as a completed run: the INFO line is what an operator reads
            // as "this pipeline is fine".
            assertThat(log.messagesAt(Level.INFO)).isEmpty();
        }
    }

    @Test
    void failedRefreshMarksItsSpanInError() {
        probeReturns(2, 1_700_000_000_000L, "evt-9");
        willThrow(new IllegalStateException("clickhouse said no"))
                .given(insertSelect).insertSelect(anyString(), anyString(), anyBoolean(), any(), any());

        assertThatThrownBy(() -> service.refresh(NAME)).isInstanceOf(IllegalStateException.class);

        assertThat(spans.getFinishedSpanItems()).singleElement().satisfies(span ->
                assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR));
    }

    // ---- the write's budget

    @Test
    void theWriteRunsUnderThePipelineBudgetWithItsOwnStatementTag() {
        probeReturns(2L, 2_000L, "evt-b");

        service.refresh(NAME);

        final ArgumentCaptor<StatementTag> tag = ArgumentCaptor.forClass(StatementTag.class);
        verify(insertSelect).insertSelect(eq("usage_daily"), anyString(), anyBoolean(), eq(READ_BUDGET), tag.capture());
        assertThat(tag.getValue()).isEqualTo(new StatementTag(NAME, "refresh-write"));
    }

    /**
     * A refresh recomputes every affected group key in full, so a prefix of its window is not a smaller correct
     * write: a refusal fails the run, is recorded, and leaves the cursor where it was — one write, no retry.
     */
    @Test
    void writeRefusedByTheBudgetFailsTheRunWithoutNarrowing() {
        probeReturns(2L, 2_000L, "evt-b");
        willThrow(new ClickHouseQueryException("ClickHouse rejected the statement",
                new ServerException(159, "Timeout exceeded: elapsed 30 seconds", 500, "q")))
                .given(insertSelect).insertSelect(anyString(), anyString(), anyBoolean(), any(), any());

        assertThatThrownBy(() -> service.refresh(NAME))
                .isInstanceOf(PipelineReadBudgetExceededException.class)
                .hasMessageContaining("aggregate refresh write")
                // A refresh's lever is its window, not the read remedy's heavy columns and partition alignment.
                .hasMessageContaining("aggregate.window-limit")
                .hasMessageNotContaining("already at its smallest");

        verify(insertSelect).insertSelect(anyString(), anyString(), anyBoolean(), any(), any());
        verify(executor).executeScan(any(), any(), any(), any());
        verify(stateWriter).recordFailure(eq(NAME), anyString());
        verify(stateWriter, never()).recordSuccess(anyString(), any(), any(), anyLong(), anyBoolean(), any());
        verify(metrics).recordRun(eq(NAME), eq(false), eq(0L), any(), eq(0L));
    }

    // ---- change-only held-back lines

    @Test
    void theHeldBackLineIsLoggedOncePerTransition() {
        given(freshness.bound(any(), eq(INPUT)))
                .willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, null, null)));

        try (LogCapture log = LogCapture.of(AggregateRefreshService.class)) {
            service.refresh(NAME);
            service.refresh(NAME);
            service.refresh(NAME);
            assertThat(log.messagesAt(Level.INFO)).filteredOn(line -> line.contains("held back"))
                    .singleElement().asString().contains("is held back entirely").contains(ENRICHMENT);

            // The enrichment materializes; the next run writes and says the hold is over, once.
            given(freshness.bound(any(), eq(INPUT))).willReturn(Optional.empty());
            probeReturns(2L, 2_000L, "evt-b");
            service.refresh(NAME);
            service.refresh(NAME);

            assertThat(log.messagesAt(Level.INFO)).filteredOn(line -> line.contains("held back")).hasSize(2)
                    .last().asString().contains("no longer held back");
        }
    }

    @Test
    void theAboveBoundLineIsLoggedOnlyWhenItStarts() {
        probeReturns(2L, 2_000L, "evt-b", 1_800L, "evt-a");
        given(freshness.bound(any(), eq(INPUT)))
                .willReturn(Optional.of(new EnrichmentFreshness.Bound(ENRICHMENT, 1_500L, "evt-mid")));

        try (LogCapture log = LogCapture.of(AggregateRefreshService.class)) {
            service.refresh(NAME);
            service.refresh(NAME);

            assertThat(log.messagesAt(Level.INFO)).filteredOn(line -> line.contains("probed only rows above"))
                    .hasSize(1);
        }
    }

    @Test
    void pipelineThatWasNeverHeldBackLogsNoTransition() {
        probeReturns(0L, null, null);

        try (LogCapture log = LogCapture.of(AggregateRefreshService.class)) {
            service.refresh(NAME);

            // "No new input rows" stays at DEBUG, and there was no hold to leave.
            assertThat(log.messagesAt(Level.INFO)).filteredOn(line -> line.contains("held back")).isEmpty();
            assertThat(log.messagesAt(Level.DEBUG)).anySatisfy(line -> assertThat(line).contains("no new input rows"));
        }
    }
}
