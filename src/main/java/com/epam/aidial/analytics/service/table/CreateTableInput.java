package com.epam.aidial.analytics.service.table;

import java.util.List;

/**
 * Service-layer input for {@code POST /v1/tables}, which is <b>identity-only</b> and issues no
 * ClickHouse DDL: it records the table's identity and catalog metadata as a {@code PENDING} definition.
 * The physical schema (columns and the physical keys — a source's {@code ordering_key}/{@code
 * partition_by} or an enrichment's {@code grain_key}/{@code cardinality}) is defined afterwards through
 * {@code POST /v1/tables/{name}/schema}, which defines it and materializes the table in one call.
 *
 * <ul>
 *   <li><b>source</b> — {@code name}, {@code type}, and optional {@code write}/{@code description}/{@code tagOrder}.</li>
 *   <li><b>enrichment</b> — additionally {@code sourceTable} (required and immutable), naming the source
 *       it enriches; {@code write} is not accepted (its discipline is implied by its kind).</li>
 * </ul>
 *
 * <p>{@code write} is the immutable write discipline (see {@link TableWriteMode}); a null means
 * "unspecified" and is stored as {@link TableWriteMode#APPEND}.
 */
public record CreateTableInput(
        String name,
        String description,
        TableType type,
        String sourceTable,
        TableWriteMode write,
        List<String> tagOrder) {

    /**
     * Convenience constructor for callers that take the default write discipline (append) and set no
     * per-table tag ordering.
     */
    public CreateTableInput(String name, String description, TableType type, String sourceTable) {
        this(name, description, type, sourceTable, null, null);
    }
}
