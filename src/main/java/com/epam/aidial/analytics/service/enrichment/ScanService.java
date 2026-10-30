package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.configuration.ScanProperties;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.observability.Elapsed;
import com.epam.aidial.analytics.observability.TransitionLog;
import com.epam.aidial.analytics.observability.trace.LogExecution;
import com.epam.aidial.analytics.service.enrichment.model.EnrichDeclaration;
import com.epam.aidial.analytics.service.enrichment.model.InputBinding;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.ScanPage;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.CursorCodec;
import com.epam.aidial.analytics.service.enrichment.support.CursorColumns;
import com.epam.aidial.analytics.service.enrichment.support.CursorPredicates;
import com.epam.aidial.analytics.service.enrichment.support.PartitionEcho;
import com.epam.aidial.analytics.service.enrichment.support.PipelineAuthorizer;
import com.epam.aidial.analytics.service.enrichment.support.ScanSourceMetadata;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichDeclarationCompleteness;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;
import com.epam.aidial.analytics.service.pipeline.EnrichmentFreshness;
import com.epam.aidial.analytics.service.pipeline.PipelineReadBudget;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumn;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumnRole;
import com.epam.aidial.analytics.service.query.EnrichmentJoins;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Governed incremental scan (B2). Resolves the pipeline's already-validated source/filter/grain,
 * authorizes by the pipeline's target-enrichment access, and runs a source-only keyset (seek) query:
 * projection pushdown to {filter columns} ∪ {bound-binding columns, row-grain pipelines only} ∪ {grain} ∪
 * {cursor columns}, a keyset
 * predicate on the opaque cursor tuple {@code (version, identity)}, safety-lag over the still-
 * mutating window, an optional caller-supplied version window, ordered by the cursor tuple, and
 * {@code final=1} at execution. The cursor is opaque and ADAS-owned; a scan requires the pipeline to
 * exist but not to be enabled.
 *
 * <p>The cursor tuple is projected as {@code toUnixTimestamp64Milli(version)} /
 * {@code toString(identity)} so encoding/decoding and the keyset compare are type-robust regardless
 * of the ClickHouse client's Java mapping. The version/identity columns come from the source's
 * catalog scan metadata ({@link ScanSourceMetadata}), not from a hardcoded table name.
 *
 * <p>The optional {@code from}/{@code to} window bounds the source's version column as the half-open
 * range {@code (from, to]}. It is an additional predicate only: it composes with the cursor keyset and
 * the safety-lag rather than replacing either, so paging semantics inside a window are identical to
 * paging without one, and a {@code to} above the safety-lag horizon is clamped by the horizon.
 *
 * <p>For a group pipeline the page doubles as the caller's dirty-mark read, and its readiness verdict is
 * computed <em>here</em>: {@code ready_when.signal} is translated into the query and projected as the
 * boolean {@link #SIGNAL_COLUMN}. See {@link #buildScan} for why the predicate is not shipped as raw
 * columns for the caller to re-evaluate.
 *
 * <p><b>A group pipeline's page carries no bound-binding columns.</b> Its consumer is a dirty-mark, which
 * needs to know which groups changed and not what they contain; the bound columns exist to render one
 * prompt per group and are read by {@code POST /v1/pipelines/{id}/group-rows}, where the row and byte caps
 * that make reading a body safe are applied. Projecting them here would put an unbounded volume — a
 * group pipeline may bind request/response bodies — on the one pipeline-scoped read that has <em>no</em> byte
 * cap, and would charge every page for data the caller discards. A row-grain pipeline still projects them,
 * because there the scanned row <em>is</em> the unit of work.
 *
 * <p>That makes the projection and the <b>guarded</b> set two different sets, which is deliberate: the
 * sensitive-column re-check governs what a pipeline reads or derives from, not what the response returns.
 * A group pipeline binding a sensitive column still reads it — at the assembly endpoint — so narrowing the
 * projection must not become a way for a non-entitled caller to dirty-mark a pipeline whose groups it would
 * then be refused. {@code ready_when.signal} already sets that precedent below.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class ScanService {

    /**
     * The computed readiness verdict a group pipeline's page carries: whether that row satisfies the pipeline's
     * {@code ready_when.signal}. Double-underscored like the cursor aliases to keep it out of the source's
     * own namespace, but unlike them it is the payload rather than internal, so it survives into the
     * response — normalized to a JSON boolean, since ClickHouse answers a predicate with {@code UInt8}.
     */
    public static final String SIGNAL_COLUMN = "__signal";

    private static final String CURSOR_VERSION = "__cursor_v";
    private static final String CURSOR_IDENTITY = "__cursor_id";

    /** The group key a preview's group choice projects, as text. */
    private static final String GROUP_KEY = "__group_key";

    private final PipelineService pipelines;
    private final PipelineAuthorizer authorizer;
    private final ScanSourceMetadata scanSources;
    private final SensitiveProjectionGuard sensitiveGuard;
    private final FilterSqlValidator filterValidator;
    private final FilterTranslator filterTranslator;
    private final DSLContext renderContext;
    private final StructuredQueryExecutor executor;
    private final CursorCodec codec;
    private final ScanProperties scanProperties;
    private final EnrichmentFreshness freshness;
    private final PipelineBudgetProperties readBudget;
    private final TransitionLog transitions;
    private final TableDefinitionRepository definitions;

    /**
     * The runner's scan: one page, and one outcome line for it (see {@link #logOutcome}).
     */
    public ScanPage scan(String pipelineName, String cursor, Integer limit, Instant from, Instant to) {
        final long started = System.nanoTime();
        final ResolvedEnrichPipeline resolved = pipelines.resolveForScan(pipelineName);  // 404 unknown_pipeline if absent
        final Scanned scanned = run(resolved, cursor, limit, from, to);
        logOutcome(resolved, scanned, Elapsed.millisSince(started));
        return scanned.page();
    }

    /** One scan's page, and how long its statement took ({@code null} when no statement ran). */
    private record Scanned(ScanPage page, Long statementMs) {}

    /**
     * The outcome line of a runner scan: the pipeline, its generation, the rows, {@code has_more}, {@code lag_s},
     * the statement's and the call's durations and the statement's query id — never a row value or the cursor.
     * An empty page is logged only on the pipeline's first scan on this replica or after one that returned
     * rows: a caught-up pipeline polls on a short cadence, and the line is there to show when it caught up.
     */
    private void logOutcome(ResolvedEnrichPipeline resolved, Scanned scanned, long totalMs) {
        final String pipelineName = resolved.pipeline().getName();
        final ScanPage page = scanned.page();
        final boolean empty = page.rows().isEmpty();
        final String previous = transitions.swap(TransitionLog.Channel.SCAN, pipelineName, empty ? "empty" : "rows");
        if (empty && "empty".equals(previous)) {
            return;
        }
        final String queryId = scanned.statementMs() == null ? null : StatementTag.current();
        log.info("scan pipeline={} gen={} rows={} has_more={} lag_s={} statement_ms={} total_ms={} query_id={}",
                pipelineName, resolved.declaration().generation(), page.rows().size(), page.hasMore(),
                page.lagSeconds() == null ? "-" : page.lagSeconds(),
                scanned.statementMs() == null ? "-" : scanned.statementMs(), totalMs,
                queryId == null ? "-" : queryId);
    }

    private Scanned run(ResolvedEnrichPipeline resolved, String cursor, Integer limit, Instant from, Instant to) {
        final String pipelineName = resolved.pipeline().getName();
        Source source = source(resolved);
        int effectiveLimit = resolveLimit(limit);
        CursorPredicates.requireOrderedWindow(from, to);

        List<String> cursorValues = StringUtils.isBlank(cursor)
                ? null
                : codec.decode(cursor, source.name()).values();     // 400 invalid_cursor; verifies entity + HMAC

        ScanPlan plan = plan(resolved, source);
        if (plan.withholdsEverything()) {
            // An enrichment this read depends on has recorded no usable position, so no row is safely
            // readable yet. An empty page echoing the cursor is the honest answer — rows are late, not absent.
            return new Scanned(toPage(new ArrayList<>(), source.name(), cursor, effectiveLimit), null);
        }

        SelectQuery<Record> select = buildScan(plan, cursorValues, effectiveLimit, from, to);
        final long statementStarted = System.nanoTime();
        List<Map<String, Object>> rows = PipelineReadBudget.run(
                executor, select, plan.model(), "enrichment scan", readBudget, new StatementTag(pipelineName, "scan"));
        final long statementMs = Elapsed.millisSince(statementStarted);
        return new Scanned(toPage(rows, source.name(), cursor, effectiveLimit), statementMs);
    }

    /** The read source and its cursor columns, resolved once the pipeline is known to be scannable. */
    private record Source(String name, CursorColumns cursorColumns) {}

    /**
     * Everything a read over the pipeline's source is planned from, computed once and rendered by each read that
     * shares it — the runner's scan page and the dry-run preview's reads: the filter and readiness-signal
     * analyses, the projection and the set its statement names, the bound columns, the group key, and the
     * freshness bound taken over the guarded set. Every read renders its joins, its sensitive check and its clamp
     * from this one plan, so the preview cannot read differently from the runner while looking the same.
     */
    private record ScanPlan(
            String pipelineName,
            String source,
            String grainKey,
            CursorColumns cursorColumns,
            QueryEntityModel model,
            FilterSqlValidator.FilterAnalysis analysis,
            FilterSqlValidator.FilterAnalysis signal,
            Set<String> projection,
            Set<String> statement,
            Set<String> boundColumns,
            String groupKeyField,
            String groupKeyAlias,
            EnrichmentFreshness.Bound bound,
            String partitionEcho) {

        boolean withholdsEverything() {
            return bound != null && bound.withholdsEverything();
        }
    }

    /** Authorizes the read and resolves its source; the request's own parameters are checked after this. */
    private Source source(ResolvedEnrichPipeline resolved) {
        final String pipelineName = resolved.pipeline().getName();
        EnrichDeclaration declaration = resolved.declaration();
        // Read source and target are authorized separately: the scan returns rows of the source.
        authorizer.authorize(resolved.source(), declaration.target());   // 403 pipeline_access_denied (bypassed in none mode)
        // A disabled pipeline may be a draft stored before it passed the gate: refuse one that is missing a
        // member the scan compiles from, before any statement is built. Absence only — a complete but
        // drifted declaration still scans. Reads stay open to a disabled pipeline, unlike the writing
        // endpoints, because its dry-run preview samples through this one.
        //
        // A Class-1 (sql) pipeline computes its values in the database via the materialize path; there is no
        // per-row payload to scan, so the two execution paths are deliberately not interchangeable. Asked
        // ahead of completeness, because "use the other endpoint" is the more useful first answer. The
        // dispatch is on the TYPE alone, with a declaration that carries no transform refused outright by
        // the shared guard rather than folded into this condition. Testing `transform != null` as well used
        // to let such a declaration fall THROUGH into the scan, while MaterializeService refused it — one
        // question, two answers, and the way a pipeline stripped of its transform came to read as a working
        // llm one.
        EnrichDeclarationCompleteness.requireRunnable(resolved, "be scanned", transform -> {
            if (transform.getType() == TransformType.SQL) {
                throw new PipelineValidationException("pipeline '" + pipelineName + "' declares a sql transform;"
                        + " use POST /v1/pipelines/{name}/materialize instead of the row scan");
            }
        });
        String source = resolved.source();
        if (StringUtils.isBlank(source)) {
            throw new QueryValidationException("pipeline '" + pipelineName + "' target has no resolvable source");
        }
        return new Source(source, scanSources.cursorColumns(source));   // 422 if the source has no scan metadata
    }

    private ScanPlan plan(ResolvedEnrichPipeline resolved, Source resolvedSource) {
        EnrichDeclaration declaration = resolved.declaration();
        String source = resolvedSource.name();
        CursorColumns cursorColumns = resolvedSource.cursorColumns();
        QueryEntityModel model = scanSources.entityModel(source);
        // A non-entitled caller cannot even parse the stored pipeline's SQL over a sensitive column, because
        // those columns are hidden from its schema view; re-attribute that to the 403 it really is rather
        // than reporting a governed pipeline as malformed. Enrichment columns count for that re-attribution
        // here, because a pipeline-scoped read admits them.
        FilterSqlValidator.FilterAnalysis analysis = sensitiveGuard.reattributing(
                source, "scan", () -> filterValidator.analyze(source, declaration.filterSql(), "filter", true), true);

        Set<String> boundColumns = new LinkedHashSet<>();
        for (InputBinding binding : declaration.inputBindings()) {
            if (StringUtils.isNotBlank(binding.getColumn())) {
                boundColumns.add(binding.getColumn());
            }
        }

        Set<String> projection = new LinkedHashSet<>(analysis.columns());
        // A group pipeline's bound columns are read by group-input assembly, under its row and byte caps —
        // not here, where nothing bounds them and the dirty-mark would only discard them.
        if (declaration.triggerKind() != TriggerKind.GROUP) {
            projection.addAll(boundColumns);
        }
        // An enrichment-sourced group key is projected under BOTH names, and both are load-bearing.
        //
        // Under its own qualified name because that is the spelling `group_by` carries on the wire, and a
        // consumer addresses the key column by the name the pipeline declares — the Enrichment Runner reads it
        // as `row.get(pipeline.groupBy())` (GroupMarkBuilder), and a row where that misses is skipped with
        // nothing logged, so the whole pipeline silently does nothing. Verified live: dropping this name made
        // the runner scan the page, advance its cursor, and mark zero groups.
        //
        // Under the target's plain grain key because that is what the enrichment is physically keyed on and
        // what the key round-trips as through the dirty mark, the assembly request and the write-back. The
        // plain name is an ALIAS over the joined field, never a projection of its own: nothing declares it
        // on the read source, so projecting it by name would render a column the FROM clause does not carry.
        // Null-safe on its own, not only because the completeness refusal above runs first: a null here was
        // added to the projection as a column with no name.
        String groupKeyField = declaration.triggerKind() == TriggerKind.GROUP
                ? StringUtils.trimToNull(declaration.triggerGroupBy()) : null;
        boolean aliasedGroupKey = groupKeyField != null && !groupKeyField.equals(resolved.grainKey());
        if (StringUtils.isNotBlank(resolved.grainKey()) && !aliasedGroupKey) {
            projection.add(resolved.grainKey());
        }
        // For a group pipeline the same page doubles as the dirty-mark read: it must also carry the group key
        // and the pipeline's readiness verdict, so the caller can update its per-group state from one scan.
        // The activity timestamp needs no addition — the version column is projected just below, under its
        // own name. Cost stays proportional to new rows, not to the number of groups.
        FilterSqlValidator.FilterAnalysis signal = new FilterSqlValidator.FilterAnalysis(null, Set.of());
        if (groupKeyField != null) {
            // Whichever spelling it is: a bare source column, or the qualified enrichment field the grain
            // join resolves. The plain-grain-key alias is added on top in buildScan.
            projection.add(groupKeyField);
            if (declaration.readyWhen() != null && StringUtils.isNotBlank(declaration.readyWhen().getSignal())) {
                signal = sensitiveGuard.reattributing(source, "scan", () -> filterValidator
                        .analyze(source, declaration.readyWhen().getSignal(), "ready_when.signal", true), true);
            }
        }
        projection.add(cursorColumns.version());
        projection.add(cursorColumns.identity());

        // Mirror the query surface's sensitive-column rule at the egress point: a caller who may not
        // see sensitive columns (not both TABLE_READ and TABLE_SENSITIVE on the owning table, outside none mode) must not
        // scan a pipeline that projects
        // one. Governance already blocks such references at registration, but re-checking here against
        // the runner's own identity closes buildScan's raw-name fallback, which would otherwise still
        // select a sensitive column that the role-filtered model omitted from its bindings.
        //
        // The signal's columns are checked even though they are not projected: the verdict is derived from
        // them, and this guard rejects a sensitive column being *read or derived*, not merely returned.
        // Without them here, computing the verdict server-side would have quietly relaxed the posture that
        // shipping the raw columns enforced.
        //
        // A group pipeline's bound columns are checked on the same reasoning: the pipeline reads them, at the
        // assembly endpoint rather than here, so dropping them from this projection must not hand a
        // non-entitled caller a dirty-mark on a pipeline whose groups it would be refused.
        // What THIS statement names: its projection — the group key included, under its declared spelling —
        // and its readiness signal. The joins it emits follow from exactly this set, so it never joins a
        // table it does not read.
        Set<String> statement = new LinkedHashSet<>(projection);
        statement.addAll(signal.columns());
        Set<String> guarded = new LinkedHashSet<>(statement);
        guarded.addAll(boundColumns);
        sensitiveGuard.enforceEntity(source, guarded, "scan");
        // A declaration stored before every write refused a computed group key would be grouped by the stand-in this
        // page renders in its place; the fragments are refused by name already, in their parse above.
        if (groupKeyField != null) {
            GroupFetchService.requireNotComputed("trigger.group_by", groupKeyField);
        }

        // The clamp is taken over the wider set — the statement's own names PLUS a group pipeline's bound
        // columns, which this page does not project but the assembly reads for the very rows this page
        // marks. A bound value read while its enrichment cell is absent is a wrong prompt value, so the
        // enrichment behind it has to hold this window back even though no clause here names it. The join
        // set stays narrow: joining an enrichment the statement never selects from would make every scan
        // tick pay a full hash read of it for nothing.
        Optional<EnrichmentFreshness.Bound> bound =
                freshness.bound(EnrichmentJoins.referencedEnrichments(model, guarded), source);
        return new ScanPlan(resolved.pipeline().getName(), source, resolved.grainKey(), cursorColumns, model,
                analysis, signal, projection, statement, boundColumns, groupKeyField,
                aliasedGroupKey ? resolved.grainKey() : null, bound.orElse(null),
                PartitionEcho.column(definitions, definitions.findById(declaration.target()).orElse(null),
                        declaration.triggerKind(), source));
    }

    /**
     * The runner's scan page: the plan's projection, ordered by the cursor tuple ascending — the keyset order that
     * is this endpoint's contract with the runner.
     */
    private SelectQuery<Record> buildScan(ScanPlan plan, List<String> cursorValues, int limit, Instant from, Instant to) {
        QueryEntityModel model = plan.model();
        Map<String, QueryFieldBinding> bindings = model.bindings();
        FilterNode signal = plan.signal().filter();
        List<Condition> sourcePredicates = sourcePredicates(plan, from, to, cursorValues, plan.bound());
        Base base = base(plan, plan.statement(), sourcePredicates);
        SelectQuery<Record> select = base.select();

        for (String name : plan.projection()) {
            select.addSelect(ScanSourceMetadata.projected(model, bindings, name).as(name));
        }
        if (plan.groupKeyAlias() != null) {
            // The second name for the same field: the projection loop above already emitted it under its
            // qualified name, and this adds the target's plain grain key beside it.
            Field<?> groupField = ScanSourceMetadata.projected(model, bindings, plan.groupKeyField());
            select.addSelect(groupField.as(plan.groupKeyAlias()));
            // An unaddressable group key is excluded by the read rather than required of the author's
            // filter: whether a predicate excludes NULL and '' is not decidable from its parse tree, so a
            // declared requirement would read as a guarantee it cannot give. NULL is the unmatched join;
            // '' is both a derived "no key" and, with join_use_nulls=0, an unmatched non-nullable column.
            // Neither addresses a row of the target — an empty key would collect every keyless source row
            // into one group that corresponds to nothing.
            select.addConditions(groupField.isNotNull(),
                    DSL.condition("toString({0}) != ''", groupField));
        }

        if (plan.partitionEcho() != null) {
            // The aligned target's partition value, for the runner to hand back with the row (PartitionEcho).
            select.addSelect(ScanSourceMetadata.projected(model, bindings, plan.partitionEcho()).as(PartitionEcho.MEMBER));
        }
        Field<Long> versionExpr = CursorPredicates.versionExpr(plan.cursorColumns());
        Field<String> identityExpr = CursorPredicates.identityExpr(plan.cursorColumns());
        select.addSelect(versionExpr.as(CURSOR_VERSION));
        select.addSelect(identityExpr.as(CURSOR_IDENTITY));
        if (signal != null) {
            // The verdict, not the evidence. Shipping the raw columns instead would make every caller
            // re-implement ADAS's bounded predicate grammar, and a second implementation is free to
            // diverge — silently marking the wrong groups ready rather than failing. It also keeps
            // values the caller has no other use for (possibly sensitive ones) inside ClickHouse.
            select.addSelect(DSL.field(filterTranslator.toCondition(signal, bindings)).as(SIGNAL_COLUMN));
        }

        base.addPredicates(sourcePredicates);
        select.addOrderBy(versionExpr.asc(), identityExpr.asc());
        select.addLimit(limit);
        return select;
    }

    /**
     * A statement over the plan's source with its joins emitted: the {@code FROM}, a LEFT JOIN per enrichment
     * {@code referenced} names, and the translated filter, which the caller adds to the {@code WHERE} with its own
     * predicates once its select list is in place.
     */
    private Base base(ScanPlan plan, Set<String> referenced, List<Condition> sourcePredicates) {
        QueryEntityModel model = plan.model();
        FilterNode filter = plan.analysis().filter();
        SelectQuery<Record> select = renderContext.selectQuery();
        select.addFrom(model.table());

        // The cursor predicates play two roles: they bound the statement's WHERE, and they key its grain joins.
        // All of them are on the source's cursor columns, so they are sound keys on their own; the author's
        // filter joins them only when it names no enrichment column (a filter that reaches into an enrichment
        // would need that very join inside the key sub-query). Without the keying, a scan tick naming one
        // payload column would hash the whole payload table to enrich one page of source rows.
        Condition filterCondition = filter == null ? null : filterTranslator.toCondition(filter, model.bindings());
        List<Condition> keyPredicates = new ArrayList<>(sourcePredicates);
        if (filterCondition != null
                && EnrichmentJoins.referencesSourceOnly(model, StructuredQueryBuilder.referencedFields(filter))) {
            keyPredicates.add(filterCondition);
        }
        // A LEFT JOIN per enrichment this statement's own clauses name, and none for the rest: a pipeline
        // referencing no enrichment renders exactly the statement it rendered before they were admitted.
        //
        // Deliberately NOT pruned to addressed JSON paths, unlike the Class-1 materialize statements. A scan
        // projects every column its filter names (see `projection` in the plan, built from the filter's own
        // analysis), and a projected `object` column is returned to the caller as a whole document — so
        // every column a path could be pruned to is already needed whole, and the pruning would be inert.
        // Making it fire would mean changing what the page projects, which is the caller's contract, not a
        // rendering detail. Until then this path keeps the unpruned projection, and the clauses translated
        // below keep resolving against the bindings they were translated with.
        EnrichmentJoins.addReferenced(select, model, referenced, keyPredicates);
        return new Base(select, filterCondition);
    }

    /** A statement with its joins emitted, and the filter its {@code WHERE} carries ahead of the source predicates. */
    private record Base(SelectQuery<Record> select, Condition filter) {

        /**
         * The filter, then the safety lag, the {@code (from, to]} window, the seek past the cursor and — when
         * present — the freshness clamp. The clamp: an enrichment row lands after the source row it derives
         * from, while this window advances on the source's cursor — so without it a row is read while its
         * enrichment cell is still absent, and because the source row never moves again the loss is permanent:
         * the group is attributed wrongly or not at all and is never revisited. Holding the window back is what
         * makes the four surfaces an enrichment can feed (group key, signal, bindings, Class-1 expression)
         * correct at once, because a row outside the window feeds none of them.
         */
        void addPredicates(List<Condition> sourcePredicates) {
            if (filter != null) {
                select.addConditions(filter);
            }
            select.addConditions(sourcePredicates);
        }
    }

    /** The safety lag, the optional window, the seek past {@code cursorValues}, and the clamp at {@code clamp}. */
    private List<Condition> sourcePredicates(ScanPlan plan, Instant from, Instant to, List<String> cursorValues,
            EnrichmentFreshness.Bound clamp) {
        List<Condition> sourcePredicates = new ArrayList<>();
        sourcePredicates.add(CursorPredicates.safetyLag(plan.cursorColumns(), scanProperties.safetyLag()));
        sourcePredicates.addAll(CursorPredicates.windowAndSeek(plan.cursorColumns(), from, to, cursorValues));
        if (clamp != null) {
            sourcePredicates.add(CursorPredicates.atMost(plan.cursorColumns(), clamp.version(), clamp.identity()));
        }
        return sourcePredicates;
    }

    /**
     * A row-grain pipeline's dry-run sample: the newest rows the runner's scan would read, carrying only the grain
     * key and the bound inputs.
     *
     * @param rows         the sampled rows, newest first
     * @param columns      the grain key and the bound inputs, typed by the source's bindings
     * @param heldBy       the enrichment that withholds every row (it has no usable bound), or null
     * @param bound        the freshness bound the read was clamped to, or null when it read no enrichment
     * @param aboveBound   the newest matching version above {@code bound}, as epoch milliseconds, when the clamp
     *                     left matching rows out; null when it left none or no probe was needed
     */
    public record RowSample(List<Map<String, Object>> rows, List<PreviewColumn> columns,
            String heldBy, EnrichmentFreshness.Bound bound, Long aboveBound) {}

    /**
     * Samples a row-grain pipeline for the dry-run preview: the runner scan's own plan — the same joins, the same
     * sensitive check, the same clamp — rendered with a narrower select list (the grain key and the bound inputs,
     * never the filter, cursor or version columns) and the opposite order, newest first. A separate read over the
     * same predicates rather than a mode of the runner's scan, whose ascending keyset order is its contract.
     *
     * <p>Where the clamp is partial, one more probe asks whether it left any matching row out, projecting only
     * the version and reading at most one row: re-running the sample with the clamp lifted would read its heavy
     * columns again, and with no {@code from} would have no lower bound.
     */
    public RowSample previewRows(ResolvedEnrichPipeline resolved, int limit, Instant from, Instant to) {
        Source source = source(resolved);
        CursorPredicates.requireOrderedWindow(from, to);
        ScanPlan plan = plan(resolved, source);
        QueryEntityModel model = plan.model();
        Map<String, QueryFieldBinding> bindings = model.bindings();

        Set<String> sampled = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(plan.grainKey())) {
            sampled.add(plan.grainKey());
        }
        sampled.addAll(plan.boundColumns());
        List<PreviewColumn> columns = sampled.stream()
                .map(name -> PreviewColumn.read(name,
                        name.equals(plan.grainKey()) ? PreviewColumnRole.GRAIN_KEY : PreviewColumnRole.INPUT, bindings.get(name)))
                .toList();
        if (plan.withholdsEverything()) {
            return new RowSample(List.of(), columns, plan.bound().enrichment(), plan.bound(), null);
        }

        List<Condition> sourcePredicates = sourcePredicates(plan, from, to, null, plan.bound());
        Base base = base(plan, plan.statement(), sourcePredicates);
        SelectQuery<Record> select = base.select();
        for (String name : sampled) {
            select.addSelect(ScanSourceMetadata.projected(model, bindings, name).as(name));
        }
        base.addPredicates(sourcePredicates);
        select.addOrderBy(CursorPredicates.versionExpr(plan.cursorColumns()).desc(),
                CursorPredicates.identityExpr(plan.cursorColumns()).desc());
        select.addLimit(limit);
        List<Map<String, Object>> rows = PipelineReadBudget.run(executor, select, model, "preview sample",
                readBudget, new StatementTag(plan.pipelineName(), "preview"));

        Long aboveBound = plan.bound() == null ? null : newestAbove(plan, from, to);
        return new RowSample(rows, columns, null, plan.bound(), aboveBound);
    }

    /**
     * The newest matching version past the plan's bound, or null when there is none: the filter, the safety lag
     * and the window, seeked past the bound, projecting the version alone. The bound is handed over as the seek
     * position a runner's cursor would be, so the window and the seek share one partition floor at the later of
     * {@code from} and the bound rather than rendering one each.
     */
    private Long newestAbove(ScanPlan plan, Instant from, Instant to) {
        // Arrays.asList, not List.of: the identity half may be null, and is rendered as the seek always rendered it.
        List<String> pastBound = Arrays.asList(Long.toString(plan.bound().version()), plan.bound().identity());
        List<Condition> sourcePredicates = sourcePredicates(plan, from, to, pastBound, null);
        Base base = base(plan, plan.analysis().columns(), sourcePredicates);
        Field<Long> versionExpr = CursorPredicates.versionExpr(plan.cursorColumns());
        base.select().addSelect(versionExpr.as(CURSOR_VERSION));
        base.addPredicates(sourcePredicates);
        base.select().addOrderBy(versionExpr.desc());
        base.select().addLimit(1);
        List<Map<String, Object>> rows = PipelineReadBudget.run(executor, base.select(), plan.model(),
                "preview clamp probe", readBudget, new StatementTag(plan.pipelineName(), "preview-clamp-probe"));
        return rows.isEmpty() ? null : ((Number) rows.getFirst().get(CURSOR_VERSION)).longValue();
    }

    /**
     * The enrichment whose missing freshness bound withholds every row of this pipeline's reads, or null when
     * none does — asked of the scan's plan alone, so no statement is issued. The {@code group} preview of a named
     * group asks it: its member read is unclamped, as the runner's group fetch is, but an enrichment with no bound
     * at all still means an enabled run would write nothing. Authorizes and enforces the sensitive-column rule as
     * every read of the plan does.
     */
    public String withholdingEnrichment(ResolvedEnrichPipeline resolved) {
        ScanPlan plan = plan(resolved, source(resolved));
        return plan.withholdsEverything() ? plan.bound().enrichment() : null;
    }

    /**
     * The group a {@code group} pipeline's preview samples when the caller names none: the key of the newest
     * matching row, or null when there is none.
     *
     * @param heldBy  the enrichment whose missing bound withholds every row from the choice, or null
     * @param columns the bound inputs a member sample of this pipeline carries, typed by the source's bindings,
     *                so an empty sample still names its columns
     */
    public record GroupPick(String groupKey, String heldBy, List<PreviewColumn> columns) {}

    /**
     * Chooses the newest group for a {@code group} pipeline's preview, as the runner's dirty-mark scan would reach
     * it: the plan's filter, safety lag and freshness clamp (the group fetch has no clamp of its own, so without
     * it the preview could choose a group the runner has not marked yet), with a NULL or empty key excluded —
     * the group fetch refuses a blank key, and a keyless row belongs to no group. The key is projected as text,
     * because that is how the fetch compares it.
     *
     * <p>The last 24 hours are tried first, which prunes partitions on a source declaring a partition column; one
     * retry over the whole source follows only if they hold nothing.
     */
    public GroupPick pickGroup(ResolvedEnrichPipeline resolved) {
        Source source = source(resolved);
        ScanPlan plan = plan(resolved, source);
        List<PreviewColumn> columns = plan.boundColumns().stream()
                .map(name -> PreviewColumn.read(name, PreviewColumnRole.INPUT, plan.model().bindings().get(name)))
                .toList();
        if (plan.withholdsEverything()) {
            return new GroupPick(null, plan.bound().enrichment(), columns);
        }
        String key = newestGroupKey(plan, Instant.now().minus(Duration.ofHours(24)));
        return new GroupPick(key != null ? key : newestGroupKey(plan, null), null, columns);
    }

    private String newestGroupKey(ScanPlan plan, Instant from) {
        QueryEntityModel model = plan.model();
        Field<?> groupField = ScanSourceMetadata.projected(model, model.bindings(), plan.groupKeyField());
        Set<String> referenced = new LinkedHashSet<>(plan.analysis().columns());
        referenced.add(plan.groupKeyField());
        List<Condition> sourcePredicates = sourcePredicates(plan, from, null, null, plan.bound());
        Base base = base(plan, referenced, sourcePredicates);
        SelectQuery<Record> select = base.select();
        select.addSelect(DSL.field("toString({0})", String.class, groupField).as(GROUP_KEY));
        select.addConditions(groupField.isNotNull(), DSL.condition("toString({0}) != ''", groupField));
        base.addPredicates(sourcePredicates);
        select.addOrderBy(CursorPredicates.versionExpr(plan.cursorColumns()).desc(),
                CursorPredicates.identityExpr(plan.cursorColumns()).desc());
        select.addLimit(1);
        List<Map<String, Object>> rows = PipelineReadBudget.run(executor, select, model, "preview group choice",
                readBudget, new StatementTag(plan.pipelineName(), "preview-group-pick"));
        return rows.isEmpty() ? null : String.valueOf(rows.getFirst().get(GROUP_KEY));
    }

    private ScanPage toPage(List<Map<String, Object>> rows, String source, String inputCursor, int limit) {
        boolean hasMore = rows.size() == limit;
        String nextCursor;
        Long lagSeconds;
        if (rows.isEmpty()) {
            nextCursor = inputCursor;
            lagSeconds = null;
        } else {
            // Rows are ordered by the cursor tuple (version, identity) ascending, so the last row
            // carries both the page's max version and the tuple the next scan seeks past.
            Map<String, Object> last = rows.getLast();
            long lastVersion = ((Number) last.get(CURSOR_VERSION)).longValue();
            String lastIdentity = String.valueOf(last.get(CURSOR_IDENTITY));
            nextCursor = codec.encode(source, List.of(Long.toString(lastVersion), lastIdentity));
            lagSeconds = Math.max(0L, (System.currentTimeMillis() - lastVersion) / 1000L);
        }
        rows.forEach(row -> {
            row.remove(CURSOR_VERSION);
            row.remove(CURSOR_IDENTITY);
            // ClickHouse answers a predicate with UInt8 — and with a *nullable* UInt8 when the predicate
            // touches a nullable column. Normalizing here is what makes __signal a plain JSON boolean on
            // the wire, so the contract is a verdict rather than a number the caller has to interpret.
            if (row.containsKey(SIGNAL_COLUMN)) {
                row.put(SIGNAL_COLUMN, signalled(row.get(SIGNAL_COLUMN)));
            }
        });
        return new ScanPage(rows, nextCursor, hasMore, lagSeconds);
    }

    /** A null verdict (a predicate over a null cell) is not a signal: unknown fails closed. */
    private static boolean signalled(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof Number number && number.intValue() != 0;
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return scanProperties.defaultLimit();
        }
        if (limit > scanProperties.maxLimit()) {
            throw new QueryValidationException("limit exceeds the maximum of " + scanProperties.maxLimit());
        }
        return limit;
    }
}
