package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.clickhouse.ClickHouseBudget;
import com.epam.aidial.analytics.clickhouse.ClickHouseInsertSelectExecutor;
import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.configuration.MaterializeProperties;
import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.configuration.ResourceBudget;
import com.epam.aidial.analytics.configuration.ScanProperties;
import com.epam.aidial.analytics.exception.PipelineReadBudgetExceededException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.TableRelayoutInProgressException;
import com.epam.aidial.analytics.observability.SqlLog;
import com.epam.aidial.analytics.observability.trace.LogExecution;
import com.epam.aidial.analytics.service.enrichment.model.EnrichDeclaration;
import com.epam.aidial.analytics.service.enrichment.model.MaterializePage;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TransformVar;
import com.epam.aidial.analytics.service.enrichment.support.CursorCodec;
import com.epam.aidial.analytics.service.enrichment.support.CursorColumns;
import com.epam.aidial.analytics.service.enrichment.support.CursorPredicates;
import com.epam.aidial.analytics.service.enrichment.support.PartitionEcho;
import com.epam.aidial.analytics.service.enrichment.support.PipelineAuthorizer;
import com.epam.aidial.analytics.service.enrichment.support.ProvenanceColumns;
import com.epam.aidial.analytics.service.enrichment.support.ScanSourceMetadata;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichDeclarationCompleteness;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichSpecValidator;
import com.epam.aidial.analytics.service.enrichment.validation.ExprSqlValidator;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;
import com.epam.aidial.analytics.service.pipeline.EnrichmentFreshness;
import com.epam.aidial.analytics.service.pipeline.PipelineReadBudget;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumn;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumnRole;
import com.epam.aidial.analytics.service.pipeline.preview.SampledPosition;
import com.epam.aidial.analytics.service.query.EnrichmentJoins;
import com.epam.aidial.analytics.service.query.ObjectPathUsage;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.EnrichmentAlignment;
import com.epam.aidial.analytics.service.table.TableDataService;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.relayout.RelayoutStateStore;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The Class-1 data plane: computes a {@code type=sql} pipeline's derived values <b>set-based inside
 * ClickHouse</b> over one bounded window, and reports how many rows it wrote. No row payload crosses the
 * wire in either direction, which is both the performance argument (one statement instead of a row loop)
 * and the privacy argument (a sensitive body never leaves the database).
 *
 * <p><b>Probe, then write.</b> The call runs two statements. The first is a two-column keyset probe that
 * picks the window's end tuple — cheap unless the pipeline's own filter names an enrichment, which makes
 * the probe join it too; the second is a single {@code INSERT … SELECT} bounded by
 * exactly that window. Choosing the window <em>before</em> executing it is what makes the effect
 * exactly-once: a call that times out, fails, or is retried re-executes an identical
 * {@code (cursorFrom, windowEnd]} range, a single ClickHouse {@code INSERT} is atomic, and the target
 * enrichment is a {@code ReplacingMergeTree} collapsing by grain — so a repeated window is a no-op in
 * effect. Deriving the cursor from what was written instead would need a third query over a window that
 * may already have moved.
 *
 * <p>The cursor is caller-held, exactly as on the scan path: ADAS signs and decodes it but keeps no
 * per-caller position, so the caller advances only on a successful response.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class MaterializeService {

    private static final String CURSOR_VERSION = "__cursor_v";
    private static final String CURSOR_IDENTITY = "__cursor_id";

    /**
     * Provenance a Class-1 pipeline has nothing to say about: a {@code sql} transform makes no model call,
     * and governance forbids it the group trigger, so neither group column can describe its input.
     */
    private static final List<String> NOT_APPLICABLE_TO_SQL = List.of(
            ProvenanceColumns.MODEL, ProvenanceColumns.GROUP_VERSION, ProvenanceColumns.TRUNCATED);

    private final PipelineService pipelines;
    private final PipelineAuthorizer authorizer;
    private final ScanSourceMetadata scanSources;
    private final SensitiveProjectionGuard sensitiveGuard;
    private final FilterSqlValidator filterValidator;
    private final ExprSqlValidator exprValidator;
    private final FilterTranslator filterTranslator;
    private final ExprTranslator exprTranslator;
    private final ColumnMappingRepository columns;
    private final TableDefinitionRepository definitions;
    private final RelayoutStateStore relayouts;
    private final TableDataService tableData;
    private final DSLContext renderContext;
    private final StructuredQueryExecutor executor;
    private final ClickHouseInsertSelectExecutor insertSelect;
    private final CursorCodec codec;
    private final ScanProperties scanProperties;
    private final PipelineBudgetProperties readBudget;
    private final MaterializeProperties materializeProperties;
    private final MaterializePositionWriter positions;
    private final EnrichmentFreshness freshness;
    private final EnrichSpecValidator validator;
    private final MaterializeOutcomeLog outcomes;

    /**
     * The probed window's end tuple, how many source rows it covers, and whether the probe filled its limit.
     *
     * <p>{@code rows} exists so narrowing halves the <b>window</b> rather than the limit. A probe that
     * matched 300 rows under a limit of 10000 returns the same 300 rows — and the same end tuple — at every
     * limit down to 512, so seeding the retry from the limit would pay for six refused full-cost writes
     * before the window actually moved.
     */
    record Window(long version, String identity, int rows, boolean hasMore) {}

    /**
     * Everything that bounds one window: the source model, the pipeline's translated filter, the source's
     * cursor columns, the caller's decoded cursor, and the optional {@code (from, to]} bounds. The probe
     * and the write take the <em>same</em> instance, which is what makes the written range identical to
     * the probed one — the property the exactly-once effect rests on.
     */
    private record WindowSpec(
            QueryEntityModel model,
            FilterNode filter,
            CursorColumns cursorColumns,
            List<String> cursorValues,
            Instant from,
            Instant to,
            Set<String> referenced,
            Set<String> filterColumns,
            EnrichmentFreshness.Bound enrichmentBound) {

        /**
         * This spec rendering against {@code effective} — the model {@code EnrichmentJoins} returned once it
         * knew which {@code object} columns it had pruned. Every clause translated <em>after</em> the join
         * is emitted must use it, or it addresses a column the derived table no longer projects.
         */
        WindowSpec withModel(QueryEntityModel effective) {
            return new WindowSpec(effective, filter, cursorColumns, cursorValues, from, to, referenced,
                    filterColumns, enrichmentBound);
        }
    }

    /**
     * The {@code object}-column paths this statement addresses, for {@link EnrichmentJoins} to prune its
     * derived table down to.
     *
     * <p>Collected from exactly the clauses the statement renders — its filter, plus the output expressions
     * when it has them — because a path the collection misses is a column the join would prune away and the
     * rendering would still ask for. The probe passes no expressions: it projects only the cursor tuple, so
     * its filter is the whole of what can name an enrichment.
     */
    private ObjectPathUsage objectPaths(
            WindowSpec spec, Map<String, ExprSqlValidator.ExprAnalysis> expressions) {
        Map<String, QueryFieldBinding> bindings = spec.model().bindings();
        ObjectPathUsage.Builder into = ObjectPathUsage.builder();
        exprTranslator.collectObjectPaths(spec.filter(), bindings, into);
        if (expressions != null) {
            expressions.values().forEach(a -> exprTranslator.collectObjectPaths(a.expr(), bindings, into));
        }
        return into.build();
    }

    public MaterializePage materialize(String pipelineName, String cursor, Instant from, Instant to, Integer limit) {
        ResolvedEnrichPipeline resolved = pipelines.getEnrich(pipelineName);            // 404 unknown_pipeline
        EnrichDeclaration declaration = resolved.declaration();
        authorizer.authorize(resolved.source(), declaration.target());   // 403 pipeline_access_denied (bypassed in none mode)

        // First of the declaration questions: a disabled pipeline is refused whatever its declaration holds.
        PipelineService.requireEnabledToWrite(resolved.pipeline(), "materialize");   // 409 pipeline_conflict
        relayouts.requireNotHeld(declaration.target());   // 503 table_relayout_in_progress, before the probe
        // Only an enabled pipeline gets here, and arming ran the gate — but a stored row can still drift from
        // it. Refused before any statement runs: one missing a member this path compiles from, and one whose
        // stored output mapping no longer describes its transform — writing through that would put a newly authored expression into a
        // column the previous transform bound.
        EnrichSpec.TransformSpec transform = EnrichDeclarationCompleteness.requireRunnable(resolved, "materialize",
                declared -> requireSql(declared, pipelineName, "materialize is the Class-1 (sql) path — use the scan/write-back path instead"));
        EnrichDeclarationCompleteness.requireCurrentOutputMapping(declaration, "materialize");
        String source = requireNotBlank(resolved.source(), "pipeline '" + pipelineName + "' target has no resolvable source");
        String grainKey = requireNotBlank(resolved.grainKey(), "pipeline '" + pipelineName + "' target has no grain key");
        String target = declaration.target();
        // Read once, up front: every mark written below is qualified by the generation the pipeline had when
        // this call started, so a mutation racing this materialize invalidates the mark it produces rather
        // than being recorded as if it had applied to the new definition.
        long generation = declaration.generation();

        CursorColumns cursorColumns = scanSources.cursorColumns(source);
        int effectiveLimit = resolveLimit(limit);
        CursorPredicates.requireOrderedWindow(from, to);
        List<String> cursorValues = StringUtils.isBlank(cursor)
                ? null
                : codec.decode(cursor, source).values();      // 400 invalid_cursor; verifies entity + HMAC

        QueryEntityModel model = scanSources.entityModel(source);
        // A non-entitled caller cannot even parse an expression over a sensitive column, because those
        // columns are hidden from its schema view; re-attribute that to the 403 it really is.
        FilterSqlValidator.FilterAnalysis filter = sensitiveGuard.reattributing(source, "materialize",
                () -> filterValidator.analyze(source, declaration.filterSql(), "filter", true), true);
        Map<String, ExprSqlValidator.ExprAnalysis> analysed = sensitiveGuard.reattributing(
                source, "materialize", () -> analyzeExpressions(declaration, transform, source), true);
        // The two gate rules whose absence writes a silently wrong row — result-type fit and row grain —
        // re-checked here and nothing else of the gate, so an enabled pipeline whose stored row drifted after
        // it was armed keeps running for drift that writes no wrong value.
        validator.recheckClass1Soundness(declaration, source, grainKey, cursorColumns, analysed);
        Map<String, ExprSqlValidator.ExprAnalysis> expressions = withAlignment(analysed, target, source);

        // Re-apply the sensitive-column rule against the *calling* identity, as the scan does at its
        // egress. No row is returned here, but the caller still causes sensitive data to be read and
        // derived, so a least-privilege caller must not be able to trigger it — which is exactly why a
        // body-extracting Class-1 pipeline needs a runner holding TABLE_READ and TABLE_SENSITIVE on the source.
        Set<String> referenced = new LinkedHashSet<>(filter.columns());
        expressions.values().forEach(analysis -> referenced.addAll(analysis.columns()));
        referenced.add(grainKey);
        sensitiveGuard.enforceEntity(source, referenced, "materialize");

        // A windowed call is a backfill: it rewrites values for source rows a consumer may already have
        // read. That is recorded before anything runs, because the fact being recorded is that history is
        // *being* re-derived — a rollup reading this enrichment is invalidated by the attempt, not only by
        // its success, and a partial rewrite invalidates it just as thoroughly as a complete one.
        boolean rederivingHistory = from != null || to != null;
        if (rederivingHistory) {
            recording(pipelineName, "history re-derivation", () -> positions.recordHistoryRederived(pipelineName));
        }

        // The freshness clamp, and Class-1 is the strict case for it. This path derives one value per
        // source row and records the position it wrote through, so a row read while a joined enrichment's
        // cell is still absent is stored with a value derived from that absence and never revisited — a
        // permanently wrong value rather than a missing one. The joined set comes from the same referenced
        // fields the statements emit joins for, so the clamp covers exactly what the SQL reads.
        Set<String> joined = EnrichmentJoins.referencedEnrichments(model, referenced);
        Optional<EnrichmentFreshness.Bound> enrichmentBound = freshness.bound(joined, source);
        if (enrichmentBound.map(EnrichmentFreshness.Bound::withholdsEverything).orElse(false)) {
            // A joined enrichment has recorded no usable position, so nothing is safely derivable yet.
            // Reported as an empty window rather than a failure: the rows are late, not absent, and a
            // caller that advances only on success never skips them.
            outcomes.outcome(MaterializeOutcomeLog.Report.empty(pipelineName, generation,
                    MaterializeOutcomeLog.Outcome.WITHHELD, null, enrichmentBound.get()));
            return new MaterializePage(0L, cursor, false, null);
        }

        WindowSpec spec = new WindowSpec(model, filter.filter(), cursorColumns, cursorValues, from, to,
                referenced, filter.columns(), enrichmentBound.orElse(null));
        // Stamped BEFORE the probe, not after. The drained horizon means "nothing matching below this
        // instant minus the safety lag", and the instant it is really making that claim about is the one
        // the probe evaluated `now64(3)` at. Reading the clock afterwards would move the horizon forward by
        // however long the probe took, releasing a sliver of rows the probe never looked inside — rows that
        // sat in the still-mutating window at probe time and may yet be enriched. On a slow probe over a
        // large source that sliver is seconds wide, and a rollup reading past it aggregates an absent value.
        Instant probedAt = Instant.now();
        // The call's one clock starts here, before the first statement: the probe's time counts against the
        // same pipeline.budget.timeout the clamp re-probe, the narrowing re-probes and the writes then run
        // on, so the call as a whole — not each statement — is what the runner's read timeout has to cover.
        MaterializeCall call = MaterializeCall.start();
        boolean completed = false;
        try {
            // The first statement runs on the whole budget: nothing has been spent yet.
            Window window = call.time(MaterializeCall.Stage.PROBE, () -> probeWindow(
                    spec, effectiveLimit, readBudget, new StatementTag(pipelineName, "probe")));
            if (window == null) {
                // Nothing to do: write nothing, echo the caller's cursor so it never skips a window.
                //
                // Record that the pipeline is DRAINED as of now, and deliberately not a position. The cursor echoed
                // above is the *caller's*, so persisting it would store what the runner asserted rather than
                // what ADAS observed. The drained instant is a different fact and the one consumers need: the
                // probe applies the pipeline's filter, so a filtered pipeline's position stops at its last matching row
                // while non-matching rows sit above it forever — without this, every rollup reading such an
                // enrichment would wait for rows nothing is ever going to enrich. A windowed call records
                // nothing here: "nothing left in this backfill window" says nothing about the pipeline's tail.
                boolean clampedEmpty = !rederivingHistory && clampHeldRowsBack(spec, pipelineName, call);
                if (!rederivingHistory && !clampedEmpty) {
                    recording(pipelineName, "drained horizon",
                            () -> positions.recordDrained(pipelineName, generation, probedAt));
                }
                outcomes.outcome(MaterializeOutcomeLog.Report.empty(pipelineName, generation,
                        clampedEmpty ? MaterializeOutcomeLog.Outcome.CLAMPED_EMPTY : MaterializeOutcomeLog.Outcome.DRAINED,
                        call, spec.enrichmentBound()));
                completed = true;
                return new MaterializePage(0L, cursor, false, null);
            }
            // The write carries the same ceiling as every read, and a refusal narrows rather than fails: halve
            // the window and re-probe for the new end tuple, down to a floor of one source row. A legitimately
            // large window genuinely can exceed any ceiling, and the caller cannot narrow it — ADAS picks the
            // window — so without this, bounding the write would turn a large backfill into a dead end. The
            // narrowed window is a PREFIX of the probed one, so the identical-window retry guarantee and the
            // cursor ordering both survive; `hasMore` is what tells the caller to come back for the rest.
            Narrowed written = writeNarrowing(
                    spec, window, effectiveLimit, source, target, grainKey, declaration, expressions, call);
            long rowsWritten = written.rows();
            // The window actually written, which is the probed one unless the budget forced a narrower prefix.
            // Everything downstream — the recorded position, the returned cursor, the lag — describes what was
            // written, never what was probed, or a narrowed call would hand the caller a cursor past rows it
            // never wrote.
            Window covered = written.window();
            boolean hasMore = covered.hasMore() || written.narrowed();
            // Only now, and only on this branch: the INSERT … SELECT has returned, so every row of the probed
            // window is in ClickHouse and this tuple is the position the enrichment is complete through. A
            // windowed call still records nothing — its position is inside history, not at the pipeline's tail.
            if (!rederivingHistory) {
                // Decided from the pipeline as it was read at the start of this call — before its own record is
                // applied — which is what makes "did this window carry the pipeline forward?" answerable at all. It
                // is also why the question is asked here rather than inside the writer: loading a PipelineEntity
                // inside that transaction would flush a full-row UPDATE over the columns it is setting.
                boolean reDerived = freshness.reDerivesHistory(resolved.pipeline(), covered.version(), covered.identity());
                recording(pipelineName, "materialized position", () -> positions.recordMaterialized(
                        pipelineName, generation, covered.version(), covered.identity(), reDerived));
            }
            String nextCursor = codec.encode(
                    source, List.of(Long.toString(covered.version()), covered.identity()));
            long lagSeconds = Math.max(0L, (System.currentTimeMillis() - covered.version()) / 1000L);
            outcomes.outcome(new MaterializeOutcomeLog.Report(pipelineName, generation,
                    MaterializeOutcomeLog.Outcome.WRITTEN, rowsWritten, written.narrowed(), hasMore, lagSeconds,
                    call, spec.enrichmentBound()));
            completed = true;
            return new MaterializePage(rowsWritten, nextCursor, hasMore, lagSeconds);
        } finally {
            outcomes.softDeadline(pipelineName, call, readBudget.timeout(), !completed);
        }
    }

    /** What one write actually covered: its rows, the window it ended on, and whether it had to narrow. */
    record Narrowed(long rows, Window window, boolean narrowed) {}

    /**
     * Writes the probed window, halving it and re-probing for as long as the resource budget refuses it.
     *
     * <p>Narrowing is done by <b>re-probing at a smaller limit</b> rather than by arithmetic on the end
     * tuple. The probe is the only thing that knows which source rows actually match, so halving its limit
     * yields a real prefix of the same ordered window, where interpolating a tuple would invent a boundary
     * that may fall inside or outside the matching set. The probe is the cheap statement of the pair, so
     * paying for a second one to avoid a third of the expensive one is the right trade.
     *
     * <p>The floor is one source row. A refusal there is surfaced: at one row there is nothing left to
     * narrow, so the declaration is asking for more than the ceiling allows however it is windowed, and
     * saying so is more useful than crawling. That is also what stops a pathological declaration from
     * hiding as slowness.
     */
    private Narrowed writeNarrowing(
            WindowSpec spec,
            Window probed,
            int limit,
            String source,
            String target,
            String grainKey,
            EnrichDeclaration declaration,
            Map<String, ExprSqlValidator.ExprAnalysis> expressions,
            MaterializeCall call) {
        // On the call's clock, not one started here: the probe that sized this window has already spent part
        // of the one budget the whole call runs under.
        return narrowing(
                probed,
                limit,
                declaration.name(),
                readBudget,
                call::elapsed,
                (attempt, budget) -> call.time(MaterializeCall.Stage.REPROBE, () -> probeWindow(
                        spec, attempt, budget, new StatementTag(declaration.name(), "reprobe"))),
                (window, budget) -> call.time(MaterializeCall.Stage.WRITE, () -> writeWindow(
                        spec, window, source, target, grainKey, declaration, expressions, budget)));
    }

    /** Re-probes the window at a smaller limit under {@code budget}. Returns null when nothing matches any more. */
    @FunctionalInterface
    interface WindowProbe {
        Window probe(int limit, ResourceBudget budget);
    }

    /** Writes one probed window under {@code budget}, or throws {@link PipelineReadBudgetExceededException}. */
    @FunctionalInterface
    interface WindowWrite {
        long write(Window window, ResourceBudget budget);
    }

    /**
     * The narrowing policy itself, separated from the statements it drives so it can be exercised without
     * a ClickHouse: halve, re-probe, retry; surface the refusal at a floor of one row.
     *
     * <p>Package-private and static rather than inlined above because this is the part with the decisions
     * in it — the halving sequence, the floor, and which outcomes count as narrowed — and every one of
     * those is a behaviour a caller depends on through {@code hasMore}.
     */
    static Narrowed narrowing(
            Window probed,
            int limit,
            String pipelineName,
            ResourceBudget budget,
            Supplier<Duration> elapsed,
            WindowProbe probe,
            WindowWrite write) {
        Window window = probed;
        // Seeded from what the probe actually matched, not from the limit it was allowed: halving a limit
        // the window never reached re-probes the identical window and pays for the same refused write again.
        int attempt = Math.max(1, Math.min(limit, probed.rows()));
        boolean narrowed = false;
        while (true) {
            // The ceiling bounds the CALL, not the attempt. Every retry runs on what is left of it, so a
            // narrowing sequence cannot outlast the caller's own read timeout and finish into a closed
            // socket — which the caller reads as a transport failure and retries, forever.
            ResourceBudget remaining = ClickHouseBudget.remaining(budget, elapsed.get());
            if (remaining == null) {
                // Two unrelated failures reach this exit and the message must not describe the other one.
                // With no narrowed attempt behind it, nothing has been written and nothing is known about
                // what window would have fitted — so claiming the window "is smaller than one this call can
                // find" would assert a conclusion only narrowing could reach, and sends the reader looking
                // for a narrowing bug that never ran. A refusal that DID narrow is the other case, and it
                // arrives here when the re-probe spends what the write left.
                throw new PipelineReadBudgetExceededException(narrowed
                        ? "this materialize write exhausted the " + budget.timeout() + " execution budget "
                        + "(pipeline.budget.*) across narrowing retries; the window it can finish in is "
                        + "smaller than one this call can find, so reduce what the declaration reads or "
                        + "have the budget raised"
                        : "this materialize write had none of the " + budget.timeout() + " execution budget "
                        + "(pipeline.budget.*) left to run in once its window had been probed, so no write "
                        + "was attempted; raise the budget, or reduce what the declaration reads so that "
                        + "probing it costs less", null);
            }
            try {
                return new Narrowed(write.write(window, remaining), window, narrowed);
            } catch (PipelineReadBudgetExceededException ex) {
                if (attempt <= 1) {
                    throw ex;
                }
                ResourceBudget left = ClickHouseBudget.remaining(budget, elapsed.get());
                if (left == null) {
                    // Nothing left to write a narrowed window in, so the probe that would size one is a
                    // statement spent for a result that can never be used — against a node that just
                    // refused us. Surface the refusal we already have, which names the real cause, rather
                    // than the exhaustion message one more round trip would produce.
                    throw ex;
                }
                attempt = Math.max(1, attempt / 2);
                // The re-probe runs on what is left too, not on a fresh budget of its own.
                Window smaller = probe.probe(attempt, left);
                if (smaller == null) {
                    // The window emptied under us — the rows this call was going to write are gone or newly
                    // withheld. Report the refusal rather than an invented success.
                    throw ex;
                }
                log.info("Materialize write for pipeline '{}' refused by the read budget; narrowing to {} row(s)",
                        pipelineName, attempt);
                window = smaller;
                narrowed = true;
            }
        }
    }

    /** Aliases of the Class-1 sample's helper columns carrying each row's cursor tuple; stripped before it is returned. */
    private static final String PREVIEW_VERSION = "__preview_v";

    private static final String PREVIEW_IDENTITY = "__preview_id";

    /**
     * A Class-1 dry run: the declaration as a query for the caller to run, the sample's typed columns and rows, with
     * nothing written and no position recorded — and the freshness bound a materialize would clamp to, computed for
     * the preview's notes only.
     *
     * @param portable the declaration as a structured query over the read source: the grain key and every output
     *               under its target column, the filter, ordered by the grain key — not yet rendered or validated
     *               for the caller, which the preview does
     * @param bound  the bound a materialize of this declaration would clamp to; null when it joins no enrichment
     * @param newest the newest sampled position; null for an empty sample
     */
    public record Class1Preview(StructuredQuery portable, List<PreviewColumn> columns, List<Map<String, Object>> rows,
            EnrichmentFreshness.Bound bound, SampledPosition newest) {}

    /**
     * The Class-1 dry run behind {@code POST /v1/pipelines/{name}/preview}: what this pipeline would
     * compute, without computing it into the target. The caller has already resolved and gated the pipeline:
     * the enrich preview passes the declaration its gate just ran over, because a disabled draft's output
     * mapping is derived in memory by that gate, and a reload would read the stored mapping, which a target
     * schema defined after the draft was saved leaves empty.
     *
     * <p>It lives here, beside the write it describes, because it shares {@link #analyzeExpressions} and
     * {@link #windowPredicates} with it <b>as code</b>. That is the whole claim a preview makes — that the
     * expressions shown are the expressions that will run, resolved against the same source model and
     * bounded by the same filter and safety lag — and a second implementation of either would be free to
     * drift from the one that writes while still looking right.
     *
     * <p><b>Three deliberate differences from a materialize.</b>
     *
     * <ul>
     *   <li><b>No window is probed and no position is recorded.</b> Nothing is written, so there is no
     *       range to make idempotent and nothing to be complete through; recording a position here would
     *       tell every dependent rollup's freshness clamp that values exist which do not.
     *   <li><b>The freshness clamp is not applied.</b> The clamp exists because a value derived from a
     *       joined enrichment's not-yet-written cell would be <em>stored</em> and never revisited — a
     *       permanently wrong value. A preview stores nothing, so that failure mode is unreachable, while
     *       clamping here would empty the sample for an author whose upstream enrichment happens to be
     *       behind. The bound is still computed, and returned for the notes: rows past it read the
     *       enrichment's columns as unmatched (the column type's default rather than a null — see
     *       {@code EnrichmentJoins}), which the author should be told rather than left to infer.
     *   <li><b>The sample is the newest rows, not the next window.</b> As on the aggregate preview, the
     *       question a sample answers is "are these the right values", and the newest input is what a
     *       just-edited declaration is judged on and what it will process once enabled. An author who wants a
     *       specific stretch of the input passes {@code from}/{@code to}.
     * </ul>
     */
    public Class1Preview preview(ResolvedEnrichPipeline resolved, Instant from, Instant to) {
        String pipelineName = resolved.pipeline().getName();
        EnrichDeclaration declaration = resolved.declaration();
        authorizer.authorize(resolved.source(), declaration.target());   // 403 pipeline_access_denied (bypassed in none mode)

        EnrichSpec.TransformSpec transform = requireSqlTransform(resolved, pipelineName,
                "this is the Class-1 (sql) preview arm");
        String source = requireNotBlank(resolved.source(),
                "pipeline '" + pipelineName + "' target has no resolvable source");
        String grainKey = requireNotBlank(resolved.grainKey(),
                "pipeline '" + pipelineName + "' target has no grain key");

        CursorColumns cursorColumns = scanSources.cursorColumns(source);
        CursorPredicates.requireOrderedWindow(from, to);

        QueryEntityModel model = scanSources.entityModel(source);
        FilterSqlValidator.FilterAnalysis filter = sensitiveGuard.reattributing(source, "preview",
                () -> filterValidator.analyze(source, declaration.filterSql(), "filter", true), true);
        // A binding of the alignment column previews the value it is superseded by; a hidden one the declaration
        // does not bind is not one of its outputs, so the sample does not list it.
        Map<String, ExprSqlValidator.ExprAnalysis> analysed = sensitiveGuard.reattributing(
                source, "preview", () -> analyzeExpressions(declaration, transform, source), true);
        Map<String, ExprSqlValidator.ExprAnalysis> expressions = new LinkedHashMap<>(
                withAlignment(analysed, declaration.target(), source));
        expressions.keySet().retainAll(analysed.keySet());

        // The same egress rule the materialize applies, for the same reason and more directly: here the
        // derived values are returned to the caller, not only written.
        Set<String> referenced = new LinkedHashSet<>(filter.columns());
        expressions.values().forEach(analysis -> referenced.addAll(analysis.columns()));
        referenced.add(grainKey);
        sensitiveGuard.enforceEntity(source, referenced, "preview");
        // The bound a materialize would clamp to, over the same joined set, for the notes only: no statement.
        Optional<EnrichmentFreshness.Bound> bound =
                freshness.bound(EnrichmentJoins.referencedEnrichments(model, referenced), source);

        WindowSpec spec = new WindowSpec(model, filter.filter(), cursorColumns, null, from, to,
                referenced, filter.columns(), null);
        SelectQuery<Record> sample = sampleQuery(spec, grainKey, expressions);
        SqlLog.LOG.debug("Pipeline '{}' preview statement: {}", pipelineName, PipelineReadBudget.render(sample, model, readBudget));
        List<Map<String, Object>> sampled = PipelineReadBudget.run(executor, sample, model,
                "Class-1 preview sample", readBudget, new StatementTag(pipelineName, "preview"));
        SampledPosition newest = SampledPosition.newest(sampled, PREVIEW_VERSION, PREVIEW_IDENTITY);
        List<Map<String, Object>> rows = new ArrayList<>(sampled.size());
        for (Map<String, Object> row : sampled) {
            Map<String, Object> stripped = new LinkedHashMap<>(row);
            stripped.remove(PREVIEW_VERSION);
            stripped.remove(PREVIEW_IDENTITY);
            rows.add(stripped);
        }
        return new Class1Preview(portableQuery(source, grainKey, expressions, filter.filter()), previewColumns(declaration, grainKey, expressions, model.bindings()),
                rows, bound.orElse(null), newest);
    }

    /**
     * The Class-1 declaration as a row query over its read source: the grain key, each output's expression under its
     * target column, and the filter. Nothing of the sample's window survives — its newest-first order, safety lag and
     * {@code from}/{@code to} all read the version column, which the entity does not expose — so it is ordered by the
     * grain key instead, and an enrichment column is named as the entity exposes it, for the query path to join.
     */
    private static StructuredQuery portableQuery(String source, String grainKey,
            Map<String, ExprSqlValidator.ExprAnalysis> expressions, FilterNode filter) {
        List<OutputColumn> select = new ArrayList<>(expressions.size() + 1);
        select.add(new OutputColumn(new FieldExpr(grainKey), null));
        expressions.forEach((column, analysis) -> select.add(new OutputColumn(analysis.expr(), column)));
        return new StructuredQuery(source, filter, QueryMode.ROW, false, select, null, null,
                List.of(new SortItem(grainKey, SortDir.ASC, null)), new OffsetPage(0L, EnrichPreviewService.SAMPLE_ROWS, false));
    }

    /**
     * The sample's columns in row order: the grain key, then each derived value with the type its translated
     * expression yields — the value the registration gate compared against the target column — and the target
     * column's declared type.
     */
    private List<PreviewColumn> previewColumns(EnrichDeclaration declaration, String grainKey,
            Map<String, ExprSqlValidator.ExprAnalysis> expressions, Map<String, QueryFieldBinding> bindings) {
        List<ColumnMappingEntity> targetColumns = columns.findByTableNameOrderByOrdinal(declaration.target());
        QueryFieldBinding grain = bindings.get(grainKey);
        List<PreviewColumn> previewColumns = new ArrayList<>();
        previewColumns.add(new PreviewColumn(grainKey, PreviewColumnRole.GRAIN_KEY,
                grain == null ? QueryFieldType.STRING : grain.type(), null));
        expressions.forEach((column, analysis) -> previewColumns.add(new PreviewColumn(column,
                PreviewColumnRole.OUTPUT, analysis.type(), declaredType(targetColumns, column).orElse(null))));
        return previewColumns;
    }

    /**
     * The sample statement: the grain key and every derived value, over exactly the predicates a
     * materialize would apply to the same window, newest first and capped at
     * {@link EnrichPreviewService#SAMPLE_ROWS} rows. Each row's version is projected under a helper alias for
     * the {@code enrichment_behind} note, and stripped before the row is returned.
     *
     * <p>The cap is the bound that matters here and it is a constant rather than a configured knob: a
     * Class-1 expression may extract from a request or response body, and the row count is the only thing
     * standing between a dry run and a multi-megabyte response. Making it settable would let it be raised
     * into exactly the read the enrich preview exists to avoid.
     *
     * <p><b>The grain joins stay unkeyed here</b>, alone among this service's statements, and deliberately:
     * a preview carries no window and no cursor, so {@link #keyPredicates} would reduce to the safety lag
     * and the key set would be very nearly the whole source. Keying on that adds a set build over every
     * row to restrict nothing — a pessimization, not the pruning it is elsewhere. What bounds this
     * statement is the row cap plus {@code pipeline.budget.*}; a preview over heavy columns is refused
     * rather than served, which is the honest answer for a dry run nobody has vetted.
     */
    private SelectQuery<Record> sampleQuery(
            WindowSpec spec, String grainKey, Map<String, ExprSqlValidator.ExprAnalysis> expressions) {
        Map<String, QueryFieldBinding> bindings = spec.model().bindings();
        SelectQuery<Record> select = renderContext.selectQuery();
        select.addFrom(spec.model().table());
        EnrichmentJoins.addReferenced(select, spec.model(), spec.referenced());
        select.addSelect(ScanSourceMetadata.projected(spec.model(), bindings, grainKey).as(grainKey));
        expressions.forEach((column, analysis) ->
                select.addSelect(exprTranslator.toField(analysis.expr(), bindings).as(column)));
        // Qualified with the source, in the select list, the order and the window alike: this statement's joins
        // are unkeyed, so an enrichment is joined whole, and a column it shares a name with — a user-table
        // enrichment's _updated_at — binds to the source only by ClickHouse's left-table preference. The probe
        // and the write keep their unqualified predicates: their SQL is the runner's contract.
        Field<Long> version = CursorPredicates.qualifiedVersionExpr(spec.cursorColumns());
        Field<String> identity = CursorPredicates.qualifiedIdentityExpr(spec.cursorColumns());
        select.addSelect(version.as(PREVIEW_VERSION));
        select.addSelect(identity.as(PREVIEW_IDENTITY));
        select.addConditions(windowPredicates(spec, true));
        // Newest first: final=1 collapses keys before the sort, and either direction is a full top-N sort on a
        // non-sort-key expression, so the order the author needs costs nothing the ascending one did not.
        select.addOrderBy(version.desc(), identity.desc());
        select.addLimit(EnrichPreviewService.SAMPLE_ROWS);
        return select;
    }

    /**
     * Whether the freshness clamp — rather than the absence of matching rows — is what emptied the probe,
     * in which case the pipeline is <b>not</b> drained and must not record that it is.
     *
     * <p>The drained horizon claims "nothing matching below this instant minus the safety lag", and every
     * downstream clamp reads it as a licence to advance past rows this pipeline was never going to enrich (see
     * {@link EnrichmentFreshness#bound}). Under a clamp that claim can be false in exactly the way this
     * whole mechanism exists to prevent: the probe saw nothing only because rows above the clamp were
     * withheld, and recording the horizon would hand a downstream reader the very unbounded read the clamp
     * denied this one — one level down and unrecoverable in the same way.
     *
     * <p><b>Asked exactly, by re-probing the same window with the clamp lifted</b>, rather than inferred
     * from where the bound sits. The tempting shortcut — treat any bound below {@code now − safetyLag} as
     * having withheld something — is wrong in the common direction: a source whose newest row is hours or
     * days old puts <em>every</em> bound below that instant, so a clamped pipeline would never record a drained
     * horizon again even when it is genuinely caught up. That silently withdraws the mechanism the horizon
     * exists for, which is releasing a <em>filtered</em> pipeline's dependents from waiting on rows nothing will
     * ever enrich. The extra probe is a two-column keyset read limited to one row, on a path that already
     * found its window empty. It runs on what is left of the call's budget, and a call with nothing left is
     * refused rather than recording a horizon it did not establish.
     */
    private boolean clampHeldRowsBack(WindowSpec spec, String pipelineName, MaterializeCall call) {
        EnrichmentFreshness.Bound bound = spec.enrichmentBound();
        if (bound == null) {
            return false;
        }
        if (bound.withholdsEverything()) {
            return true;
        }
        WindowSpec unclamped = new WindowSpec(spec.model(), spec.filter(), spec.cursorColumns(),
                spec.cursorValues(), spec.from(), spec.to(), spec.referenced(), spec.filterColumns(), null);
        ResourceBudget left = remainingFor(call, "clamp re-probe");
        return call.time(MaterializeCall.Stage.REPROBE, () -> probeWindow(
                unclamped, 1, left, new StatementTag(pipelineName, "clamp-probe"))) != null;
    }

    /**
     * What is left of the call's budget for a statement that is not a write, or the refusal when nothing
     * usable is. The write's own exits are in {@link #narrowing}, whose messages distinguish a narrowing
     * sequence from a write that was never attempted.
     */
    private ResourceBudget remainingFor(MaterializeCall call, String what) {
        ResourceBudget left = ClickHouseBudget.remaining(readBudget, call.elapsed());
        if (left == null) {
            throw new PipelineReadBudgetExceededException("this materialize " + what + " had none of the "
                    + readBudget.timeout() + " execution budget (pipeline.budget.*) left to run in once its window "
                    + "had been probed; raise the budget, or reduce what the declaration reads so that probing it "
                    + "costs less", null);
        }
        return left;
    }

    /**
     * Runs one bookkeeping write, never letting it fail the materialize it describes.
     *
     * <p>The rows are already written by the time this is reached, and the caller is about to advance its
     * cursor past a window that really was materialized; answering with an error would make it re-execute
     * that window for the sake of a watermark. A lost record costs one cadence of clamp precision — a
     * dependent rollup holds its window slightly longer than it had to, which is the safe direction.
     *
     * <p>The catch sits here rather than inside {@link MaterializePositionWriter} because those methods
     * open their own transaction: catching inside one that has been marked rollback-only only defers the
     * failure to commit time.
     */
    private void recording(String pipelineName, String what, Runnable record) {
        try {
            record.run();
        } catch (RuntimeException ex) {
            log.warn("Could not record the {} of pipeline '{}': {}. The materialize itself is unaffected; a "
                    + "rollup reading this enrichment holds its window until the next materialize "
                    + "re-establishes the mark", what, pipelineName, ex.getMessage());
            log.debug("Recording the {} of pipeline '{}' failed", what, pipelineName, ex);
        }
    }

    /**
     * The transform of a pipeline this path requires to be Class-1, or a 422 naming what it actually is.
     *
     * <p>Both arms of this service ask the same question and must give the same answer, so it is asked
     * once here — and it costs no lookup at all now, because the transform arrives inside the pipeline's
     * own stored declaration rather than through a resolved evaluator version.
     *
     * <p><b>A missing transform is not one of the answers.</b> It goes to the shared guard, which names
     * the pre-fold write that produced it and the re-declaration that repairs it — the same refusal the
     * scan gives, so the two paths cannot disagree about a type-less declaration the way they did while
     * one of them carried a {@code transform == null} arm the other did not.
     */
    private static EnrichSpec.TransformSpec requireSqlTransform(
            ResolvedEnrichPipeline resolved, String pipelineName, String instead) {
        return requireSql(
                PipelineSpecCodec.requireTransform(pipelineName, resolved.spec(), resolved.pipeline().isEnabled()),
                pipelineName, instead);
    }

    /** The type half of {@link #requireSqlTransform}, over a transform the caller already holds. */
    private static EnrichSpec.TransformSpec requireSql(EnrichSpec.TransformSpec transform, String pipelineName, String instead) {
        if (transform.getType() != TransformType.SQL) {
            throw new PipelineValidationException("pipeline '" + pipelineName + "' declares a "
                    + (transform.getType() == null
                            ? "transform with no type" : transform.getType().name().toLowerCase(Locale.ROOT))
                    + " transform; " + instead);
        }
        return transform;
    }

    /**
     * Validates and translates every bound output expression against the pipeline's source, keyed
     * by the target column it is bound to. Re-validating at execution rather than trusting registration
     * matters because the source's schema can change under a stored pipeline; the checks are the same ones
     * the registration gate applied.
     */
    private Map<String, ExprSqlValidator.ExprAnalysis> analyzeExpressions(
            EnrichDeclaration declaration, EnrichSpec.TransformSpec transform, String source) {
        Map<String, ExprSqlValidator.ExprAnalysis> byColumn = new LinkedHashMap<>();
        for (OutputBinding binding : declaration.outputBindings()) {
            TransformVar var = transform.effectiveOutputVars().stream()
                    .filter(v -> v.getName().equals(binding.getVar()))
                    .findFirst()
                    .orElseThrow(() -> new PipelineValidationException(
                            "outputs entry references unknown transform output '" + binding.getVar() + "'"));
            byColumn.put(binding.getColumn(),
                    exprValidator.analyze(source, var.getSql(), "output variable '" + var.getName() + "'", true));
        }
        if (byColumn.isEmpty()) {
            throw new PipelineValidationException("a sql pipeline must bind at least one output column to materialize");
        }
        return byColumn;
    }

    /**
     * {@code expressions} with the target's alignment column, when it has one, bound to the source row's own
     * partition column — replacing any expression the declaration binds to that column, and added when it binds
     * none (see {@link EnrichmentAlignment}). The table is partitioned by that column and joined on it, so its
     * value is the source row's by construction rather than by declaration: a binding computing anything else
     * would write rows no source row can match. A binding of the column to the source column itself
     * ({@code request_time: request_time}) is therefore replaced by what it already wrote.
     *
     * <p>The alignment is the target's against its <b>own</b> source ({@link PartitionEcho#targetAlignment}), and a
     * pipeline reading any other table into an aligned target is refused rather than written: that table's rows hold
     * no value of the target's source rows, so every row would be written under a value no source row carries — the
     * epoch default when the read source has no such column, another table's timestamp when it has one of the same
     * name — and match nothing. Registration refuses the same declaration; this is the guard for one that drifted.
     *
     * @throws PipelineValidationException (422 pipeline_validation_failed) for a read source other than the target's
     */
    private Map<String, ExprSqlValidator.ExprAnalysis> withAlignment(
            Map<String, ExprSqlValidator.ExprAnalysis> expressions, String target, String source) {
        TableDefinitionEntity enrichment = definitions.findById(target).orElse(null);
        String alignment = PartitionEcho.targetAlignment(definitions, enrichment);
        if (alignment == null) {
            return expressions;
        }
        if (!source.equals(enrichment.getSourceTable())) {
            throw new PipelineValidationException(EnrichSpecValidator.otherReadSourceMessage(
                    target, enrichment.getSourceTable(), alignment, source));
        }
        Map<String, ExprSqlValidator.ExprAnalysis> aligned = new LinkedHashMap<>(expressions);
        aligned.put(alignment, exprValidator.analyze(
                source, "\"" + alignment + "\"", "alignment column '" + alignment + "'", true));
        return aligned;
    }

    /**
     * The window probe: the same predicates the scan applies (filter, safety-lag, optional
     * {@code (from, to]} window, keyset seek), projecting only the cursor tuple and ordered by it. The
     * last row's tuple is the window end; a probe that filled its limit means more remains.
     *
     * @param budget what is left of the call's budget; the first probe passes the whole of it
     * @param tag    names the statement, so each probe and re-probe gets its own query id
     * @return the probed window, or {@code null} when nothing matches
     */
    private Window probeWindow(WindowSpec spec, int limit, ResourceBudget budget, StatementTag tag) {
        SelectQuery<Record> probe = renderContext.selectQuery();
        probe.addFrom(spec.model().table());
        // Each statement joins what its OWN clauses name: a probe whose filter names no enrichment stays
        // unjoined even when the write's expressions join one, so the cheap two-column probe stays cheap.
        // A join it does emit is keyed on the window's source-only predicates (see keyPredicates), and
        // pruned to the paths its filter addresses — a probe that joins for a single-path read of a JSON
        // column has no reason to open every path's stream in every part.
        QueryEntityModel model = EnrichmentJoins.addReferenced(
                probe, spec.model(), spec.filterColumns(), keyPredicates(spec), objectPaths(spec, null));
        WindowSpec rendered = spec.withModel(model);
        Field<Long> versionExpr = CursorPredicates.versionExpr(spec.cursorColumns());
        Field<String> identityExpr = CursorPredicates.identityExpr(spec.cursorColumns());
        probe.addSelect(versionExpr.as(CURSOR_VERSION));
        probe.addSelect(identityExpr.as(CURSOR_IDENTITY));
        probe.addConditions(windowPredicates(rendered));
        probe.addOrderBy(versionExpr.asc(), identityExpr.asc());
        probe.addLimit(limit);

        List<Map<String, Object>> rows = PipelineReadBudget.run(executor, probe, model, "materialize window probe", budget, tag);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> last = rows.getLast();
        return new Window(
                ((Number) last.get(CURSOR_VERSION)).longValue(),
                String.valueOf(last.get(CURSOR_IDENTITY)),
                rows.size(),
                rows.size() == limit);
    }

    /**
     * The set-based write: one {@code INSERT … SELECT} over exactly the probed window. The statement is
     * rendered from the stored pipeline — the caller never supplies SQL — and runs on the writer credential
     * with {@code final=1} so the source half sees only the latest version per identity.
     */
    private long writeWindow(
            WindowSpec spec,
            Window window,
            String source,
            String target,
            String grainKey,
            EnrichDeclaration declaration,
            Map<String, ExprSqlValidator.ExprAnalysis> expressions,
            ResourceBudget budget) {
        List<String> targetColumns = new ArrayList<>();
        SelectQuery<Record> select = renderContext.selectQuery();
        select.addFrom(spec.model().table());
        // Keyed on the window's source-only predicates: a Class-1 expression reading a payload body is the
        // common case, and without the key the write would hash every in-window payload row per tick. The
        // key predicates are rendered from the unpruned model deliberately — they select from the source
        // inside the key subquery, where no enrichment column can appear (see keyPredicates).
        QueryEntityModel model = EnrichmentJoins.addReferenced(
                select, spec.model(), spec.referenced(), keyPredicates(spec), objectPaths(spec, expressions));
        WindowSpec rendered = spec.withModel(model);
        Map<String, QueryFieldBinding> bindings = model.bindings();

        targetColumns.add(grainKey);
        select.addSelect(ScanSourceMetadata.projected(model, bindings, grainKey));
        expressions.forEach((column, analysis) -> {
            targetColumns.add(column);
            select.addSelect(exprTranslator.toField(analysis.expr(), bindings));
        });
        addProvenance(target, declaration.generation(), targetColumns, select);

        // Exactly the predicates the probe ran — including the caller's from/to bounds, without which the
        // write's lower bound would fall back to the cursor and pull in rows below `from`.
        select.addConditions(windowPredicates(rendered));
        // Plus the probed end tuple, so a retry re-executes an identical range.
        select.addConditions(
                CursorPredicates.atMost(spec.cursorColumns(), window.version(), window.identity()));

        String columnList = String.join(", ",
                targetColumns.stream().map(c -> renderContext.render(DSL.name(c))).toList());
        String sql = "INSERT INTO " + renderContext.render(DSL.name(target)) + " (" + columnList + ") "
                + select.getSQL(ParamType.INLINED);
        log.debug("Materializing pipeline '{}' from '{}' into '{}'", declaration.name(), source, target);
        // Again, immediately before the statement: the check at the start of the call ran before the probe, and a
        // relayout can take its hold while the probe (or a narrowing re-probe) runs; an INSERT reaching the old
        // table after the final catch-up copy would be lost at the exchange.
        relayouts.requireNotHeld(target);   // 503 table_relayout_in_progress
        requireAlignmentUnchanged(target, expressions);   // 503 table_relayout_in_progress
        try {
            return insertSelect.insertSelect(target, sql, model.partitionLocal(), budget,
                    new StatementTag(declaration.name(), "write"));
        } catch (RuntimeException ex) {
            throw PipelineReadBudget.refusal(ex, "materialize write", budget);
        }
    }

    /**
     * Refuses an INSERT whose expressions were resolved while {@code target} was not yet aligned and that reaches it
     * aligned: a relayout aligned it meanwhile, and the statement would write no alignment value — rows the epoch
     * partition holds, past the relayout's last repair of it, until the inherited TTL drops them. Checked after the
     * hold, never before it: a relayout aligns the catalog while it holds the writers, so a write that finds the hold
     * released finds the catalog as the relayout left it.
     *
     * @throws TableRelayoutInProgressException (503) when the target is aligned and {@code expressions} carry no
     *                                          value for its alignment column
     */
    private void requireAlignmentUnchanged(String target, Map<String, ExprSqlValidator.ExprAnalysis> expressions) {
        String alignment = PartitionEcho.targetAlignment(definitions, definitions.findById(target).orElse(null));
        if (alignment != null && !expressions.containsKey(alignment)) {
            throw new TableRelayoutInProgressException("table '" + target + "' was relaid out by ADAS while this "
                    + "materialize ran; retry the same write");
        }
    }

    /**
     * Stamps the provenance columns the target physically carries. {@link #NOT_APPLICABLE_TO_SQL} is
     * always skipped, and any provenance column the target lacks is skipped too, matching the write-back
     * path's best-effort-lineage behaviour.
     *
     * <p><b>{@code pipeline_generation} is the stamp; {@code evaluator_version} is never written again.</b>
     * The evaluator version is gone as a concept — the definition is on the pipeline — and the two numbers
     * are deliberately not merged into one column: existing rows carry small version integers and new rows
     * would carry generations, and two number spaces in one column is unreadable. Already-stamped
     * {@code evaluator_version} values stay where they are, under a reserved name; nothing adds to them.
     *
     * <p>The declared type is load-bearing: the generation is written only into a column that can hold a
     * number. A column of any other type is skipped rather than failing the write, because provenance is
     * informational and must never cost a materialization.
     */
    private void addProvenance(
            String target,
            long generation,
            List<String> targetColumns,
            SelectQuery<Record> select) {
        Set<String> physical = tableData.physicalColumns(target);
        List<ColumnMappingEntity> declared = columns.findByTableNameOrderByOrdinal(target);
        if (physical.contains(ProvenanceColumns.PIPELINE_GENERATION)) {
            Optional<QueryFieldType> type = declaredType(declared, ProvenanceColumns.PIPELINE_GENERATION);
            Field<?> value = switch (type.orElse(null)) {
                case INTEGER, LONG -> DSL.inline(generation);
                case STRING -> DSL.inline(Long.toString(generation));
                case null, default -> null;
            };
            if (value != null) {
                targetColumns.add(ProvenanceColumns.PIPELINE_GENERATION);
                select.addSelect(value);
            } else {
                log.debug("Skipping provenance column '{}' on '{}': declared type cannot hold a generation",
                        ProvenanceColumns.PIPELINE_GENERATION, target);
            }
        }
        if (physical.contains(ProvenanceColumns.ENRICHED_AT)) {
            targetColumns.add(ProvenanceColumns.ENRICHED_AT);
            select.addSelect(DSL.field("now64(3)"));
        }
        NOT_APPLICABLE_TO_SQL.stream().filter(physical::contains).forEach(name -> log.debug(
                "Skipping provenance column '{}' on '{}': not applicable to a sql transform", name, target));
    }

    /**
     * The predicates shared by the probe and the write: the pipeline's filter, the safety-lag, the optional
     * caller window, and the keyset seek past the caller's cursor. Passing the same
     * {@code (filter, cursor)} to both is what makes the written range identical to the probed one.
     */
    private List<Condition> windowPredicates(WindowSpec spec) {
        return windowPredicates(spec, false);
    }

    /**
     * {@link #windowPredicates(WindowSpec)}, with the safety lag and the caller window over the source-qualified
     * version column when {@code qualified} — the Class-1 preview sample's spelling (see {@link #sampleQuery}). Only
     * for a spec with no cursor and no clamp, which is the only one the sample builds.
     */
    private List<Condition> windowPredicates(WindowSpec spec, boolean qualified) {
        List<Condition> conditions = new ArrayList<>();
        if (spec.filter() != null) {
            conditions.add(filterTranslator.toCondition(spec.filter(), spec.model().bindings()));
        }
        if (qualified) {
            if (spec.cursorValues() != null || spec.enrichmentBound() != null) {
                throw new IllegalStateException("a qualified window takes no cursor and no clamp");
            }
            conditions.add(CursorPredicates.qualifiedSafetyLag(spec.cursorColumns(), scanProperties.safetyLag()));
            conditions.addAll(CursorPredicates.qualifiedWindow(spec.cursorColumns(), spec.from(), spec.to()));
        } else {
            conditions.addAll(cursorPredicates(spec));
        }
        return conditions;
    }

    /**
     * The source-only conjuncts of {@link #windowPredicates}, for {@code EnrichmentJoins} to key the grain
     * joins on: the cursor predicates always (they are on the source's cursor columns), the filter only when
     * it names no enrichment column. Any subset of the statement's conjuncts is a sound key — it selects a
     * superset of the grain keys the statement reads — so a filter reaching into an enrichment is left out
     * rather than disqualifying the cursor predicates beside it.
     */
    private List<Condition> keyPredicates(WindowSpec spec) {
        List<Condition> keyed = new ArrayList<>();
        if (spec.filter() != null && EnrichmentJoins.referencesSourceOnly(spec.model(), spec.filterColumns())) {
            keyed.add(filterTranslator.toCondition(spec.filter(), spec.model().bindings()));
        }
        keyed.addAll(cursorPredicates(spec));
        return keyed;
    }

    /** The safety-lag, the optional caller window, the keyset seek, and the freshness clamp — all on the source. */
    private List<Condition> cursorPredicates(WindowSpec spec) {
        CursorColumns cursorColumns = spec.cursorColumns();
        List<Condition> conditions = new ArrayList<>();
        conditions.add(CursorPredicates.safetyLag(cursorColumns, scanProperties.safetyLag()));
        conditions.addAll(CursorPredicates.windowAndSeek(cursorColumns, spec.from(), spec.to(), spec.cursorValues()));
        if (spec.enrichmentBound() != null) {
            conditions.add(CursorPredicates.atMost(cursorColumns,
                    spec.enrichmentBound().version(), spec.enrichmentBound().identity()));
        }
        return conditions;
    }

    private static Optional<QueryFieldType> declaredType(List<ColumnMappingEntity> declared, String name) {
        return declared.stream()
                .filter(c -> c.getName().equals(name) || c.getSourceName().equals(name))
                .map(ColumnMappingEntity::getType)
                .findFirst();
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return materializeProperties.defaultLimit();
        }
        if (limit > materializeProperties.maxLimit()) {
            throw new QueryValidationException("limit exceeds the maximum of " + materializeProperties.maxLimit());
        }
        return limit;
    }

    private static String requireNotBlank(String value, String message) {
        if (StringUtils.isBlank(value)) {
            throw new QueryValidationException(message);
        }
        return value;
    }
}
