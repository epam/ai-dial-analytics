package com.epam.aidial.analytics.service.table;

import java.util.List;

/**
 * Service-layer input for {@code POST /v1/tables/{name}/schema} — the complete physical schema of a
 * not-yet-materialized table, defined and materialized in one call. It carries the exposed
 * {@code columns} plus the physical keys for the table's kind: a source's
 * {@code orderingKey}/{@code partitionColumn}/{@code partitionGranularity}, or an enrichment's
 * {@code grainKey}/{@code cardinality} — plus an optional row-expiry {@code ttl} rendered into the
 * {@code CREATE TABLE}. The schema must be complete for its kind (validated before any
 * ClickHouse DDL is issued). The identity fields ({@code name}, {@code type}, {@code sourceTable}) are
 * fixed by create and are not part of this input.
 *
 * <p>{@code identityColumn}/{@code versionColumn} are the pair the governed incremental scan pages a
 * source by ({@code table_definition.identity_column}/{@code version_column}, read by
 * {@code ScanSourceMetadata}). Both are OPTIONAL for a source — one that declares them is scannable, one
 * that does not is refused by the scan — and rejected for an enrichment, which is keyed on its
 * {@code grainKey}; see {@link ScanMetadataRules} for both halves of the contract. An omitted member leaves
 * the stored value unchanged, so re-posting a {@code FAILED} table's schema never silently clears one.
 */
public record DefineSchemaInput(
        List<ColumnInput> columns,
        List<String> orderingKey,
        String partitionColumn,
        PartitionGranularity partitionGranularity,
        String grainKey,
        Cardinality cardinality,
        TtlInput ttl,
        String identityColumn,
        String versionColumn) {

    /**
     * Convenience constructor for a schema that declares no scan metadata — every enrichment, and any
     * source not meant to be scanned.
     */
    public DefineSchemaInput(
            List<ColumnInput> columns,
            List<String> orderingKey,
            String partitionColumn,
            PartitionGranularity partitionGranularity,
            String grainKey,
            Cardinality cardinality,
            TtlInput ttl) {
        this(columns, orderingKey, partitionColumn, partitionGranularity, grainKey, cardinality, ttl,
                null, null);
    }
}
