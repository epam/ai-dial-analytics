package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.service.table.PartitionGranularity;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.Set;

/**
 * A materialized enrichment available to a source's flat query schema: the enrichment {@code table},
 * the {@code condition} to LEFT JOIN it to the source ({@code source.grain_key = enrichment.grain_key}),
 * the two grain-key fields that condition compares, and the set of EXPOSED field names it contributes.
 * The join is emitted only when a query references one of {@code exposedNames}.
 *
 * <p>{@code sourceKey} and {@code enrichmentKey} are carried separately from {@code condition} — which is
 * their equality — so {@link EnrichmentJoins} can build a <em>keyed</em> join: when the statement's own
 * predicates confine the source, the enrichment side is restricted to the grain keys those predicates
 * select ({@code enrichment.grain_key IN (SELECT source.grain_key FROM source WHERE …)}) instead of being
 * hashed whole. A ClickHouse hash join builds its table from the right side, so without that restriction
 * a point lookup naming one payload column reads every in-window payload row.
 *
 * <p><b>The partition pair, and why keying alone is not enough.</b> Restricting the enrichment to a grain
 * key bounds what the join <em>matches</em>; it does not always bound what the read <em>costs</em>. An
 * enrichment keyed on a high-cardinality identifier that is uncorrelated with its partitioning — a hashed
 * event id, say — has that identifier spread across every partition, so a primary-key lookup for one value
 * lands a candidate granule in every part of the table and reads all of them to return one row: on the
 * order of a gigabyte per heavy column, for a single-row result, keyed or not.
 *
 * <p>What removes that cost is pruning partitions, and the statement usually already holds the value needed
 * to do it — a predicate on the source's own partition column, in the same tuple that identifies the row.
 * {@code sourcePartition} and {@code enrichmentPartition} are that value's two homes: the source column a
 * statement can predicate on, and the enrichment column carrying the same value for the same grain key.
 * Given both, {@link EnrichmentJoins} bounds the enrichment side by the partition values the source
 * predicates select, and the read prunes to the partitions that can hold the row.
 *
 * <p><b>The alignment is an invariant, not an inference.</b> Both fields are non-null only when the catalog
 * says the enrichment's partition column mirrors its source's — that for a given grain key the two columns
 * hold the same value. Nothing in a LEFT JOIN checks it: a wrong claim silently restricts the enrichment to
 * partitions that do not hold the row, and the row then reads as the column's type default rather than as
 * its value, which is indistinguishable from a legitimately unmatched row. So the claim is carried by the
 * catalog for tables whose population guarantees it, never derived from two columns happening to share a
 * name at query time.
 *
 * @param sourceKey the source's grain-key column, two-part qualified ({@code source.grain_key}); null when
 *     the join was built from a bare condition, in which case it is always emitted unkeyed (see {@link #keyed})
 * @param enrichmentKey the enrichment's grain-key column, two-part qualified ({@code enrichment.grain_key});
 *     null under the same circumstances as {@code sourceKey}
 * @param sourcePartition the source's partition column, two-part qualified; null when either table declares
 *     no partitioning or the catalog claims no alignment between them
 * @param enrichmentPartition the enrichment's partition column, two-part qualified, holding the same value
 *     as {@code sourcePartition} for the same grain key; null under the same circumstances
 * @param partitionGranularity the granularity the source partitions the aligned column by; null when the join is
 *     not aligned, or was built by hand without it
 */
public record EnrichmentJoin(
        Table<?> table,
        Condition condition,
        Field<?> sourceKey,
        Field<?> enrichmentKey,
        Field<?> sourcePartition,
        Field<?> enrichmentPartition,
        Set<String> exposedNames,
        PartitionGranularity partitionGranularity) {

    /**
     * The join for {@code table} keyed on {@code sourceKey = enrichmentKey} and optionally partition-aligned; the
     * form the catalog builds. Pass null for both partition fields and the granularity for a join with no
     * partition alignment.
     *
     * <p>An aligned join compares the partition pair too: {@code sourceKey = enrichmentKey AND sourcePartition =
     * enrichmentPartition}. An aligned enrichment row is written from one source row and carries that row's
     * partition value, so the tuple is its key: two source rows sharing a grain key across partitions each match
     * the row written from them rather than both matching either, and a row carrying a wrong partition value
     * matches nothing instead of attaching to another source row.
     *
     * <p>A join on an opaque condition, with no grain-key fields to key a restriction on, is built with the
     * canonical constructor and nulls; it is emitted as a plain {@code LEFT JOIN} whatever the statement's
     * predicates, and only hand-built models in tests carry one.
     *
     * @param partitionGranularity the granularity both tables partition the aligned column by, which a partition
     *     pre-selection needs to name the partitions (see {@link PartitionPreselection}); null when unknown
     */
    public static EnrichmentJoin keyed(
            Table<?> table,
            Field<?> sourceKey,
            Field<?> enrichmentKey,
            Field<?> sourcePartition,
            Field<?> enrichmentPartition,
            Set<String> exposedNames,
            PartitionGranularity partitionGranularity) {
        final Condition key = DSL.condition("{0} = {1}", sourceKey, enrichmentKey);
        final Condition condition = sourcePartition == null || enrichmentPartition == null
                ? key
                : key.and(DSL.condition("{0} = {1}", sourcePartition, enrichmentPartition));
        return new EnrichmentJoin(table, condition, sourceKey, enrichmentKey, sourcePartition, enrichmentPartition,
                exposedNames, partitionGranularity);
    }

    /** Whether this join knows its grain-key fields and can therefore be emitted keyed. */
    public boolean keyable() {
        return sourceKey != null && enrichmentKey != null;
    }

    /**
     * Whether this join knows an aligned partition-column pair, so a keyed restriction can also bound the
     * enrichment's partitions. Implies {@link #keyable()} in every model the catalog builds; checked
     * independently so a hand-built model cannot produce a partition bound without a key to hang it on.
     */
    public boolean partitionAligned() {
        return keyable() && sourcePartition != null && enrichmentPartition != null;
    }
}
