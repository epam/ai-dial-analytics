package com.epam.aidial.analytics.service.table;

import java.util.List;

/**
 * Service-layer input for {@code PATCH /v1/tables/{name}/schema} (the live-schema surface): columns to
 * {@code add}, exposed names to {@code drop}, exposed names to {@code rename}, per-column metadata
 * {@code update}s, and a merge-patch of the table's row-expiry {@code ttl}. Operations are applied
 * drop → rename → add → ttl → update; a single column may be the target of at most one structural
 * operation per request. The physical commands (drop/rename/add/ttl) are issued as one multi-command
 * ClickHouse {@code ALTER} statement; {@code update} is catalog-only metadata (a merge-patch over
 * {@code tag}/{@code displayName}/{@code description}/{@code sensitive}/{@code heavy}). Null lists are treated as
 * empty; a null {@code ttl} change means "leave unchanged" (a present change with a null value removes
 * the TTL). Frozen physical keys and the table-level tag ordering are NOT part of this input — keys
 * are set when the schema is defined ({@code POST .../schema}) and frozen after materialization, and
 * tag ordering is table catalog metadata ({@code PUT /v1/tables/{name}}).
 */
public record PatchInput(
        List<ColumnInput> add, List<String> drop, List<Rename> rename, List<ColumnMetadataUpdate> update,
        Change<TtlInput> ttl) {

    /** Rename the column currently exposed as {@code from} to {@code to} (physical and exposed name). */
    public record Rename(String from, String to) {}

    /**
     * Merge-patch of the metadata of the column exposed as {@code name}: each {@link Change} is either
     * "leave unchanged" or "set to a value" (a value of {@code null} clears the attribute).
     */
    public record ColumnMetadataUpdate(
            String name, Change<String> tag, Change<String> displayName,
            Change<String> description, Change<Boolean> sensitive, Change<Boolean> heavy) {}

    public List<ColumnInput> add() {
        return add == null ? List.of() : add;
    }

    public List<String> drop() {
        return drop == null ? List.of() : drop;
    }

    public List<Rename> rename() {
        return rename == null ? List.of() : rename;
    }

    public List<ColumnMetadataUpdate> update() {
        return update == null ? List.of() : update;
    }

    public Change<TtlInput> ttl() {
        return ttl == null ? Change.leave() : ttl;
    }
}
