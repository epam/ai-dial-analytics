package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;

/**
 * Whether every version of a key in a catalog table is guaranteed to land in one partition — the
 * precondition for deduplicating a {@code FINAL} read inside each partition instead of across all of them
 * (see {@code FinalSettings}). Decided from the catalog's declarations only, never from data.
 *
 * <p>A table is partition-local when any of the following holds:
 *
 * <ul>
 *   <li>it collapses nothing — an {@code append} source is a plain {@code MergeTree}, so FINAL is a no-op on
 *       it and there is nothing for the merge scope to get wrong. This is the one rule that trusts the
 *       catalog's <em>declared</em> {@code write} facet over the engine: a source recorded {@code append}
 *       that is physically a {@code ReplacingMergeTree} partitioned outside its key would be deduplicated
 *       per partition and could return two versions of a key. The table-management capability requires the
 *       facet to agree with the engine (and V22 corrected the two seeded sources that once did not); this
 *       rule is why that requirement now guards results, not only descriptions;
 *   <li>it declares no partition column — one partition, nothing to cross;
 *   <li>its partition column is one of its ordering-key columns — the key itself fixes the partition, so
 *       two versions of one key cannot differ in it (ClickHouse 26.2+ works this case out on its own);
 *   <li>it is an enrichment whose partition column is <em>aligned</em> with its source's — the same column
 *       name on both sides, the catalog's claim that for a grain key the two tables hold the same value —
 *       and that source is itself partition-local. This is the case ClickHouse cannot see:
 *       {@code dial_usage_log_payload} is keyed on {@code event_id} and partitioned by the
 *       {@code request_time} it copies from its parent event, which never changes.
 * </ul>
 *
 * <p>The alignment test is deliberately the same name-equality {@code CatalogQueryService} uses to key a
 * join by partition, so the two optimisations rest on one claim rather than two.
 */
public final class PartitionLocality {

    private PartitionLocality() {
    }

    /**
     * Whether {@code table} is partition-local.
     *
     * @param table the table to decide for
     * @param source the source {@code table} enriches, consulted only when {@code table} is an enrichment;
     *     may be null for a source
     */
    public static boolean isPartitionLocal(TableDefinitionEntity table, TableDefinitionEntity source) {
        if (table.effectiveWriteMode() == TableWriteMode.APPEND) {
            return true;
        }
        final String partition = table.getPartitionColumn();
        if (partition == null) {
            return true;
        }
        if (table.getOrderingKey() != null && table.getOrderingKey().contains(partition)) {
            return true;
        }
        return table.getType() == TableType.ENRICHMENT
                && source != null
                && partition.equals(source.getPartitionColumn())
                && isPartitionLocal(source, null);
    }
}
