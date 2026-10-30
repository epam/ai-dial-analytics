package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.service.enrichment.support.CursorColumns;
import com.epam.aidial.analytics.service.enrichment.support.CursorPredicates;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.GroupKey;
import com.epam.aidial.analytics.service.pipeline.model.Measure;
import com.epam.aidial.analytics.service.query.EnrichmentJoin;
import com.epam.aidial.analytics.service.query.EnrichmentJoins;
import com.epam.aidial.analytics.service.query.PartitionPreselection;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.table.Identifiers;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Compiles an {@link AggregateSpec} into the statements a refresh runs. The declaration is lowered to
 * the <em>existing</em> expression translator — a group bucket becomes a {@code date_trunc} call and a
 * measure becomes an aggregate function call, both resolved through the same closed catalog the query
 * DSL uses — so the measure vocabulary and the query surface cannot drift apart, and no SQL is ever
 * assembled by string concatenation.
 *
 * <p>The translation stops at {@link ExprTranslator} rather than going through
 * {@code StructuredQueryBuilder}: that builder applies a default {@code LIMIT} when a query carries no
 * page, which on a write path would silently truncate a rollup to the first page of group keys. The
 * Class-1 materialize path composes its write the same way, at the expression level.
 *
 * <p><b>The write recomputes whole keys, never increments.</b> The target collapses by its ordering key,
 * so an {@code INSERT} <em>replaces</em> a key's row. A statement that aggregated only the new window
 * would therefore overwrite a bucket's running total with the newest slice — no error, no duplicate row,
 * just a wrong number. So the compiled statement aggregates over <em>all</em> rows of every key the
 * window touched, selecting those keys with a tuple {@code IN} over a sub-select restricted to the
 * window:
 *
 * <pre>{@code
 * INSERT INTO usage_daily (deployment, day, call_count, _updated_at)
 * SELECT deployment, date_trunc('day',request_time) AS day, count(), now64(3)
 * FROM dial_usage_log
 * WHERE <filter>
 *   AND (deployment, date_trunc('day',request_time)) IN (
 *       SELECT DISTINCT deployment, date_trunc('day',request_time)
 *       FROM dial_usage_log WHERE <filter> AND <window predicates>)
 * GROUP BY deployment, day
 * }</pre>
 *
 * <p>Because the window is pinned by a probe before this runs, a retry re-derives the same key set and
 * writes the same values — which the target then collapses. Idempotence is a property of this shape, not
 * an extra mechanism.
 *
 * <p>When the declaration carries a time bucket, the recompute half additionally gets a bound on the
 * bucketed column, taken from the span of the buckets the window dirtied ({@link #bucketBound}). That is a
 * pruning bound, not a narrowing of the key set — it is implied by those buckets — and it is what stops
 * every refresh from reading the whole input, since the tuple {@code IN} above gives the index nothing to
 * work with.
 *
 * <p>When the whole grain reads one enrichment, the same dirty-key condition also restricts that
 * enrichment inside its grain join ({@link #groupEnrichment}), so the hash join is built from the dirty
 * keys' enrichment rows rather than from the whole enrichment.
 */
@Component
@RequiredArgsConstructor
public class AggregateCompiler {

    /** The target's version column, stamped so each refresh wins over the previous one for a key. */
    private static final String UPDATED_AT = "_updated_at";

    private final ExprTranslator exprTranslator;
    private final DSLContext renderContext;

    /** A compiled refresh: the statement to execute and the target columns it writes, in order. */
    public record Compiled(String sql, List<String> targetColumns) {}

    /**
     * A translated predicate together with the entity field names it reads.
     *
     * <p>The names travel with the condition because a statement carrying the predicate must also join
     * whatever enrichment the predicate reaches through — and by the time the predicate is a
     * {@link Condition} its column names are gone. Passing the two separately is the same information with
     * one more way to forget half of it at one of the five statement sites below.
     *
     * @param condition the rendered predicate, or null for "all rows"
     * @param columns the flat entity field names it reads, qualified {@code "<enrichment>.<column>"} for a
     *     column reached through the grain join
     * @param node the parsed predicate {@code condition} was translated from, for the preview's portable query;
     *     null for "all rows", and where only the condition is at hand
     */
    public record CompiledFilter(Condition condition, Set<String> columns, FilterNode node) {

        /** No predicate: renders nothing, references nothing, joins nothing. */
        public static final CompiledFilter NONE = new CompiledFilter(null, Set.of(), null);

        public CompiledFilter(Condition condition, Set<String> columns) {
            this(condition, columns, null);
        }
    }

    /**
     * Compiles the full recompute statement for one window.
     *
     * @param windowPredicates the probed window's predicates (safety lag, cursor seek, probed end) —
     *     applied to the dirty-key sub-select, which is what bounds the run
     * @param filter the pipeline's membership predicate, applied to both halves
     *     ({@link CompiledFilter#NONE} for "all rows") — and, being read by every statement here, the one
     *     reference that pulls its enrichments into all five
     * @param measureFilters each measure's own {@code where}, by measure name, already translated; absent
     *     for a measure with none. These scope an individual aggregate <em>inside</em> the group and so
     *     never reach the {@code WHERE} clause of either half — a measure predicate must not narrow the
     *     rows the other measures see, and must not narrow the dirty-key set, or a key whose only matching
     *     rows fall outside the window would stop being recomputed
     */
    public Compiled compileRefresh(
            AggregateSpec spec,
            QueryEntityModel model,
            String target,
            CompiledFilter filter,
            Map<String, CompiledFilter> measureFilters,
            List<Condition> windowPredicates) {
        final Map<String, QueryFieldBinding> bindings = model.bindings();
        final List<Field<?>> groupFields = groupFields(spec, bindings);

        final Condition dirtyKeys =
                dirtyKeys(renderContext, model, groupFields, groupedFields(spec, filter), filter, windowPredicates);

        final SelectQuery<Record> select = renderContext.selectQuery();
        select.addFrom(model.table());
        // The recompute reads everything the declaration names, so it joins for the widest of the five sets.
        // An enrichment the whole grain reads is restricted to the dirty keys inside its derived table: the
        // outer dirty-key IN applies only after the join, so without this the hash join is built from the
        // whole enrichment on every refresh. It is the same condition the outer WHERE applies, so it selects
        // exactly the enrichment rows the recompute aggregates (see groupEnrichment for when it is exact).
        // Every other join is keyed on the membership filter alone: the bucket span and dirty-key
        // restrictions are source-side sub-selects of their own, and copying them into a key sub-query would
        // run each again for a restriction the filter already carries when it is selective.
        final EnrichmentJoin restricted = groupEnrichment(model,
                spec.getGroupBy().stream().map(key -> Set.of(key.inputColumn())).toList());
        // When that enrichment is aligned, the dirty-key IN is a lookup on it and pre-selects the partitions the
        // dirty keys live in. Exact for the reason the restriction is: an unmatched row reads NULL and never satisfies
        // the IN, so every row recomputed matched an enrichment row of a dirty key, in its own partition. The input
        // takes it; the restricted enrichment and every other aligned join are pruned to it through their tuple joins,
        // and every join without an aligned pair is keyed on it.
        final List<Condition> preselected = restricted == null
                ? List.of()
                : PartitionPreselection.of(restricted, dirtyKeys, List.of()).stream().toList();
        EnrichmentJoins.addReferenced(select, model, referencedFields(spec, filter, measureFilters),
                keyPredicates(model, filter, List.of()), preselected,
                restricted == null ? Map.of() : Map.of(restricted.table().getName(), dirtyKeys));
        select.addConditions(preselected);
        final List<String> targetColumns = new ArrayList<>();

        // Group keys are projected under their target column names and grouped by the same expressions.
        for (int i = 0; i < spec.getGroupBy().size(); i++) {
            final GroupKey key = spec.getGroupBy().get(i);
            select.addSelect(groupFields.get(i).as(key.outputName()));
            targetColumns.add(key.outputName());
        }
        for (final Measure measure : spec.getMeasures()) {
            select.addSelect(measureField(measure, bindings, condition(measureFilters, measure))
                    .as(measure.getName()));
            targetColumns.add(measure.getName());
        }
        select.addSelect(DSL.field("now64(3)"));
        targetColumns.add(UPDATED_AT);

        if (filter.condition() != null) {
            select.addConditions(filter.condition());
        }
        select.addConditions(bucketBound(spec, model, filter, windowPredicates));
        select.addConditions(dirtyKeys);
        select.addGroupBy(groupFields);

        final String columnList = targetColumns.stream()
                .map(column -> renderContext.render(DSL.name(column)))
                .collect(Collectors.joining(", "));
        final String sql = "INSERT INTO " + renderContext.render(DSL.name(Identifiers.require(target)))
                + " (" + columnList + ") " + select.getSQL(ParamType.INLINED);
        return new Compiled(sql, targetColumns);
    }

    /**
     * Alias of the sample's helper column holding each group's latest input row, as epoch milliseconds of the
     * input's own version column. It orders the sample and feeds the {@code enrichment_behind} note, and is
     * stripped before the sample is returned.
     */
    public static final String LATEST_VERSION = "__latest_v";

    /**
     * Alias of the sample's helper column holding the identity of each group's latest input row — the tiebreak
     * half of its cursor tuple, read from the same row as {@link #LATEST_VERSION}; stripped likewise.
     */
    public static final String LATEST_IDENTITY = "__latest_id";

    /** Alias of the sample's helper column counting every group before the {@code LIMIT}; stripped likewise. */
    public static final String TOTAL_GROUPS = "__total_groups";

    /** Every helper alias {@link #compilePreview} adds beside the declaration's own columns — what a caller strips. */
    public static final Set<String> PREVIEW_HELPER_COLUMNS = Set.of(LATEST_VERSION, LATEST_IDENTITY, TOTAL_GROUPS);

    /**
     * The aggregate over the whole input, bounded by {@code limit} — the preview's sample. Deliberately
     * unrestricted by any window: preview answers "what does this declaration produce", which is the
     * question the dry-run gate exists to let an operator check before enabling the pipeline.
     *
     * <p><b>Newest first.</b> Groups are ordered by their latest input row — the maximum of the input's version
     * column over the group, qualified with the input table because a joined enrichment may carry a column of
     * the same name — with the keys ascending as the tiebreak, so two previews of an unchanged input return the
     * same sample. Ordering by key or by age put test traffic and months-old groups in front of the author.
     *
     * <p><b>One statement counts every group.</b> {@code count(*) OVER ()} is evaluated after {@code GROUP BY}
     * and before {@code ORDER BY}/{@code LIMIT}, so it reports the number of rows the target will hold once
     * caught up, in the same pass. It holds the whole grouped result in memory before the sort, and that step
     * does not spill as the grouping may: a group key of far higher cardinality than meant is refused by the
     * preview budget rather than counted, which is the same signal.
     */
    public SelectQuery<Record> compilePreview(AggregateSpec spec, QueryEntityModel model, CursorColumns cursorColumns,
            CompiledFilter filter, Map<String, CompiledFilter> measureFilters, int limit) {
        final Map<String, QueryFieldBinding> bindings = model.bindings();
        final List<Field<?>> groupFields = groupFields(spec, bindings);
        final SelectQuery<Record> select = renderContext.selectQuery();
        select.addFrom(model.table());
        // The same set as the recompute: preview exists to show what a refresh would do, joins included.
        EnrichmentJoins.addReferenced(select, model, referencedFields(spec, filter, measureFilters),
                keyPredicates(model, filter, List.of()));
        for (int i = 0; i < spec.getGroupBy().size(); i++) {
            select.addSelect(groupFields.get(i).as(spec.getGroupBy().get(i).outputName()));
        }
        for (final Measure measure : spec.getMeasures()) {
            select.addSelect(measureField(measure, bindings, condition(measureFilters, measure))
                    .as(measure.getName()));
        }
        final Field<Long> version = CursorPredicates.qualifiedVersionExpr(cursorColumns);
        final Field<String> identity = CursorPredicates.qualifiedIdentityExpr(cursorColumns);
        final Field<Long> latest = DSL.max(version);
        select.addSelect(latest.as(LATEST_VERSION));
        // argMax over the tuple, so the identity comes from the row holding the latest version — the position
        // the enrichment_behind note compares with a bound, which is ordered by the whole tuple.
        select.addSelect(DSL.field("argMax({1}, ({0}, {1}))", String.class, version, identity).as(LATEST_IDENTITY));
        select.addSelect(DSL.field("count(*) OVER ()", Long.class).as(TOTAL_GROUPS));
        if (filter.condition() != null) {
            select.addConditions(filter.condition());
        }
        select.addGroupBy(groupFields);
        select.addOrderBy(latest.desc());
        groupFields.forEach(field -> select.addOrderBy(field.asc()));
        select.addLimit(limit);
        return select;
    }

    /**
     * The declaration as a structured query over its input entity — what the preview returns for a caller to run
     * through the query API, built from the same keys, measures and predicates {@link #compilePreview} compiles.
     *
     * <p>It carries the declaration and none of the sample's mechanics. It orders by the keys rather than by each
     * group's latest row, so two runs agree while the input is still being written, and counts nothing; an
     * enrichment column is named as the entity exposes it and the query path joins for it.
     *
     * <p>A measure's own {@code where} is the same {@code CASE} operand {@link #measureField} compiles, so it narrows
     * that aggregate alone. Each predicate must have been translated with its parsed node at hand: a filter carrying
     * a condition but no node is a caller that cannot have one rendered, and is refused rather than dropped.
     */
    public StructuredQuery portablePreview(AggregateSpec spec, String entity, CompiledFilter filter,
            Map<String, CompiledFilter> measureFilters, int limit) {
        final List<OutputColumn> select = new ArrayList<>();
        final List<String> groupBy = new ArrayList<>();
        final List<SortItem> sort = new ArrayList<>();
        for (final GroupKey key : spec.getGroupBy()) {
            select.add(new OutputColumn(groupExpr(key), key.outputName()));
            // A plain key groups by its field; a bucket by its output, the one name the DSL has for an expression.
            final String grouped = key.isBucket() ? key.outputName() : key.getColumn();
            groupBy.add(grouped);
            sort.add(new SortItem(grouped, SortDir.ASC, null));
        }
        for (final Measure measure : spec.getMeasures()) {
            select.add(new OutputColumn(portableMeasure(measure, measureFilters.get(measure.getName())), measure.getName()));
        }
        return new StructuredQuery(entity, node(filter), QueryMode.AGGREGATE, false, select, groupBy, null, sort,
                new OffsetPage(0L, limit, false));
    }

    private static Expr portableMeasure(Measure measure, CompiledFilter where) {
        final FilterNode predicate = node(where);
        if (predicate == null) {
            final List<Expr> args = StringUtils.isBlank(measure.getColumn())
                    ? List.of()
                    : List.of(new FieldExpr(measure.getColumn()));
            return new FnExpr(measure.getFn(), measure.isDistinct(), args);
        }
        final Expr operand = StringUtils.isBlank(measure.getColumn())
                ? new ValueExpr(ValueType.INTEGER, "1")
                : new FieldExpr(measure.getColumn());
        return new FnExpr(measure.getFn(), measure.isDistinct(),
                List.of(new CaseExpr(List.of(new CaseBranch(predicate, operand)), null)));
    }

    private static FilterNode node(CompiledFilter filter) {
        if (filter == null || filter.condition() == null) {
            return null;
        }
        if (filter.node() == null) {
            throw new IllegalStateException("a predicate reached the portable preview without its parsed form");
        }
        return filter.node();
    }

    /**
     * The pruning bound on the recompute half: the bucketed column confined to the span of the buckets the
     * window actually dirtied. Empty when the declaration has no time bucket.
     *
     * <p><b>Why this exists.</b> The recompute half must see every row of every dirty key, so it cannot
     * carry the window's predicates. Restricted only by the tuple {@code IN} above, it reads the entire
     * input: ClickHouse cannot use a partition or primary-key index against a tuple of <em>expressions</em>
     * tested against a sub-select, so the index has nothing to work with. A rollup refreshed every few
     * minutes would full-scan its input every few minutes.
     *
     * <p>A time bucket makes a usable bound available for free, because it is derived from a column: every
     * row of bucket {@code b} has its bucketed column inside {@code [b, b + one unit)}. So the dirty buckets'
     * own span bounds the rows that can belong to them — on the <em>raw</em> column, which an index can
     * prune on. It narrows nothing: the recompute still aggregates every row of every dirty key, and the
     * numbers are identical with or without it.
     *
     * <p><b>A grain with no time bucket therefore gets no bound at all</b>, and the paragraph above applies
     * to it in full: every refresh reads the whole input. That is a property of the grain, not something
     * missing here — the bound is available only because a bucket <em>implies</em> the range its rows lie
     * in, and a plain column implies nothing. Such a key should be a primary-key prefix of the input, or
     * carry an index that still prunes at the number of keys a window dirties; the capability's spec and
     * {@code docs/configuration.md} carry that guidance for whoever declares the pipeline.
     *
     * <p><b>Do not close the gap with a time predicate.</b> Both candidates look plausible and both silently
     * drop rows, because the recompute must see <em>every</em> row of every dirty key: bounding on the
     * window's own {@code _ingested_at} axis excludes a key's rows ingested before this window, and bounding
     * on the event-time column excludes a key's rows whose events fall outside the window's span. Neither
     * fails — they return understated measures with nothing recording the loss, which is exactly what the
     * "recompute every affected group key in full" contract exists to prevent.
     *
     * <p>Both bounds are <b>scalar sub-queries</b> rather than values fetched by an extra round trip.
     * ClickHouse evaluates an independent scalar sub-query before execution and substitutes the constant, so
     * pruning works the same — and keeping the arithmetic in the engine ({@code + INTERVAL 1 <unit>}) means
     * the step past the last bucket happens in the same time zone and calendar the bucketing itself used.
     * Marshalling the bounds through Java would have had to reproduce that, and a mismatch would silently
     * drop rows from the recompute. That zone is UTC and stays UTC without anything here saying so: the
     * bucket is compiled with an explicit {@code 'UTC'}, which types it {@code DateTime('UTC')}, and the
     * {@code + INTERVAL} inherits it — so the step is a true 24 hours, not a DST-shortened local day.
     *
     * <p>An empty window writes nothing, and the dirty-key set is what guarantees that: its {@code IN} over
     * no rows is false for every row. This bound is not relied on for it and must not be — {@code min}/
     * {@code max} over an empty group return the bucket expression's <em>type default</em> when it is not
     * nullable, so the bound degenerates to a range around the epoch rather than to false. Harmless, because
     * the two are ANDed and the dirty-key half already excludes everything; it is only the reasoning that
     * differs, and reading this as the empty-window guard would put the guarantee in the wrong clause. The
     * refresh path never reaches here on an empty window anyway — the probe returns no window and the write
     * is skipped — so this shape is never executed: the refresh statement {@link
     * com.epam.aidial.analytics.observability.SqlLog} prints at DEBUG always carries a non-empty window, and the
     * degenerate bound shows only when this compiler is called directly over an empty one.
     *
     * @param windowPredicates the same predicates the dirty-key sub-select uses, so the span bounded is the
     *     span that will be recomputed
     */
    private List<Condition> bucketBound(AggregateSpec spec, QueryEntityModel model,
            CompiledFilter filter, List<Condition> windowPredicates) {
        final GroupKey bucket = bucketKey(spec);
        if (bucket == null) {
            return List.of();
        }
        final Field<?> column = exprTranslator.toField(
                new FieldExpr(bucket.getTrunc().getColumn()), model.bindings());
        final Field<?> bucketExpr = exprTranslator.toField(groupExpr(bucket), model.bindings());
        return bucketBound(renderContext, model, column, bucketExpr, bucket.getTrunc().getUnit().intervalKeyword(),
                referencing(filter, bucket.getTrunc().getColumn()), filter, windowPredicates);
    }

    /**
     * {@link #bucketBound(AggregateSpec, QueryEntityModel, CompiledFilter, List)} over already-translated parts —
     * the form shared with {@code AggregateSqlCompiler}, which reads its bucket out of a parsed statement.
     *
     * @param column     the bucketed column, the operand the bound is placed on
     * @param bucketExpr the bucket expression whose dirty extremes bound it
     * @param step       the bucket unit's {@code INTERVAL} keyword; the upper bound steps exactly one of it
     * @param referenced the fields the two bound sub-queries read, so they join what they name and nothing else
     */
    static List<Condition> bucketBound(DSLContext renderContext, QueryEntityModel model, Field<?> column,
            Field<?> bucketExpr, String step, Set<String> referenced, CompiledFilter filter,
            List<Condition> windowPredicates) {
        // The sub-selects are parenthesised explicitly: jOOQ renders a plain SELECT here, so without the
        // parentheses the statement is not valid SQL — and on the upper bound the `+ INTERVAL` would attach
        // to the sub-select's own last predicate instead of to the value it returns.
        // A bound reads the bucketed column and the filter, and nothing else — not the other group keys and
        // not the measures. Joining for those here would put a full enrichment read into two more statements
        // on every tick, for a reference neither of them carries.
        return List.of(
                DSL.condition("{0} >= ({1})", column,
                        dirtyBucketBound(renderContext, model, DSL.min(bucketExpr), filter, windowPredicates, referenced)),
                DSL.condition("{0} < ({1}) + INTERVAL 1 " + step, column,
                        dirtyBucketBound(renderContext, model, DSL.max(bucketExpr), filter, windowPredicates, referenced)));
    }

    /** One end of the dirty buckets' span, as a scalar sub-query over exactly the probed window. */
    private static SelectQuery<Record> dirtyBucketBound(DSLContext renderContext, QueryEntityModel model, Field<?> extreme,
            CompiledFilter filter, List<Condition> windowPredicates, Set<String> referenced) {
        final SelectQuery<Record> bound = renderContext.selectQuery();
        bound.addFrom(model.table());
        EnrichmentJoins.addReferenced(bound, model, referenced, keyPredicates(model, filter, windowPredicates));
        bound.addSelect(extreme);
        if (filter.condition() != null) {
            bound.addConditions(filter.condition());
        }
        bound.addConditions(windowPredicates);
        return bound;
    }

    /** The declaration's single time bucket, or null when it groups by plain columns only. */
    private static GroupKey bucketKey(AggregateSpec spec) {
        return spec.getGroupBy().stream().filter(GroupKey::isBucket).findFirst().orElse(null);
    }

    /**
     * The dirty-key restriction: a tuple {@code IN} over the distinct group keys the window touched. The
     * tuple form is rendered explicitly (as the Class-1 path renders its cursor tuple compare) so the
     * emitted SQL shape is certain rather than dialect-dependent.
     */
    static Condition dirtyKeys(
            DSLContext renderContext,
            QueryEntityModel model,
            List<Field<?>> groupFields,
            Set<String> referenced,
            CompiledFilter filter,
            List<Condition> windowPredicates) {
        return tupleIn(groupFields, dirtyKeySelect(renderContext, model, groupFields, referenced, filter, windowPredicates));
    }

    /**
     * The dirty-key sub-select itself: the distinct group-key tuples of the filtered window, joining the enrichments
     * {@code referenced} names — the group keys' and the filter's.
     */
    static SelectQuery<Record> dirtyKeySelect(
            DSLContext renderContext,
            QueryEntityModel model,
            List<Field<?>> groupFields,
            Set<String> referenced,
            CompiledFilter filter,
            List<Condition> windowPredicates) {
        final SelectQuery<Record> dirty = renderContext.selectQuery();
        dirty.addFrom(model.table());
        // The group keys and the filter, never the measures: this sub-select projects the key tuple only, so it
        // joins exactly the enrichments those read — an enrichment key's join included.
        EnrichmentJoins.addReferenced(dirty, model, referenced, keyPredicates(model, filter, windowPredicates));
        dirty.addSelect(groupFields);
        dirty.setDistinct(true);
        if (filter.condition() != null) {
            dirty.addConditions(filter.condition());
        }
        dirty.addConditions(windowPredicates);
        return dirty;
    }

    /** {@code (<fields>) IN (<keys>)} — one placeholder per field plus the sub-select. */
    static Condition tupleIn(List<Field<?>> fields, SelectQuery<Record> keys) {
        final String tuple = IntStream.range(0, fields.size())
                .mapToObj(i -> "{" + i + "}")
                .collect(Collectors.joining(", "));
        final Object[] parts = new Object[fields.size() + 1];
        for (int i = 0; i < fields.size(); i++) {
            parts[i] = fields.get(i);
        }
        parts[fields.size()] = keys;
        return DSL.condition("(" + tuple + ") IN ({" + fields.size() + "})", parts);
    }

    /**
     * The join of the enrichment whose rows the recompute can restrict to the dirty keys, or null.
     *
     * <p>Only when <em>every</em> group key — a plain column or a time bucket over one — reads a column of
     * that one enrichment. The restriction is then the dirty-key condition itself, and it is exact:
     * <ul>
     *   <li>a row of a dirty key reaches an enrichment row whose key is dirty, which survives;
     *   <li>a row whose enrichment row has another key loses its match and reads NULL, and NULL never
     *       satisfies the outer {@code IN} under the pinned {@code transform_null_in = 0} — so it stays
     *       excluded, as it was.
     * </ul>
     * The second step needs an unmatched row to read NULL. {@code AggregateSpecValidator} refuses a
     * non-nullable enrichment group key on every refresh, except {@code array}, {@code map} and
     * {@code object}, which ClickHouse cannot make nullable: an unmatched one reads its empty default, and
     * if that default were a dirty key, rows of other keys would be counted under it. Those grains are left
     * unrestricted here rather than refused there.
     *
     * <p>A grain mixing source and enrichment keys, or reading two enrichments, is not restricted either:
     * projecting the dirty set onto one enrichment's components is sound, but measured no gain on a
     * low-cardinality component, and no declaration needs it.
     *
     * @param keyReads per group key, the entity fields it reads: one column for a form key, every column an SQL
     *     declaration's key expression reads. A key qualifies only when every one of them is a column of the same
     *     enrichment, since the restriction is evaluated inside that enrichment's derived table.
     */
    static EnrichmentJoin groupEnrichment(QueryEntityModel model, List<Set<String>> keyReads) {
        EnrichmentJoin owner = null;
        for (final Set<String> reads : keyReads) {
            if (reads.isEmpty()) {
                return null;
            }
            for (final String column : reads) {
                final EnrichmentJoin enrichment = keyEnrichment(model, column);
                if (enrichment == null || owner != null && !owner.equals(enrichment)) {
                    return null;
                }
                owner = enrichment;
            }
        }
        return owner;
    }

    /** The keyable enrichment {@code column} belongs to, or null when it is not one a restriction can carry. */
    private static EnrichmentJoin keyEnrichment(QueryEntityModel model, String column) {
        final QueryFieldBinding binding = model.bindings().get(column);
        if (binding == null || binding.type() == QueryFieldType.ARRAY || binding.type() == QueryFieldType.MAP
                || binding.type() == QueryFieldType.OBJECT) {
            return null;
        }
        // A join that does not know its grain-key fields cannot be emitted as a derived table at all;
        // only hand-built models carry one, and they are left unrestricted rather than refused.
        final EnrichmentJoin owner = model.ownerOf(column);
        return owner != null && owner.keyable() ? owner : null;
    }

    /**
     * The source-only conjuncts a statement applies, for {@link EnrichmentJoins} to key its grain joins on:
     * the window predicates (always on the source's cursor columns) plus the membership filter when it
     * names no enrichment column. Any subset of the statement's own conjuncts is a sound key — it selects a
     * superset of the grain keys the statement reads — so a filter that reaches into an enrichment is simply
     * left out rather than disqualifying the window predicates beside it.
     */
    static List<Condition> keyPredicates(QueryEntityModel model, CompiledFilter filter, List<Condition> windowPredicates) {
        final List<Condition> keyed = new ArrayList<>(windowPredicates);
        if (filter != null && filter.condition() != null && EnrichmentJoins.referencesSourceOnly(model, filter.columns())) {
            keyed.add(filter.condition());
        }
        return keyed;
    }

    /**
     * The entity fields the recompute and the preview read: the filter, every group key's input column,
     * every measure's column, and every measure's own {@code where}.
     *
     * <p>Derived per statement rather than once per compile, and this is the widest of the sets. A refresh
     * composes five statements over the same input, each with its own {@code FROM}, and they reference
     * different things: joining an enrichment into the window probe because a <em>measure</em> named it would
     * add a full enrichment read to the cheapest statement of the refresh, on every tick, for nothing.
     */
    public static Set<String> referencedFields(
            AggregateSpec spec, CompiledFilter filter, Map<String, CompiledFilter> measureFilters) {
        final Set<String> referenced = groupedFields(spec, filter);
        for (final Measure measure : spec.getMeasures()) {
            if (StringUtils.isNotBlank(measure.getColumn())) {
                referenced.add(measure.getColumn());
            }
            final CompiledFilter where = measureFilters.get(measure.getName());
            if (where != null) {
                referenced.addAll(where.columns());
            }
        }
        return referenced;
    }

    /** The fields the dirty-key sub-select reads: the filter, plus each group key's input column. */
    private static Set<String> groupedFields(AggregateSpec spec, CompiledFilter filter) {
        final Set<String> referenced = new LinkedHashSet<>(filter.columns());
        for (final GroupKey key : spec.getGroupBy()) {
            referenced.add(key.inputColumn());
        }
        return referenced;
    }

    /** The filter's fields plus one named column. */
    private static Set<String> referencing(CompiledFilter filter, String column) {
        final Set<String> referenced = new LinkedHashSet<>(filter.columns());
        referenced.add(column);
        return referenced;
    }

    /** One measure's translated {@code where}, or null when it declares none. */
    private static Condition condition(Map<String, CompiledFilter> measureFilters, Measure measure) {
        final CompiledFilter where = measureFilters.get(measure.getName());
        return where == null ? null : where.condition();
    }

    /** The group-by expressions, in declared order: a plain column, or a {@code date_trunc} time bucket. */
    private List<Field<?>> groupFields(AggregateSpec spec, Map<String, QueryFieldBinding> bindings) {
        final List<Field<?>> fields = new ArrayList<>(spec.getGroupBy().size());
        for (final GroupKey key : spec.getGroupBy()) {
            fields.add(exprTranslator.toField(groupExpr(key), bindings));
        }
        return fields;
    }

    private Expr groupExpr(GroupKey key) {
        if (!key.isBucket()) {
            return new FieldExpr(key.getColumn());
        }
        // date_trunc(<unit>, <column>) — one bucket of the declared unit. Alignment is the engine's
        // toStartOfInterval, pinned to UTC: epoch-aligned for hour/day, Monday-aligned for week, calendar
        // for month (see TruncUnit), and never dependent on the column's or the server's zone.
        //
        // Every unit goes through date_trunc, not just the calendar one. On the fixed-width units this
        // renders exactly what date_bin(1, <unit>, col) rendered — the same
        // toStartOfInterval(col, INTERVAL 1 <unit>, 'UTC') — so no existing rollup, cursor or bucket
        // boundary moves; it just drops a synthetic amount-of-1 literal and a branch on the unit.
        return new FnExpr("date_trunc", false, List.of(
                new ValueExpr(ValueType.STRING, key.getTrunc().getUnit().code()),
                new FieldExpr(key.getTrunc().getColumn())));
    }

    /**
     * One measure as an aggregate field. Without a {@code where} this is the plain catalog call; with one,
     * the operand is wrapped in a {@code CASE} so the aggregate sees a value only on the matching rows.
     *
     * <p><b>Why {@code CASE} and not a {@code FILTER} clause.</b> ClickHouse has no SQL-standard
     * {@code FILTER (WHERE …)}; its native form is the {@code -If} combinator ({@code sumIf}), and jOOQ's
     * ClickHouse dialect is experimental enough that relying on it to emulate {@code filterWhere} would be
     * betting the write path on an emulation detail. {@code CASE} is also the form that generalises: it
     * composes with {@code group_uniq_array}, which is rendered as plain SQL here rather than as a jOOQ
     * {@code AggregateFunction} and so has no {@code filterWhere} to call.
     *
     * <p>The semantics come out right because every aggregate in the catalog's aggregate group skips NULLs:
     * a non-matching row contributes the {@code CASE}'s implicit NULL and is ignored, which is exactly
     * {@code sumIf}. It also gives the honest answer when a group matches nothing — {@code CASE} yields NULL
     * for every row, so {@code sum}/{@code min}/{@code max}/{@code avg} return NULL rather than the
     * {@code 0} that {@code sumIf} would report, and {@code AggregateSpecValidator} requires a nullable
     * target so that NULL survives the insert.
     *
     * <p>A count with no column becomes {@code count(CASE WHEN … THEN 1 END)} — the row count of the
     * matching rows, since {@code count(x)} counts non-null values. The literal {@code 1} is the operand
     * because there is no column to stand in for one.
     *
     * <p><b>A {@code distinct} measure composes with either path unchanged.</b> Without a {@code where} the
     * quantifier rides on the {@code FnExpr}; with one it is passed alongside the prepared operand, giving
     * {@code count(DISTINCT CASE WHEN <predicate> THEN <column> END)}. That needs no special case for the
     * same reason the {@code CASE} works at all: every catalog aggregate skips NULLs, and
     * {@code count(DISTINCT x)} counts distinct <em>non-NULL</em> values, so a non-matching row contributes
     * the {@code CASE}'s implicit NULL and is ignored. A group matching nothing is then all-NULL and counts
     * {@code 0} — the same honest answer a scoped plain {@code count} gives, which is why a scoped distinct
     * count needs no nullable target column either. {@code distinct} cannot reach a column-less
     * {@code count}: {@code AggregateSpecValidator} requires a column for it, so the {@code DSL.inline(1)}
     * operand above is never deduplicated.
     */
    private Field<?> measureField(Measure measure, Map<String, QueryFieldBinding> bindings, Condition where) {
        if (where == null) {
            final List<Expr> args = StringUtils.isBlank(measure.getColumn())
                    ? List.of()
                    : List.of(new FieldExpr(measure.getColumn()));
            return exprTranslator.toField(new FnExpr(measure.getFn(), measure.isDistinct(), args), bindings);
        }
        final Field<?> operand = StringUtils.isBlank(measure.getColumn())
                ? DSL.inline(1)
                : exprTranslator.resolveField(measure.getColumn(), bindings);
        return exprTranslator.toAggregate(measure.getFn(), DSL.when(where, operand), measure.isDistinct());
    }
}
