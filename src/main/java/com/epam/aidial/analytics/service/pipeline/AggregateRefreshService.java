package com.epam.aidial.analytics.service.pipeline;

import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseInsertSelectExecutor;
import com.epam.aidial.analytics.clickhouse.FinalSettings;
import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.configuration.AggregateProperties;
import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.exception.PipelineConflictException;
import com.epam.aidial.analytics.exception.PipelineNotFoundException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.observability.ClientSpanTracer;
import com.epam.aidial.analytics.observability.CursorPosition;
import com.epam.aidial.analytics.observability.SqlLog;
import com.epam.aidial.analytics.observability.TransitionLog;
import com.epam.aidial.analytics.observability.trace.LogExecution;
import com.epam.aidial.analytics.service.enrichment.support.CursorColumns;
import com.epam.aidial.analytics.service.enrichment.support.CursorPredicates;
import com.epam.aidial.analytics.service.enrichment.support.ScanSourceMetadata;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.Measure;
import com.epam.aidial.analytics.service.pipeline.model.RefreshOutcome;
import com.epam.aidial.analytics.service.pipeline.preview.PipelinePreview;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumn;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumnRole;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNote;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNoteCode;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNotes;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewValueCapper;
import com.epam.aidial.analytics.service.pipeline.preview.SampledPosition;
import com.epam.aidial.analytics.service.pipeline.preview.TargetCoercion;
import com.epam.aidial.analytics.service.query.EnrichmentJoins;
import com.epam.aidial.analytics.service.query.PortableQueryRenderer;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.security.RowPolicyFilter;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The aggregate data plane: recomputes one pipeline's rollup over one bounded window, inside ClickHouse.
 *
 * <p><b>Probe, then write</b> — the same two-statement shape the Class-1 materialize path uses, and for
 * the same reason. The probe picks the window's end tuple and asks whether the window filled its limit,
 * deriving both <em>inside ClickHouse</em> so one row comes back whatever the window holds; the
 * write is a single {@code INSERT … SELECT} whose dirty-key sub-select is bounded by exactly that window.
 * Neither statement moves a row payload across the wire — the probe's result is one row and the write's
 * values are computed in the database — so a refresh's traffic is constant in the size of the window it
 * covers. Choosing the window <em>before</em> executing it is what makes the effect repeatable: a run that times
 * out, fails, or is retried re-executes an identical {@code (cursorFrom, windowEnd]} range, derives the
 * same set of group keys, recomputes the same values, and the target — a {@code ReplacingMergeTree} on
 * the group key — collapses the duplicate write to a no-op in effect.
 *
 * <p>That property is what lets the scheduler run on every instance without leader election, and it is
 * why the cursor advances <b>only</b> after the write succeeds: a failed window is simply re-run.
 *
 * <p>Governance is re-applied here, not trusted from registration: a stored pipeline's input schema can
 * change under it, and a column can become {@code sensitive} after the fact. A refresh that kept writing
 * through such a change would produce derived numbers nobody could account for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class AggregateRefreshService {

    private static final String CURSOR_VERSION = "__cursor_v";
    private static final String CURSOR_IDENTITY = "__cursor_id";
    private static final String START_VERSION = "__start_v";
    private static final String START_IDENTITY = "__start_id";
    private static final String WINDOW_COUNT = "__window_n";

    /** Span name for one refresh. Low-cardinality by design — the pipeline is an attribute, not the name. */
    private static final String REFRESH_SPAN = "aggregate refresh";
    private static final AttributeKey<String> SPAN_PIPELINE = AttributeKey.stringKey("pipeline.name");
    private static final AttributeKey<String> SPAN_INPUT = AttributeKey.stringKey("pipeline.input");
    private static final AttributeKey<String> SPAN_TARGET = AttributeKey.stringKey("pipeline.target");

    /** What reduces a refused refresh write's cost; a read's "already at its smallest" remedy does not hold. */
    private static final String WRITE_REMEDY = "A refresh recomputes every group key its window touches, in full: "
            + "lower aggregate.window-limit so a window touches fewer, or have the budget raised.";

    private final PipelineRepository pipelines;
    private final PipelineStateWriter stateWriter;
    private final AggregateSpecValidator validator;
    private final AggregateCompiler compiler;
    private final AggregateSqlCompiler sqlCompiler;
    private final ScanSourceMetadata scanSources;
    private final EnrichmentFreshness freshness;
    private final PipelineSpecCodec specCodec;
    private final SensitiveProjectionGuard sensitiveGuard;
    private final FilterSqlValidator filterValidator;
    private final FilterTranslator filterTranslator;
    private final StructuredQueryExecutor executor;
    private final ClickHouseInsertSelectExecutor insertSelect;
    private final RowPolicyFilter rowPolicies;
    private final DSLContext renderContext;
    private final AggregateMetrics metrics;
    private final AggregateProperties properties;
    private final PipelineBudgetProperties readBudget;
    private final ClientSpanTracer spanTracer;
    private final TransitionLog transitions;
    private final PreviewValueCapper capper;
    private final PortableQueryRenderer portable;

    /**
     * The probed window's <b>start and end</b> tuples, plus whether the probe filled its limit.
     *
     * <p>The start is carried only for the enrichment clamp, and only to tell two cases apart that would
     * otherwise need a second query: a clamp that <em>trims</em> the window (some of it lies at or below the
     * bound, so there is work to do) from one that <em>withholds</em> it entirely (every probed row is above
     * the bound). Writing in the second case would execute an {@code INSERT … SELECT} guaranteed to produce
     * nothing, and advancing the cursor to the bound would be sound but would mean a scheduled tick doing
     * pure work for no rows. Both tuples come out of one collapsed probe, so the distinction is free.
     */
    private record Window(
            long startVersion, String startIdentity, long version, String identity, boolean hasMore) {}

    /**
     * Refreshes one pipeline over a single bounded window.
     *
     * <p><b>Eligibility is settled before the run is instrumented</b>, deliberately. Both gates below reject
     * without a statement ever being executed, so neither is a refresh run that could succeed or fail — and
     * the metric series are tagged with {@code name}, which on this path is a caller-supplied path variable.
     * Instrumenting an unknown name would let {@code POST /v1/pipelines/<anything>/refresh} mint a permanent
     * counter and timer series per spelling, growing the meter registry without bound.
     *
     * <p>A failure of the run itself is recorded against the pipeline as well as counted, so
     * {@code GET /v1/pipelines/{name}} reports the same health whether the refresh was triggered on demand
     * or by the scheduler.
     *
     * <p><b>The run is wrapped in an {@code INTERNAL} span</b>, placed here rather than in the scheduler so
     * the on-demand path is traced too, and inside the timing so the span and
     * {@code aggregate.refresh.duration} describe the same interval. It sits <em>after</em> the two gates
     * for the same reason they precede the metrics: a rejection that executes no statement is not a run,
     * and {@code name} is a caller-supplied path variable — tracing one would mint a span per spelling of
     * a pipeline that does not exist. The ClickHouse client spans the probe and the write raise carry no
     * statement text by design, so without this parent nothing attributes them to a declaration.
     *
     * @return the outcome: rows written, whether more work remains, and the advanced cursor
     * @throws PipelineNotFoundException if the pipeline is unknown
     * @throws PipelineConflictException if the pipeline is disabled — see {@link #requireEnabled}
     */
    public RefreshOutcome refresh(String name) {
        final PipelineEntity pipeline = require(name);
        requireEnabled(pipeline);
        // A no-op for the scheduler's system identity, which carries no provider role a policy could match.
        rowPolicies.requireUnconfinedPipeline(pipeline.getInputTable(), pipeline.getTarget(), "refresh this pipeline");

        final Attributes attributes = Attributes.builder()
                .put(SPAN_PIPELINE, name)
                .put(SPAN_INPUT, pipeline.getInputTable())
                .put(SPAN_TARGET, pipeline.getTarget())
                .build();
        final long startNanos = System.nanoTime();
        try {
            final RefreshOutcome outcome =
                    spanTracer.internalSpan(REFRESH_SPAN, attributes, () -> runRefresh(pipeline));
            final Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            metrics.recordRun(name, true, outcome.rowsWritten(), elapsed, outcome.lagSeconds());
            log.info("Pipeline '{}' refreshed in {} ms: {} row(s) written, has_more={}, lag={}s",
                    name, elapsed.toMillis(), outcome.rowsWritten(), outcome.hasMore(), outcome.lagSeconds());
            return outcome;
        } catch (RuntimeException ex) {
            metrics.recordRun(name, false, 0L, Duration.ofNanos(System.nanoTime() - startNanos), 0L);
            recordFailure(name, ex);
            throw ex;
        }
    }

    /**
     * Records the failure against the pipeline so it is visible in the state view, never masking the
     * failure being reported: the state write runs in its own transaction and can itself fail (the same
     * Postgres outage may be why the refresh failed), which must not replace the original cause.
     */
    private void recordFailure(String name, RuntimeException cause) {
        final String message = StringUtils.defaultIfBlank(cause.getMessage(), cause.getClass().getSimpleName());
        try {
            stateWriter.recordFailure(name, message);
        } catch (RuntimeException ex) {
            log.warn("Could not record the failure of pipeline '{}': {}", name, ex.getMessage());
        }
    }

    private RefreshOutcome runRefresh(PipelineEntity pipeline) {
        final String name = pipeline.getName();
        // Decoded once and threaded through every step below. The declaration lives in an untyped JSON
        // column since the two registries merged, so each read of it is a Jackson parse rather than a field
        // access — and this method needs it four times (the gate, the measure filters, the clamp's field
        // set, and the compiler).
        final AggregateSpec spec = specCodec.readAggregate(pipeline);

        // Re-validate in full: schema drift and a newly-sensitive column both invalidate a stored spec. The
        // sensitive gate runs inside validate(), against the identity this refresh is executing as.
        // For an SQL declaration the gate hands back the parsed statement, which the rest of this method compiles.
        final Gated gated = gate(pipeline, spec, "aggregate refresh");

        final String input = gated.input();
        // The entity, not the bare source: a declaration may name an enrichment column, and the compiler
        // can only emit the grain join for one if the model it is handed still carries the enrichments.
        final QueryEntityModel model = scanSources.entityModel(input);
        final CursorColumns cursorColumns = scanSources.cursorColumns(input);
        final Declaration declaration = declaration(pipeline, spec, gated, model);
        final AggregateCompiler.CompiledFilter filter = declaration.filter();

        // How far the enrichments this refresh joins have actually been filled in. Resolved before the probe
        // so a bound that withholds everything costs no ClickHouse work at all, and empty — so the rest of
        // this method is the pre-existing path byte for byte — whenever the declaration joins nothing.
        final Optional<EnrichmentFreshness.Bound> bound = enrichmentBound(input, model, declaration.referencedFields());
        if (bound.isPresent() && bound.get().withholdsEverything()) {
            // A referenced enrichment's pipeline exists but has recorded nothing usable — it has written nothing
            // yet, or a mutation invalidated its mark. It is expected to write, so its absent values are late
            // rather than final and reading past them is the very thing the clamp exists to prevent. Resolves
            // within one enrichment cadence. Logged when it starts, not on every tick it lasts.
            holdState(name, Hold.HELD, bound.get().enrichment());
            stateWriter.recordSuccess(name, null, null, 0L, false, bound.get());
            return new RefreshOutcome(0L, false, PipelineEntity.stalenessSeconds(pipeline.getCursorVersion()));
        }

        final List<Condition> windowPredicates = windowPredicates(pipeline, cursorColumns);
        final Window window = probe(name, model, cursorColumns, filter, windowPredicates);
        if (window == null) {
            // Nothing new: write nothing and leave the cursor where it is, so no window is ever skipped. No
            // clamp is recorded — there was nothing for one to hold back, and reporting one here would make
            // the indicator mean "an enrichment is behind" in some runs and "the input is quiet" in others.
            log.debug("Pipeline '{}' has no new input rows; refresh is a no-op", name);
            holdState(name, Hold.CLEAR, null);
            stateWriter.recordSuccess(name, null, null, 0L, false, null);
            return new RefreshOutcome(0L, false, PipelineEntity.stalenessSeconds(pipeline.getCursorVersion()));
        }

        // Clamp only when the probed window would end PAST the bound. A refresh already behind its
        // enrichments is untouched: clamping unconditionally would drag a healthy rollup backwards.
        final EnrichmentFreshness.Bound clamp = bound
                .filter(at -> endsPast(window, at))
                .orElse(null);
        if (clamp != null && startsPast(window, clamp)) {
            // Every row the probe found sits above the bound, so the clamped window holds no rows at all.
            // Skip the write rather than execute a statement that cannot produce any, and leave the cursor
            // alone: advancing it to the bound would be sound (nothing matching lies below it) but buys
            // nothing, and a cursor that moves while no rows were written is a confusing thing to debug.
            holdState(name, Hold.ABOVE_BOUND, clamp.enrichment());
            stateWriter.recordSuccess(name, null, null, 0L, false, clamp);
            return new RefreshOutcome(0L, false, PipelineEntity.stalenessSeconds(pipeline.getCursorVersion()));
        }
        final long endVersion = clamp == null ? window.version() : clamp.version();
        final String endIdentity = clamp == null ? window.identity() : clamp.identity();

        // Pin the window's end so the write covers exactly the range that was probed — or, where a clamp
        // applies, exactly the part of it the referenced enrichments have been materialized through.
        final List<Condition> pinned = new ArrayList<>(windowPredicates);
        pinned.add(CursorPredicates.atMost(cursorColumns, endVersion, endIdentity));

        final AggregateCompiler.Compiled compiled = declaration.compileRefresh(pipeline.getTarget(), pinned);
        log.debug("Refreshing pipeline '{}' from '{}' into '{}'", name, input, pipeline.getTarget());
        // The statement a scheduled run actually executed — the one place it is visible, since preview returns
        // only the declaration as a portable query: a run that behaved unexpectedly at 03:00 otherwise leaves nothing to
        // compare against. DEBUG because it is large and identical on every run of an unchanged declaration.
        SqlLog.LOG.debug("Pipeline '{}' refresh statement: {}", name, compiled.sql());
        final long rowsWritten = write(pipeline, compiled.sql(), model);
        holdState(name, Hold.CLEAR, null);

        if (clamp != null) {
            log.info("Pipeline '{}' window clamped to enrichment '{}' at {}; {} row(s) written",
                    name, clamp.enrichment(), CursorPosition.of(clamp.version(), clamp.identity()), rowsWritten);
        }
        // has_more keeps its own meaning — "the probe filled the window limit" — and is deliberately not
        // forced true by a clamp. A clamped pipeline waits for its own cron rather than being re-run on
        // every scheduler tick against a bound that has not moved; the clamp indicator is what says it is
        // being held back.
        stateWriter.recordSuccess(name, endVersion, endIdentity, rowsWritten, window.hasMore(), clamp);
        return new RefreshOutcome(rowsWritten, window.hasMore(), PipelineEntity.stalenessSeconds(endVersion));
    }

    /**
     * The refresh write, under the same {@code pipeline.budget.*} ceilings as its probe.
     *
     * <p><b>Refused, never narrowed.</b> A refresh recomputes every affected group key in full, so a prefix of
     * its window is not a smaller correct write the way a Class-1 prefix is. A refusal fails the run like any
     * other failure: {@link #refresh} records it against the pipeline, the cursor does not move, and an
     * on-demand refresh answers {@code 422 pipeline_read_budget_exceeded}. The ceiling is what stops one hung
     * recompute from holding the aggregate scheduler, which runs refreshes serially on a pool of one by default;
     * {@code aggregate.window-limit} is what bounds the recompute when it is refused.
     */
    private long write(PipelineEntity pipeline, String sql, QueryEntityModel model) {
        try {
            return insertSelect.insertSelect(pipeline.getTarget(), sql, model.partitionLocal(), readBudget,
                    new StatementTag(pipeline.getName(), "refresh-write"));
        } catch (RuntimeException ex) {
            throw PipelineReadBudget.refusal(ex, "aggregate refresh write", readBudget, WRITE_REMEDY);
        }
    }

    /** Why a refresh wrote nothing for an enrichment's sake, for the change-only held-back lines. */
    private enum Hold { CLEAR, HELD, ABOVE_BOUND }

    /**
     * Logs a held-back state at INFO when it changes for this pipeline, entering or leaving it, and at DEBUG on
     * the ticks it only lasts. A refresh held back by an unmaterialized enrichment is re-run on every tick, and
     * the line is there to show when that starts and stops. The state is in memory per replica, and the tick
     * runs on every replica, so each logs its own transitions.
     */
    private void holdState(String name, Hold hold, String enrichment) {
        final String state = hold == Hold.CLEAR ? hold.name() : hold.name() + ":" + enrichment;
        final String previous = transitions.swap(TransitionLog.Channel.AGGREGATE_HOLD, name, state);
        final boolean changed = !state.equals(previous) && !(previous == null && hold == Hold.CLEAR);
        switch (hold) {
            case HELD -> {
                if (changed) {
                    log.info("Pipeline '{}' is held back entirely: enrichment '{}' has no usable materialized "
                            + "position yet", name, enrichment);
                } else {
                    log.debug("Pipeline '{}' is still held back by enrichment '{}'", name, enrichment);
                }
            }
            case ABOVE_BOUND -> {
                if (changed) {
                    log.info("Pipeline '{}' probed only rows above enrichment '{}''s materialized position; "
                            + "nothing to write yet", name, enrichment);
                } else {
                    log.debug("Pipeline '{}' probed only rows above enrichment '{}'; nothing to write yet",
                            name, enrichment);
                }
            }
            case CLEAR -> {
                if (changed) {
                    log.info("Pipeline '{}' is no longer held back by an enrichment ({})",
                            name, previous.substring(previous.indexOf(':') + 1));
                }
            }
            default -> throw new IllegalStateException("unknown hold " + hold);
        }
    }

    /**
     * The position no window of this refresh may close past, given the enrichments its statements actually
     * join. Empty when it joins none, which is what keeps every pre-existing rollup unaffected.
     *
     * <p>The joined set comes from {@link EnrichmentJoins#referencedEnrichments} over the same field set the
     * compiler hands its widest statement, rather than from a second reading of the declaration: the clamp
     * has to cover exactly what the SQL reads, and a set derived independently could drift from it — which
     * would leave a safety mechanism reporting that it was working while missing a join.
     */
    private Optional<EnrichmentFreshness.Bound> enrichmentBound(
            String input, QueryEntityModel model, Set<String> referenced) {
        final Set<String> joined = EnrichmentJoins.referencedEnrichments(model, referenced);
        return freshness.bound(joined, input);
    }

    private static boolean endsPast(Window window, EnrichmentFreshness.Bound bound) {
        return CursorPredicates.compareCursors(
                window.version(), window.identity(), bound.version(), bound.identity()) > 0;
    }

    private static boolean startsPast(Window window, EnrichmentFreshness.Bound bound) {
        return CursorPredicates.compareCursors(
                window.startVersion(), window.startIdentity(), bound.version(), bound.identity()) > 0;
    }

    /**
     * The dry-run gate. Reads only: it runs the full declaration gate, then returns a bounded sample of what the
     * declaration produces over the <em>whole input</em>, deliberately not window-restricted, because the
     * question a sample answers is "are these the right numbers" and a window obscures it.
     *
     * <p><b>No refresh statement.</b> The window a refresh takes — the safety lag, the seek past the cursor, the
     * probed end, the cap, the enrichment clamp — is runtime state the pipeline read serves, and one rendered
     * without a probe was neither what a refresh runs nor safe to copy out of a response. The one fact it carried
     * that matters before arming, "would a run write nothing?", is the {@code held_by_enrichment} note.
     *
     * <p><b>Not the sample's statement either.</b> What is returned is the declaration as a query the caller can run
     * through the query API, rendered against the caller's own view of the input (see {@link
     * PortableQueryRenderer#tryRender}); the physical sample, its helper columns and its settings are logged at DEBUG.
     * The sample's columns are typed by the gate, and {@code total_rows} is counted by the sample before its
     * {@code LIMIT}.
     */
    public PipelinePreview preview(String name) {
        final PipelineEntity pipeline = require(name);
        rowPolicies.requireUnconfinedPipeline(pipeline.getInputTable(), pipeline.getTarget(), "preview this pipeline");
        // As in runRefresh: one decode, threaded — preview reads the declaration several times.
        final AggregateSpec spec = specCodec.readAggregate(pipeline);
        // A disabled pipeline may be a draft that has never been gated, and its preview reproduces every
        // refusal arming it would meet — the trigger's entity-level rules as well as the kind gate below —
        // so that "preview passes" means "arming will pass". The gate itself runs in both states, as it
        // always has: an aggregate preview has no lighter path to keep.
        if (!pipeline.isEnabled()) {
            PipelineService.requireArmableTrigger(pipeline);
        }
        final Gated gated = gate(pipeline, spec, "aggregate preview");
        // The input the gate resolved: an SQL draft saved before its FROM table existed stores none, and the
        // statement's table is the one it reads.
        final String input = gated.input();
        if (!Objects.equals(input, pipeline.getInputTable())) {
            rowPolicies.requireUnconfinedPipeline(input, pipeline.getTarget(), "preview this pipeline");
        }

        final QueryEntityModel model = scanSources.entityModel(input);
        final CursorColumns cursorColumns = scanSources.cursorColumns(input);
        final Declaration declaration = declaration(pipeline, spec, gated, model);
        final SelectQuery<Record> sample = declaration.compilePreview(cursorColumns, properties.previewRows());
        // A select item whose target column's name is taken by an entity field is aliased by position in the sample;
        // its cells are returned under the target column like every other one.
        final Map<String, String> renamed = declaration.sampleAliases();
        final QuerySettings settings = PreviewBudget.settings(properties.preview());
        // The physical sample is for whoever reads the log, rendered through the same merge the executor applies so
        // it carries the settings it ran under; the response carries the declaration as the query API runs it.
        SqlLog.LOG.debug("Pipeline '{}' preview statement: {}", name,
                FinalSettings.render(sample.getSQL(ParamType.INLINED), settings, model.partitionLocal()));
        final PortableQueryRenderer.Portable rendered =
                portable.tryRender(declaration.portableQuery(input, properties.previewRows()));
        final List<Map<String, Object>> sampled = runSample(name, sample, settings, model);

        final long totalRows = sampled.isEmpty() ? 0L : ((Number) sampled.getFirst().get(AggregateCompiler.TOTAL_GROUPS)).longValue();
        final SampledPosition newest =
                SampledPosition.newest(sampled, AggregateCompiler.LATEST_VERSION, AggregateCompiler.LATEST_IDENTITY);
        final List<PreviewColumn> columns = declaration.previewColumns(input, pipeline.getTarget());
        final Set<String> dateKeys = columns.stream()
                .filter(column -> column.role() == PreviewColumnRole.KEY && column.type() == QueryFieldType.DATE)
                .map(PreviewColumn::name)
                .collect(Collectors.toSet());
        final List<Map<String, Object>> rows = new ArrayList<>(sampled.size());
        for (final Map<String, Object> row : sampled) {
            final Map<String, Object> stripped = new LinkedHashMap<>();
            row.forEach((column, value) -> {
                if (!AggregateCompiler.PREVIEW_HELPER_COLUMNS.contains(column)) {
                    stripped.put(renamed.getOrDefault(column, column), value);
                }
            });
            dateKeys.forEach(key -> stripped.computeIfPresent(key, (k, value) -> asDate(value)));
            rows.add(stripped);
        }

        final List<PreviewNote> notes = new ArrayList<>();
        // The bound the refresh would clamp to, computed for the notes only: the sample stays unclamped.
        final Optional<EnrichmentFreshness.Bound> bound = enrichmentBound(input, model, declaration.referencedFields());
        if (bound.isPresent() && bound.get().withholdsEverything()) {
            notes.add(PreviewNotes.heldByEnrichment(bound.get().enrichment()));
        } else if (bound.isPresent() && newest != null && newest.isPast(bound.get())) {
            notes.add(PreviewNotes.enrichmentBehind(bound.get().enrichment(), newest.version(), bound.get().version(), true));
        }
        if (rows.isEmpty()) {
            notes.add(declaration.noMatchingRows(input));
        }
        notes.addAll(TargetCoercion.notes(columns, rows, declaration.valuePreservingMeasures()));
        final PreviewValueCapper.Capped capped = capper.cap(columns, rows);
        notes.addAll(capped.notes());
        if (!rendered.rendered()) {
            notes.add(PreviewNotes.statementNotPortable(rendered.subject(), rendered.refusal()));
        }
        return new PipelinePreview(rendered.sql(), rendered.query(), capped.columns(), capped.rows(), totalRows, null,
                notes);
    }

    /**
     * A day, week or month bucket's cell as the calendar date its column reports. The bucket expression is a
     * UTC midnight timestamp ({@code toStartOfInterval(…, 'UTC')} yields {@code DateTime('UTC')} for a day), which
     * the {@code date} target stores as that date; the sample shows it the same way rather than as a midnight
     * instant under a {@code date} header. Only the preview's cells change — the refresh writes the bucket as is.
     */
    static Object asDate(Object value) {
        return switch (value) {
            case LocalDate date -> date;
            case LocalDateTime local -> local.toLocalDate();
            case OffsetDateTime offset -> offset.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
            case ZonedDateTime zoned -> zoned.withZoneSameInstant(ZoneOffset.UTC).toLocalDate();
            case Instant instant -> LocalDate.ofInstant(instant, ZoneOffset.UTC);
            default -> value;
        };
    }

    /** The {@code no_matching_rows} note: blame the filter only when one is declared. */
    static PreviewNote noMatchingRows(PipelineEntity pipeline) {
        return noMatchingRows(pipeline.getInputTable(), pipeline.getFilterSql());
    }

    private static PreviewNote noMatchingRows(String input, String filterSql) {
        if (StringUtils.isBlank(filterSql)) {
            return PreviewNote.of(PreviewNoteCode.NO_MATCHING_ROWS, "input '" + input
                    + "' holds no rows yet, so the declaration produces no group");
        }
        return PreviewNote.of(PreviewNoteCode.NO_MATCHING_ROWS, "no input row matches this pipeline's "
                + "`filter`, so the declaration produces no group; check the filter against the input");
    }

    /** The {@code no_matching_rows} note of an SQL declaration: blame its WHERE only when the block has one. */
    static PreviewNote noMatchingRows(String input, AggregateSqlValidator.Analysis sql) {
        if (sql.plan().translation().query().filter() == null) {
            return noMatchingRows(input, (String) null);
        }
        return PreviewNote.of(PreviewNoteCode.NO_MATCHING_ROWS, "no input row matches the WHERE of this pipeline's "
                + "`sql`, so the declaration produces no group; check the WHERE against the input");
    }

    /**
     * Runs the preview's sample under its resource budget — the sample only, never the probe.
     *
     * <p>The budget exists because the sample is deliberately unwindowed: it aggregates the whole input on
     * every call, and {@code aggregate.preview-rows} bounds what comes back rather than what it costs to
     * produce. Preview sits between declaring a rollup and letting it write, so it is exactly the endpoint
     * an operator is expected to call on a declaration nobody has vetted — and a declaration that is
     * refused the moment it runs must not be able to take the data plane down on the way to being refused.
     * A {@code group by "dial_usage_log_payload.request_body"} preview over a modest input is enough to do it.
     */
    private List<Map<String, Object>> runSample(String name, SelectQuery<Record> sample, QuerySettings settings,
            QueryEntityModel model) {
        try {
            new StatementTag(name, "preview").applyTo(settings);
            return executor.executeScan(sample, settings, model);
        } catch (RuntimeException ex) {
            throw PreviewBudget.translate(ex, properties.preview());
        }
    }

    /**
     * Re-runs the full governance gate against the identity this operation is executing as, returning what it
     * resolved. For an SQL declaration that includes the input the statement reads, which a disabled draft saved
     * before its FROM table existed does not store.
     */
    private Gated gate(PipelineEntity pipeline, AggregateSpec spec, String operation) {
        final AggregateSpecValidator.Validated validated = validator.validate(pipeline.getName(), pipeline.getInputTable(),
                pipeline.getTarget(), pipeline.getFilterSql(), spec, operation);
        if (spec.getSql() == null) {
            return new Gated(pipeline.getInputTable(), null);
        }
        if (validated == null || validated.sql() == null) {
            throw new IllegalStateException("the gate accepted pipeline '" + pipeline.getName()
                    + "''s sql declaration without analysing it");
        }
        // The table the statement reads: the gate refuses a stored input that disagrees with it.
        return new Gated(validated.sql().plan().translation().query().entity(), validated.sql());
    }

    /** The gate's outcome: the input the declaration reads, and for an SQL declaration its analysis (else null). */
    private record Gated(String input, AggregateSqlValidator.Analysis sql) {
    }

    /**
     * The gated declaration bound to the model it compiles against — the one place a refresh and a preview ask which
     * mode it is in. Its filter is translated once, when it is bound: the probe, the clamp's field set and the
     * compiler all read that same filter.
     */
    private Declaration declaration(PipelineEntity pipeline, AggregateSpec spec, Gated gated, QueryEntityModel model) {
        return gated.sql() != null
                ? new SqlDeclaration(gated.sql(), model)
                : new FormDeclaration(pipeline, spec, model);
    }

    /** What a refresh and a preview need of a declaration, whichever mode states it. */
    private sealed interface Declaration permits FormDeclaration, SqlDeclaration {

        /** The membership filter the probe, the dirty-key sub-select and the bucket bound apply. */
        AggregateCompiler.CompiledFilter filter();

        /** Every entity field the widest statement reads — the set the enrichment clamp covers. */
        Set<String> referencedFields();

        AggregateCompiler.Compiled compileRefresh(String target, List<Condition> windowPredicates);

        SelectQuery<Record> compilePreview(CursorColumns cursorColumns, int limit);

        /** The declaration as a structured query over {@code input}, for the caller to run through the query API. */
        StructuredQuery portableQuery(String input, int limit);

        /** The sample's column aliases that are not their target column's name, mapped to that name. */
        Map<String, String> sampleAliases();

        List<PreviewColumn> previewColumns(String input, String target);

        PreviewNote noMatchingRows(String input);

        Set<String> valuePreservingMeasures();
    }

    /** A form declaration: {@code group_by} and {@code measures}, with the pipeline's {@code filter} and the measures' own. */
    private final class FormDeclaration implements Declaration {

        private final PipelineEntity pipeline;
        private final AggregateSpec spec;
        private final QueryEntityModel model;
        private final AggregateCompiler.CompiledFilter filter;
        private final Map<String, AggregateCompiler.CompiledFilter> measureFilters;

        private FormDeclaration(PipelineEntity pipeline, AggregateSpec spec, QueryEntityModel model) {
            this.pipeline = pipeline;
            this.spec = spec;
            this.model = model;
            this.filter = translatedFilter(pipeline, model);
            this.measureFilters = translatedMeasureFilters(pipeline, spec, model);
        }

        @Override
        public AggregateCompiler.CompiledFilter filter() {
            return filter;
        }

        @Override
        public Set<String> referencedFields() {
            return AggregateCompiler.referencedFields(spec, filter, measureFilters);
        }

        @Override
        public AggregateCompiler.Compiled compileRefresh(String target, List<Condition> windowPredicates) {
            return compiler.compileRefresh(spec, model, target, filter, measureFilters, windowPredicates);
        }

        @Override
        public SelectQuery<Record> compilePreview(CursorColumns cursorColumns, int limit) {
            return compiler.compilePreview(spec, model, cursorColumns, filter, measureFilters, limit);
        }

        @Override
        public StructuredQuery portableQuery(String input, int limit) {
            return compiler.portablePreview(spec, input, filter, measureFilters, limit);
        }

        @Override
        public Map<String, String> sampleAliases() {
            return Map.of();
        }

        @Override
        public List<PreviewColumn> previewColumns(String input, String target) {
            return validator.previewColumns(input, target, spec);
        }

        @Override
        public PreviewNote noMatchingRows(String input) {
            return AggregateRefreshService.noMatchingRows(pipeline);
        }

        @Override
        public Set<String> valuePreservingMeasures() {
            return AggregateSpecValidator.valuePreservingMeasures(spec);
        }
    }

    /**
     * An SQL declaration: the analysed single block. Its filter is the block's {@code WHERE}, and its measure
     * predicates live inside its measures, so it has no separate measure filters.
     */
    private final class SqlDeclaration implements Declaration {

        private final AggregateSqlValidator.Analysis analysis;
        private final QueryEntityModel model;
        private final AggregateCompiler.CompiledFilter filter;

        private SqlDeclaration(AggregateSqlValidator.Analysis analysis, QueryEntityModel model) {
            this.analysis = analysis;
            this.model = model;
            this.filter = sqlCompiler.where(analysis, model);
        }

        @Override
        public AggregateCompiler.CompiledFilter filter() {
            return filter;
        }

        @Override
        public Set<String> referencedFields() {
            return AggregateSqlCompiler.referencedFields(analysis);
        }

        @Override
        public AggregateCompiler.Compiled compileRefresh(String target, List<Condition> windowPredicates) {
            return sqlCompiler.compileRefresh(analysis, model, target, filter, windowPredicates);
        }

        @Override
        public SelectQuery<Record> compilePreview(CursorColumns cursorColumns, int limit) {
            return sqlCompiler.compilePreview(analysis, model, cursorColumns, limit);
        }

        @Override
        public StructuredQuery portableQuery(String input, int limit) {
            return sqlCompiler.portablePreview(analysis, limit);
        }

        @Override
        public Map<String, String> sampleAliases() {
            return sqlCompiler.sampleAliases(analysis, model);
        }

        @Override
        public List<PreviewColumn> previewColumns(String input, String target) {
            return validator.previewColumns(input, target, analysis);
        }

        @Override
        public PreviewNote noMatchingRows(String input) {
            return AggregateRefreshService.noMatchingRows(input, analysis);
        }

        @Override
        public Set<String> valuePreservingMeasures() {
            return AggregateSqlValidator.valuePreservingMeasures(analysis);
        }
    }

    /**
     * The predicates bounding one window: the safety lag and the keyset seek past the stored cursor. They
     * restrict the dirty-key sub-select — never the recompute half, which must see every row of an affected
     * key.
     *
     * <p>The membership filter is deliberately <em>not</em> included: the compiler applies it to both halves
     * of the write itself, so adding it here too would emit it twice in the same sub-select. The probe adds
     * it separately, because a probe that ignored the filter would pick a window end from rows the pipeline
     * does not even claim.
     */
    private List<Condition> windowPredicates(PipelineEntity pipeline, CursorColumns cursorColumns) {
        final List<Condition> conditions = new ArrayList<>();
        conditions.add(CursorPredicates.safetyLag(cursorColumns, properties.safetyLag()));
        if (pipeline.hasCursor()) {
            conditions.add(CursorPredicates.seekPast(
                    cursorColumns, pipeline.getCursorVersion(), pipeline.getCursorIdentity()));
        }
        return conditions;
    }

    /**
     * The window probe: the same predicates the write applies, projecting only the cursor tuple and
     * ordered by it. The last row's tuple is the window end; a probe that filled its limit means more
     * input remains for the next run.
     *
     * <p><b>Both facts are derived inside ClickHouse</b>, over the ordered-and-limited select as a
     * subquery, so one row comes back however large the window is. The alternative — selecting the window
     * and taking its last row in Java — satisfies the same contract while moving up to
     * {@code aggregate.window-limit} rows across the wire, 100,000 by default, on every tick of every
     * enabled pipeline, to extract one tuple and one boolean. {@code window-limit} bounds how much a
     * refresh <em>recomputes</em>; it is not a budget for how much the service <em>reads</em>, so nothing
     * else caps that transfer.
     *
     * <p>The limit stays <em>inside</em> the subquery because it is what defines the window: the end tuple
     * is the last row of the first N in cursor order, not the greatest row in the input, and the two
     * differ exactly when the window is capped.
     */
    private Window probe(String name, QueryEntityModel model, CursorColumns cursorColumns,
            AggregateCompiler.CompiledFilter filter, List<Condition> windowPredicates) {
        final SelectQuery<Record> probe = renderContext.selectQuery();
        probe.addFrom(model.table());
        // The membership filter is the only thing here that can reach an enrichment — the probe projects
        // the cursor tuple and nothing else — so a declaration that names one only in a measure or a group
        // key leaves the cheapest statement of the refresh unjoined, which is the point of deriving the
        // referenced set per statement.
        EnrichmentJoins.addReferenced(probe, model, filter.columns(),
                AggregateCompiler.keyPredicates(model, filter, windowPredicates));
        final Field<Long> versionExpr = CursorPredicates.versionExpr(cursorColumns);
        final Field<String> identityExpr = CursorPredicates.identityExpr(cursorColumns);
        probe.addSelect(versionExpr.as(CURSOR_VERSION));
        probe.addSelect(identityExpr.as(CURSOR_IDENTITY));
        if (filter.condition() != null) {
            probe.addConditions(filter.condition());
        }
        probe.addConditions(windowPredicates);
        probe.addOrderBy(versionExpr.asc(), identityExpr.asc());
        probe.addLimit(properties.windowLimit());

        final Map<String, Object> row = PipelineReadBudget.run(executor, windowEnd(probe), model,
                "refresh window probe", readBudget, new StatementTag(name, "refresh-probe")).getFirst();
        // An aggregate over an empty subquery still returns one row — carrying count 0 and a null tuple —
        // so "nothing new" is the count, never an absent row. Testing for an empty result here would make
        // the no-op unreachable and pin the window to a null end tuple.
        final long matched = ((Number) row.get(WINDOW_COUNT)).longValue();
        if (matched == 0) {
            return null;
        }
        return new Window(
                ((Number) row.get(START_VERSION)).longValue(),
                String.valueOf(row.get(START_IDENTITY)),
                ((Number) row.get(CURSOR_VERSION)).longValue(),
                String.valueOf(row.get(CURSOR_IDENTITY)),
                matched == properties.windowLimit());
    }

    /**
     * Collapses the probed window to one row: how many rows it holds, and its start and end cursor tuples.
     *
     * <p>Every half of both tuples is taken with {@code argMax}/{@code argMin} over <b>the tuple</b>, so
     * each is read from the row that extremises the ordering the window is defined by — the same row, by
     * construction. The trap this avoids is {@code max(version)} alongside {@code max(identity)}:
     * independent maxima come from different rows whenever the greatest version is not also the greatest
     * identity, yielding a window end that no row ever had. Nothing fails; the next run simply reads a
     * different range than it should, and the cursor is a plausible-looking value with no way to tell it
     * apart from a correct one.
     *
     * <p>The start tuple is here rather than in a second query because the enrichment clamp needs to
     * distinguish trimming the window from withholding it, and the aggregate that already runs can answer
     * that for the cost of two more expressions over the same subquery.
     */
    private SelectQuery<Record> windowEnd(SelectQuery<Record> window) {
        final Table<Record> probed = window.asTable("probed");
        final Field<Long> version = probed.field(CURSOR_VERSION, Long.class);
        final Field<String> identity = probed.field(CURSOR_IDENTITY, String.class);
        final SelectQuery<Record> collapsed = renderContext.selectQuery();
        collapsed.addFrom(probed);
        collapsed.addSelect(DSL.count().as(WINDOW_COUNT));
        collapsed.addSelect(
                DSL.field("argMax({0}, ({0}, {1}))", Long.class, version, identity).as(CURSOR_VERSION));
        collapsed.addSelect(
                DSL.field("argMax({1}, ({0}, {1}))", String.class, version, identity).as(CURSOR_IDENTITY));
        collapsed.addSelect(
                DSL.field("argMin({0}, ({0}, {1}))", Long.class, version, identity).as(START_VERSION));
        collapsed.addSelect(
                DSL.field("argMin({1}, ({0}, {1}))", String.class, version, identity).as(START_IDENTITY));
        return collapsed;
    }

    private AggregateCompiler.CompiledFilter translatedFilter(PipelineEntity pipeline, QueryEntityModel model) {
        // analyze() reports through the enrichment capability's exception; on a /v1/pipelines call that
        // would answer with the wrong stable error code, so it is re-attributed here.
        return translatedPredicate(pipeline.getInputTable(), pipeline.getFilterSql(), model);
    }

    /**
     * Each measure's own {@code where}, translated and keyed by measure name; measures with none are absent
     * from the map rather than mapped to null, so {@code Map.get} answering null means "no predicate".
     *
     * <p>Translated here, next to the pipeline filter, rather than inside {@link AggregateCompiler}: the
     * compiler is handed conditions it renders and holds no validator, which is what keeps SQL out of its
     * inputs. Both refresh and preview go through this, so the two cannot disagree about what a measure
     * predicate means.
     */
    private Map<String, AggregateCompiler.CompiledFilter> translatedMeasureFilters(
            PipelineEntity pipeline, AggregateSpec spec, QueryEntityModel model) {
        final Map<String, AggregateCompiler.CompiledFilter> byMeasure = new LinkedHashMap<>();
        for (final Measure measure : spec.getMeasures()) {
            if (StringUtils.isBlank(measure.getWhere())) {
                continue;
            }
            final AggregateCompiler.CompiledFilter condition =
                    translatedPredicate(pipeline.getInputTable(), measure.getWhere(), model);
            if (condition.condition() != null) {
                byMeasure.put(measure.getName(), condition);
            }
        }
        return byMeasure;
    }

    /**
     * One predicate, translated, carrying the field names it reads.
     *
     * <p>The names are kept rather than discarded with the parse: the compiler renders a {@link Condition}
     * and cannot recover what it reads from it, but a statement carrying the predicate has to join whatever
     * enrichment the predicate reaches through. This is the only place both are in hand at once.
     */
    private AggregateCompiler.CompiledFilter translatedPredicate(
            String inputTable, String sql, QueryEntityModel model) {
        final FilterSqlValidator.FilterAnalysis analysis =
                PipelineFilters.analyze(filterValidator, sensitiveGuard, inputTable, sql);
        if (analysis.filter() == null) {
            return AggregateCompiler.CompiledFilter.NONE;
        }
        return new AggregateCompiler.CompiledFilter(
                filterTranslator.toCondition(analysis.filter(), model.bindings()),
                Set.copyOf(analysis.columns()), analysis.filter());
    }

    private PipelineEntity require(String name) {
        final PipelineEntity pipeline = pipelines.findById(name)
                .orElseThrow(() -> new PipelineNotFoundException("pipeline '" + name + "' not found"));
        requireAggregate(pipeline);
        return pipeline;
    }

    /**
     * The third of the three guards {@link PipelineRepository#findDue} names against an ENRICH pipeline
     * reaching the aggregate machinery — and the only one that can be reached by a caller rather than by a
     * scheduling mistake, because {@code preview} and {@code refresh} take a name straight from the path.
     *
     * <p>Asserted here, in the shared loader, rather than in the two entry points: every path into this
     * service resolves its pipeline through {@link #require}, so a future entry point inherits the guard
     * instead of having to remember it.
     *
     * <p>Until this existed the refusal came from {@code PipelineSpecCodec.readAggregate}, whose
     * {@code IllegalStateException} is an internal invariant assertion and surfaced as
     * {@code 500 internal_error} — the fold put both kinds on one path, so an operator previewing an
     * enrich pipeline by mistake got a server error rather than being told what the endpoint serves.
     * 422 for the same reason {@code PipelineService.requireEnrich} answers 422 in the other direction:
     * the pipeline exists, and saying so is more useful than pretending it does not.
     */
    private static void requireAggregate(PipelineEntity pipeline) {
        if (pipeline.getKind() != PipelineKind.AGGREGATE) {
            throw new PipelineValidationException("pipeline '" + pipeline.getName() + "' is of kind '"
                    + pipeline.getKind().code() + "'; the aggregate refresh machinery serves the '"
                    + PipelineKind.AGGREGATE.code() + "' kind — an enrich pipeline is driven by the "
                    + "Enrichment Runner through scan and materialize");
        }
    }

    /**
     * A disabled pipeline may not write. Registration lands disabled precisely so the dry-run preview sits
     * between declaring a rollup and letting it write; letting the on-demand endpoint write anyway would
     * make {@code enabled} describe only the schedule while the gate it exists to enforce stayed open.
     * {@link #preview} remains available while disabled — that is its whole purpose.
     */
    private void requireEnabled(PipelineEntity pipeline) {
        if (!pipeline.isEnabled()) {
            throw new PipelineConflictException("pipeline '" + pipeline.getName() + "' is disabled and cannot "
                    + "write; preview it with POST /v1/pipelines/" + pipeline.getName() + "/preview, then "
                    + "enable it before refreshing");
        }
    }

}
