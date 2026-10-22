package com.epam.aidial.analytics.service.query;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.JoinType;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.SelectQuery;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits a source's grain joins into one statement: a LEFT JOIN per enrichment whose exposed columns that
 * statement actually references.
 *
 * <p>Shared by the read path and the aggregate pipeline path rather than implemented twice. The two derive
 * the referenced set differently — a query reads it from its clauses, a refresh derives one per statement it
 * composes — but <em>which</em> enrichment a statement joins and <em>how</em> has a single answer, and a
 * second copy of it is how a rollup and a query over the same entity would start disagreeing about what a
 * column means.
 *
 * <p>Opt-in by reference, not by presence: an enrichment nothing names is not joined, so a statement never
 * pays to read a table it does not select from. A single-key 0..1 LEFT JOIN also never inflates the source
 * row count, which is what lets a {@code count(*)} and an aggregate carry it safely.
 *
 * <p><b>Keyed, not merely joined.</b> ClickHouse executes a {@code LEFT JOIN} as a hash join built from the
 * <em>right</em> side, and nothing in the source's {@code WHERE} narrows that build: a point lookup on
 * {@code trace_id} that names one payload column would hash every in-window payload row — bodies included —
 * to find one. So when the caller can hand over the predicates that confine the <em>source</em>, the
 * enrichment is emitted as a derived table restricted to the grain keys those predicates select:
 * <pre>
 * LEFT JOIN (SELECT grain_key, &lt;referenced columns&gt; FROM enrichment
 *            WHERE enrichment.grain_key IN (SELECT source.grain_key FROM source WHERE &lt;predicates&gt;))
 *        AS enrichment ON source.grain_key = enrichment.grain_key
 * </pre>
 * The derived table keeps the enrichment's own name, so every reference the statement already renders
 * ({@code "enrichment"."column"}, the join condition) resolves unchanged. The predicates MUST reference the
 * source alone — a predicate naming an enrichment column would need that very join inside the key
 * subquery — and each caller establishes that from the referenced-field set it already computes, conjunct
 * by conjunct: any subset of the statement's own conjuncts is a sound key, so a conjunct reaching into an
 * enrichment is left out rather than disqualifying the source conjuncts beside it. With no
 * predicates and no restriction (below), or for a join that does not know its key fields, the bare
 * {@code LEFT JOIN} is emitted as before: a statement with no source restriction has no key set to offer,
 * and the hash join over the whole enrichment is then the honest cost of what it asked for.
 *
 * <p><b>Keyed, and partition-bounded where the catalog says it can be.</b> Restricting the enrichment to a
 * grain key bounds what the join matches, which is not the same as bounding what it reads. Where the
 * enrichment's grain key is uncorrelated with its partitioning — {@code dial_usage_log_payload} is keyed on
 * a hashed {@code event_id} and partitioned by day — a lookup for one key value lands a candidate granule in
 * every part and reads all of them, because a sorted random identifier gives the primary-key search no way
 * to exclude a part. The grain-key set does not help: the row genuinely might be in any of them. That can be
 * on the order of a gigabyte per heavy column to return a single row, on <em>every</em> path that reads a
 * body, and a single-column-at-a-time read is what keeps it under a 2 GiB ceiling.
 *
 * <p>What excludes the other partitions is a predicate on the partition key, and the statement generally has
 * the value already — the same tuple that identifies the row usually pins its time. So when the join carries
 * an aligned partition pair ({@link EnrichmentJoin#partitionAligned()}), the derived table gains a second
 * restriction, in the same shape as the first:
 * <pre>
 * WHERE enrichment.grain_key      IN (SELECT source.grain_key      FROM source WHERE &lt;predicates&gt;)
 *   AND enrichment.partition_col  IN (SELECT source.partition_col  FROM source WHERE &lt;predicates&gt;)
 * </pre>
 * One extra subquery, deliberately the same {@code IN}-set shape rather than a {@code min}/{@code max}
 * bounding box: an exact set prunes rows as well as parts, costs one evaluation of the source predicates
 * instead of two, and is the canonical form ClickHouse prunes partitions from. It is also the form whose
 * correctness needs no argument about monotonicity — a partition either holds one of the selected values or
 * it does not.
 *
 * <p>This restriction is <em>only</em> as sound as the alignment invariant behind it, which is why nothing
 * here infers it. {@link EnrichmentJoin} states it: both fields are non-null only when the catalog claims
 * the two columns hold the same value for the same grain key. A wrong claim does not fail loudly — it
 * restricts the enrichment to partitions that cannot hold the row, and the LEFT JOIN then yields the
 * column's type default, which reads exactly like a row that legitimately has no enrichment.
 *
 * <p><b>Bounded instead of keyed when the predicates are partition bounds alone.</b> Both sets are built in full
 * before the scan, and over a window they hold every value the window selects (millions of values over
 * a month of history): the grain-key set prunes nothing on a table sorted by a random key, and the partition set restates a
 * range. So the read path may hand over, per aligned enrichment, the statement's bounds on the source partition column
 * rendered on the enrichment's alignment column, when those bounds are the whole of the source predicates; the derived
 * table is then restricted by them in place of both sets, everything else about it unchanged:
 * <pre>
 * WHERE enrichment.partition_col &gt;= T          -- for a source predicate source.partition_col &gt;= T
 * </pre>
 * Sound under the same invariant as the partition set: an admitted source row's enrichment row carries the same value.
 * The caller decides, because only it holds the filter tree that says what each predicate reads.
 *
 * <p><b>Keyed on a partition pre-selection where the tuple join cannot carry it.</b> A caller may also hand over
 * source-only conjuncts that key only the joins <em>without</em> an aligned partition pair: a partition
 * pre-selection ({@link PartitionPreselection}) restricts the source to a few partitions, and ClickHouse carries
 * that restriction into an aligned join itself, through the {@code source.p = enrichment.p} its condition compares —
 * keying it as well would only evaluate the pre-selection again, twice per join. A join on the grain key alone has
 * no such equality, and unkeyed it would hash the whole enrichment; keyed on the pre-selection it reads the grain
 * keys of the selected partitions.
 *
 * <p><b>Restricted by the enrichment's own columns.</b> A caller may instead hand over a restriction for a
 * given enrichment: a condition over that enrichment's columns alone, which is then the derived table's
 * <em>only</em> {@code WHERE} conjunct:
 * <pre>
 * LEFT JOIN (SELECT grain_key, &lt;referenced columns&gt; FROM enrichment WHERE &lt;restriction&gt;)
 *        AS enrichment ON source.grain_key = enrichment.grain_key
 * </pre>
 * This is not keying. Neither the grain-key nor the partition restriction is added beside it, with or
 * without source predicates: both are drawn from source predicates, and the restriction's caller vouches
 * that it already selects a superset of the enrichment rows the statement reads. The aggregate recompute
 * is the one caller — it restricts the enrichment its grain reads to the window's dirty keys, a set no
 * source-only predicate can express, because which source rows belong to a dirty enrichment key is known
 * only after the join. A keyed restriction beside it would only add a set the window does not bound. The
 * read path passes none.
 *
 * <p><b>Shaped by the statement's own read.</b> The single-entity read path may hand over a {@link Shaping} per
 * enrichment (the {@code structured-query} spec's late-materialization, conjunct-pushdown and call-pushdown
 * requirements decide when):
 * <ul>
 *   <li>{@linkplain Shaping#conjuncts() conjuncts} over the enrichment's own columns, each false on an unmatched
 *       row, are ANDed into its derived table beside the keys, so a row the statement's {@code WHERE} rejects
 *       never reaches the hash build. The statement keeps them in its own {@code WHERE} as well;
 *   <li>{@linkplain Shaping#computed() computed} columns — calls over a heavy column, aliased — are projected in
 *       place of the {@linkplain Shaping#omitted() omitted} raw columns, so the build holds the call's light
 *       result instead of the column it reads;
 *   <li>{@linkplain Shaping#pageKeys() page keys} replace keying altogether: the enrichment table itself is
 *       joined, with {@code (grain_key, partition_col) IN (<page keys>)} added to the join condition. Not inside
 *       a derived table, deliberately: ClickHouse evaluates a subquery once per scope, and the statement's
 *       {@code WHERE} names the same page-key subquery, so placing both in the outer scope is what makes the page
 *       the join reads the page the statement returns.
 * </ul>
 * An enrichment with conjuncts or computed columns but no source predicates is emitted as a derived table all
 * the same, restricted only by its conjuncts (or by nothing) — it is the projection, not a key set, that the
 * shaping exists for.
 */
public final class EnrichmentJoins {

    /**
     * How the read path shapes one enrichment's join (see the class note). Every member is optional; {@link #NONE}
     * leaves the join exactly as the unshaped overloads emit it.
     *
     * @param conjuncts conditions over this enrichment's columns alone, each false on an unmatched row, added to
     *     its derived table's {@code WHERE}
     * @param computed  aliased expressions over this enrichment's columns to project in its derived table
     * @param omitted   exposed names the derived table must not project although the statement references them
     *     (each is read only through a {@code computed} column)
     * @param pageKeys  the page-key subquery — one column, or two when the join is partition-aligned — that
     *     restricts the bare enrichment through the join condition instead of keying a derived table; {@code null}
     *     for none
     */
    public record Shaping(List<Condition> conjuncts, List<Field<?>> computed, Set<String> omitted, Select<?> pageKeys) {

        /** No shaping. */
        public static final Shaping NONE = new Shaping(List.of(), List.of(), Set.of(), null);

        public Shaping {
            conjuncts = List.copyOf(conjuncts);
            computed = List.copyOf(computed);
            omitted = Set.copyOf(omitted);
        }

        /** Whether this shaping asks for a derived table even with no key predicates. */
        boolean narrows() {
            return !conjuncts.isEmpty() || !computed.isEmpty();
        }
    }

    private EnrichmentJoins() {
    }

    /** Joins each enrichment of {@code model} that contributes a name in {@code referenced}, unkeyed. */
    public static void addReferenced(
            SelectQuery<Record> select, QueryEntityModel model, Collection<String> referenced) {
        addReferenced(select, model, referenced, List.of());
    }

    /**
     * Joins each enrichment of {@code model} that contributes a name in {@code referenced}, keyed on
     * {@code sourcePredicates} when there are any (see the class note).
     *
     * @param sourcePredicates conjuncts that confine the source rows the statement reads and that reference
     *     source columns only; empty for an unkeyed join
     */
    public static void addReferenced(
            SelectQuery<Record> select,
            QueryEntityModel model,
            Collection<String> referenced,
            Collection<? extends Condition> sourcePredicates) {
        addReferenced(select, model, referenced, sourcePredicates, ObjectPathUsage.none(), false);
    }

    /**
     * As {@link #addReferenced(SelectQuery, QueryEntityModel, Collection, Collection)}, rendering every
     * joined enrichment under ClickHouse's {@code FINAL} modifier when {@code finalRead} — a user-facing read
     * of an entity that is read final deduplicates its enrichments with its source (see
     * {@code FinalDecision}). The key sub-query that keys a join reads the source raw either way: the latest
     * version of a key is one of its raw versions, so the raw key set is a superset of the final one and
     * keying still never changes which rows the statement returns.
     */
    public static void addReferenced(
            SelectQuery<Record> select,
            QueryEntityModel model,
            Collection<String> referenced,
            Collection<? extends Condition> sourcePredicates,
            boolean finalRead) {
        addReferenced(select, model, referenced, sourcePredicates, ObjectPathUsage.none(), finalRead);
    }

    /**
     * As {@link #addReferenced(SelectQuery, QueryEntityModel, Collection, Collection)}, pruning each
     * {@code object} column that {@code usage} says this statement addresses only at literal paths down to
     * those paths' subcolumns.
     *
     * <p><b>The returned model is the one the statement's expressions must render against.</b> A pruned
     * path is addressed through an alias that exists only inside the derived table this method emitted, so
     * a caller that prunes the join and then renders against the model it passed in would produce a
     * statement naming a column nothing projects. Returning the effective model rather than mutating the
     * argument keeps that coupling impossible to get half-right: there is no pruned join without a model
     * that knows it.
     *
     * <p>Pruning is applied only where the join is actually emitted as a derived table — keyed or
     * restricted, on a keyable enrichment. An unkeyed join is the bare enrichment table, which has no alias to address, so
     * its columns stay whole however this statement reads them.
     *
     * @return {@code model} with the pruned columns' bindings stamped, or {@code model} itself when
     *     nothing was pruned
     */
    public static QueryEntityModel addReferenced(
            SelectQuery<Record> select,
            QueryEntityModel model,
            Collection<String> referenced,
            Collection<? extends Condition> sourcePredicates,
            ObjectPathUsage usage) {
        return addReferenced(select, model, referenced, sourcePredicates, usage, false);
    }

    /**
     * As {@link #addReferenced(SelectQuery, QueryEntityModel, Collection, Collection, ObjectPathUsage)},
     * rendering the enrichment tables under {@code FINAL} when {@code finalRead}. Pipeline reads pass
     * {@code false}: they deduplicate through the query-level {@code final = 1} setting instead.
     */
    public static QueryEntityModel addReferenced(
            SelectQuery<Record> select,
            QueryEntityModel model,
            Collection<String> referenced,
            Collection<? extends Condition> sourcePredicates,
            ObjectPathUsage usage,
            boolean finalRead) {
        return addReferenced(select, model, referenced, sourcePredicates, Map.of(), usage, finalRead);
    }

    /**
     * As {@link #addReferenced(SelectQuery, QueryEntityModel, Collection, Collection)}, restricting each
     * enrichment named in {@code restrictions} by its own columns instead of keying it (see the class note).
     * The pipeline-facing form: whole columns, no {@code FINAL} modifier.
     *
     * @param restrictions a condition over one enrichment's columns alone, by that enrichment's table name;
     *     each must select a superset of the enrichment rows the statement reads
     */
    public static void addReferenced(
            SelectQuery<Record> select,
            QueryEntityModel model,
            Collection<String> referenced,
            Collection<? extends Condition> sourcePredicates,
            Map<String, ? extends Condition> restrictions) {
        addReferenced(select, model, referenced, sourcePredicates, List.of(), restrictions);
    }

    /**
     * As {@link #addReferenced(SelectQuery, QueryEntityModel, Collection, Collection, Map)}, keying each join that has
     * no aligned partition pair on {@code unalignedPredicates} as well (see the class note).
     */
    public static void addReferenced(
            SelectQuery<Record> select,
            QueryEntityModel model,
            Collection<String> referenced,
            Collection<? extends Condition> sourcePredicates,
            Collection<? extends Condition> unalignedPredicates,
            Map<String, ? extends Condition> restrictions) {
        addReferenced(select, model, referenced, sourcePredicates, unalignedPredicates, restrictions, Map.of(),
                ObjectPathUsage.none(), false);
    }

    /**
     * The general form every overload delegates to.
     *
     * @throws IllegalStateException when a restriction names an enrichment that is not {@code keyable()}: its
     *     derived table could not project the grain key the join condition compares, and silently emitting
     *     the bare join instead would hash the whole enrichment the caller asked to restrict
     */
    public static QueryEntityModel addReferenced(
            SelectQuery<Record> select,
            QueryEntityModel model,
            Collection<String> referenced,
            Collection<? extends Condition> sourcePredicates,
            Map<String, ? extends Condition> restrictions,
            ObjectPathUsage usage,
            boolean finalRead) {
        return addReferenced(select, model, referenced, sourcePredicates, List.of(), restrictions, Map.of(), usage,
                finalRead);
    }

    /**
     * As {@link #addReferenced(SelectQuery, QueryEntityModel, Collection, Collection, Map, ObjectPathUsage,
     * boolean)}, shaping each enrichment named in {@code shaping} (see the class note). Only the single-entity read
     * path passes a shaping, and never together with a restriction.
     *
     * @param unalignedPredicates further source-only conjuncts of the statement that key only the joins without an
     *     aligned partition pair, beside {@code sourcePredicates} — a partition pre-selection, which reaches an aligned
     *     join through its tuple join condition instead (see {@link PartitionPreselection})
     * @throws IllegalStateException when a shaping names an enrichment that is not {@code keyable()}, or one that
     *     also carries a restriction
     */
    public static QueryEntityModel addReferenced(
            SelectQuery<Record> select,
            QueryEntityModel model,
            Collection<String> referenced,
            Collection<? extends Condition> sourcePredicates,
            Collection<? extends Condition> unalignedPredicates,
            Map<String, ? extends Condition> restrictions,
            Map<String, Shaping> shaping,
            ObjectPathUsage usage,
            boolean finalRead) {
        return addReferenced(select, model, referenced, sourcePredicates, unalignedPredicates, restrictions, shaping, Map.of(),
                usage, finalRead);
    }

    /**
     * As {@link #addReferenced(SelectQuery, QueryEntityModel, Collection, Collection, Collection, Map, Map,
     * ObjectPathUsage, boolean)}, restricting each keyed enrichment named in {@code transferred} by the conditions
     * given for it <em>in place of</em> its key sets (see the class note). Only the read path passes any.
     *
     * @param transferred by enrichment table name, the source's partition-column bounds rendered on that enrichment's
     *     alignment column; the caller vouches that they are the whole of {@code sourcePredicates}, so they select
     *     every enrichment row a source row the statement admits can match
     */
    public static QueryEntityModel addReferenced(
            SelectQuery<Record> select,
            QueryEntityModel model,
            Collection<String> referenced,
            Collection<? extends Condition> sourcePredicates,
            Collection<? extends Condition> unalignedPredicates,
            Map<String, ? extends Condition> restrictions,
            Map<String, Shaping> shaping,
            Map<String, List<Condition>> transferred,
            ObjectPathUsage usage,
            boolean finalRead) {
        final List<Condition> predicates = withoutNulls(sourcePredicates);
        final List<Condition> unaligned = withoutNulls(unalignedPredicates);
        final Map<String, Set<List<String>>> pruned = new LinkedHashMap<>();
        for (final EnrichmentJoin enrichment : model.enrichments()) {
            if (Collections.disjoint(referenced, enrichment.exposedNames())) {
                continue;
            }
            final String name = enrichment.table().getName();
            final Condition restriction = restrictions.get(name);
            if (restriction != null && !enrichment.keyable()) {
                throw new IllegalStateException("Enrichment '" + name
                        + "' cannot carry a restriction: its join does not know its grain-key fields");
            }
            final Shaping shape = shaping.getOrDefault(name, Shaping.NONE);
            if (shape != Shaping.NONE && (restriction != null || !enrichment.keyable())) {
                throw new IllegalStateException("Enrichment '" + name
                        + "' cannot be shaped: it is restricted, or its join does not know its grain-key fields");
            }
            if (shape.pageKeys() != null) {
                select.addJoin(FinalTables.of(enrichment.table(), finalRead), JoinType.LEFT_OUTER_JOIN,
                        enrichment.condition().and(pageKeyed(enrichment, shape.pageKeys())));
                continue;
            }
            final List<Condition> keyedOn = new ArrayList<>(predicates);
            if (!enrichment.partitionAligned()) {
                keyedOn.addAll(unaligned);
            }
            final Table<?> right;
            if (restriction != null) {
                right = derived(model, enrichment, referenced, List.of(restriction), Shaping.NONE, usage, pruned,
                        finalRead);
            } else if (!enrichment.keyable() || keyedOn.isEmpty() && !shape.narrows()) {
                right = FinalTables.of(enrichment.table(), finalRead);
            } else {
                final List<Condition> where = new ArrayList<>();
                final List<Condition> bounds = enrichment.partitionAligned() ? transferred.get(name) : null;
                if (bounds != null && !bounds.isEmpty() && !predicates.isEmpty()) {
                    where.addAll(bounds);
                } else if (!keyedOn.isEmpty()) {
                    where.addAll(keys(model, enrichment, keyedOn));
                }
                where.addAll(shape.conjuncts());
                right = derived(model, enrichment, referenced, where, shape, usage, pruned, finalRead);
            }
            select.addJoin(right, JoinType.LEFT_OUTER_JOIN, enrichment.condition());
        }
        return model.withPrunedPaths(pruned);
    }

    /**
     * {@code (enrichment.grain_key, enrichment.partition_col) IN (<page keys>)} — or the grain key alone when the
     * join is not partition-aligned — the restriction a late-materialized join carries in its condition.
     */
    private static Condition pageKeyed(EnrichmentJoin enrichment, Select<?> pageKeys) {
        if (enrichment.partitionAligned()) {
            return DSL.condition("({0}, {1}) IN ({2})",
                    enrichment.enrichmentKey(), enrichment.enrichmentPartition(), pageKeys);
        }
        return DSL.condition("{0} IN ({1})", enrichment.enrichmentKey(), pageKeys);
    }

    /**
     * The keying restrictions for {@code predicates}: the grain keys they select from the source — and, when
     * the join is partition-aligned, those rows' partition values too.
     *
     * <p>The partition restriction is added to the derived table's {@code WHERE} rather than to the outer
     * join condition. Both prune, but only the inner form also narrows what the derived table projects, and
     * projecting a heavy column is the cost being avoided; putting it on the join condition would prune the
     * scan while still building the join from an unrestricted projection.
     */
    private static List<Condition> keys(QueryEntityModel model, EnrichmentJoin enrichment, List<Condition> predicates) {
        final List<Condition> restrictions = new ArrayList<>(2);
        restrictions.add(selects(model, enrichment.enrichmentKey(), enrichment.sourceKey(), predicates));
        if (enrichment.partitionAligned()) {
            restrictions.add(selects(
                    model, enrichment.enrichmentPartition(), enrichment.sourcePartition(), predicates));
        }
        return restrictions;
    }

    /**
     * The enrichment as a derived table holding only the grain key, the columns {@code referenced} names (less
     * those {@code shape} omits) and the columns {@code shape} computes, for the rows {@code restrictions} select,
     * aliased to the enrichment's own name.
     */
    private static Table<?> derived(
            QueryEntityModel model,
            EnrichmentJoin enrichment,
            Collection<String> referenced,
            List<Condition> restrictions,
            Shaping shape,
            ObjectPathUsage usage,
            Map<String, Set<List<String>>> pruned,
            boolean finalRead) {
        final List<Field<?>> projection = new ArrayList<>();
        projection.add(enrichment.enrichmentKey());
        if (enrichment.partitionAligned()) {
            // The join condition compares the partition pair, so the derived table projects its half.
            projection.add(enrichment.enrichmentPartition());
        }
        projection.addAll(shape.computed());
        for (final String name : enrichment.exposedNames()) {
            final QueryFieldBinding binding = model.bindings().get(name);
            if (!referenced.contains(name) || binding == null || shape.omitted().contains(name)) {
                continue;
            }
            final Set<List<String>> paths = usage.prunedPaths(name);
            if (paths.isEmpty()) {
                // An adopted alignment column is exposed as well as compared; it is projected once, above.
                if (!enrichment.partitionAligned() || !binding.field().getUnqualifiedName().last()
                        .equals(enrichment.enrichmentPartition().getUnqualifiedName().last())) {
                    projection.add(binding.field());
                }
                continue;
            }
            // One field per addressed path, each aliased to the dotted path as a single identifier, so the
            // outer statement's reference stays two-part. See ObjectPathUsage#alias.
            final String column = binding.field().getUnqualifiedName().last();
            for (final List<String> path : paths) {
                projection.add(ObjectPathUsage.subcolumn(binding.field(), path).as(ObjectPathUsage.alias(column, path)));
            }
            pruned.put(name, paths);
        }
        return DSL.select(projection)
                .from(FinalTables.of(enrichment.table(), finalRead))
                .where(restrictions)
                .asTable(enrichment.table().getName());
    }

    /**
     * {@code enrichmentColumn IN (SELECT sourceColumn FROM source WHERE predicates)} — the one keying shape
     * this class emits, for the grain key and for an aligned partition column alike. A caller's own
     * restriction (see the class note) is emitted as given.
     */
    private static Condition selects(
            QueryEntityModel model, Field<?> enrichmentColumn, Field<?> sourceColumn, List<Condition> predicates) {
        final Select<?> values = DSL.select(sourceColumn).from(model.table()).where(predicates);
        return DSL.condition("{0} IN ({1})", enrichmentColumn, values);
    }

    private static List<Condition> withoutNulls(Collection<? extends Condition> conditions) {
        final List<Condition> kept = new ArrayList<>();
        if (conditions != null) {
            for (final Condition condition : conditions) {
                if (condition != null) {
                    kept.add(condition);
                }
            }
        }
        return kept;
    }

    /**
     * Whether {@code referenced} names no column of any enrichment of {@code model} — the condition under
     * which a predicate set over those names can key the grain joins. Callers use it to decide whether the
     * statement's own filter may travel into the key subquery.
     */
    public static boolean referencesSourceOnly(QueryEntityModel model, Collection<String> referenced) {
        for (final EnrichmentJoin enrichment : model.enrichments()) {
            if (!Collections.disjoint(referenced, enrichment.exposedNames())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The enrichments {@link #addReferenced} would join for the same {@code referenced} set, by table name.
     *
     * <p>Exists so a caller that needs to <em>reason</em> about the joined enrichments rather than emit them
     * asks the same question the emitter answers. The aggregate refresh needs exactly this: it clamps its
     * window to the materialized position of every enrichment its statements join, and deriving that set by
     * re-parsing the declaration would let the clamp cover a different set than the SQL actually reads —
     * the one divergence that turns a safety mechanism into a false assurance.
     *
     * <p>The name comes from the join's own table rather than a stored field, because
     * {@code CatalogQueryService} builds it as {@code DSL.table(DSL.name(enrichment.getName()))} and jOOQ
     * hands that name straight back.
     */
    public static Set<String> referencedEnrichments(QueryEntityModel model, Collection<String> referenced) {
        final Set<String> joined = new LinkedHashSet<>();
        for (final EnrichmentJoin enrichment : model.enrichments()) {
            if (!Collections.disjoint(referenced, enrichment.exposedNames())) {
                joined.add(enrichment.table().getName());
            }
        }
        return joined;
    }
}
