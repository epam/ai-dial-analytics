package com.epam.aidial.analytics.service.audit;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The kind of catalog resource an activity is about. See {@link ActivityType} for why these constants
 * are spelled in PascalCase rather than ADAS's usual uppercase.
 *
 * <p>{@link #Table} and {@link #TableColumn} back the table catalog: since the {@code V25}
 * denormalization an enrichment's grain key and a table's MergeTree ordering key are columns of the
 * definition row, so a change to either is an {@link ActivityType#Update} on the owning {@link #Table}
 * rather than a resource of its own. {@link #Pipeline} backs the pipeline registry — <b>both</b> kinds,
 * since {@code V36} folded the enrichment control plane's {@code rule} registry into {@code pipeline} as
 * the {@code ENRICH} kind, so one resource type now names what used to need two. {@link #SavedQuery}
 * backs the saved query registry.
 */
public enum ActivityResourceType {

    /** A {@code table_definition} row, identified by the table name. */
    Table,

    /** A {@code column_mapping} row, identified by {@code <tableName>:<sourceName>}. */
    TableColumn,

    /** A {@code pipeline} row of either kind, identified by its name. */
    Pipeline,

    /** A {@code saved_query} row, identified by its id. */
    SavedQuery;

    /**
     * Pins the wire spelling against ADAS's global {@code WRITE_ENUMS_TO_LOWERCASE} — see
     * {@link ActivityType#wireValue()}. Without it this constant would go out as {@code "tablecolumn"},
     * which no reader could map back to a resource type.
     */
    @JsonValue
    public String wireValue() {
        return name();
    }
}
