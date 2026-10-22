package com.epam.aidial.analytics.service.table;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Write discipline of a catalog table: what a write to an already-present key does.
 * {@code APPEND} keeps every row (a plain {@code MergeTree}); {@code UPSERT_BY_KEY} collapses to the
 * latest row per ordering key (a {@code ReplacingMergeTree(_updated_at)}), making the
 * {@code ordering_key} the table's unique key — what a rollup target needs so that recomputing a group
 * key replaces its row instead of adding a second one.
 *
 * <p>A <b>table</b> facet, decoupled from {@link TableType}: it says how a write behaves, not what the
 * table is. It is <b>immutable</b>, because it selects the ClickHouse engine and an engine is fixed by
 * {@code CREATE TABLE} — so it is declared once at create and rejected on every mutating endpoint. It is
 * caller-settable only on a {@code SOURCE}: an enrichment is always keyed and collapsed on its grain, so
 * its discipline is implied by its kind. The stored column therefore keeps its {@code APPEND} default on an
 * enrichment and does not describe it — every read of the facet goes through
 * {@link TableDefinitionEntity#effectiveWriteMode()}, which resolves the kind's implication, because the
 * facet's contract is to describe the engine and not merely to have selected it.
 *
 * <p>Stored by {@link Enum#name()} (uppercase) in PostgreSQL; the wire form is the lowercase
 * {@link #code()}.
 */
public enum TableWriteMode {
    APPEND("append"),
    UPSERT_BY_KEY("upsert_by_key");

    private final String code;

    TableWriteMode(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
