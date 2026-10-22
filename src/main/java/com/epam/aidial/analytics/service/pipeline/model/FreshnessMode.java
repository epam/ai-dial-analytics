package com.epam.aidial.analytics.service.pipeline.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Declared freshness intent of an aggregate pipeline (D-TR-2).
 *
 * <p>{@code PERIODIC} — the rollup is kept current by a scheduled windowed {@code INSERT … SELECT}. This
 * is the implemented mode and the contract.
 *
 * <p>{@code INCREMENTAL} — an insert-time materialized view over partial aggregate states, keeping the
 * rollup current in real time. Declared here so the vocabulary is complete and a caller's intent is
 * legible, but <b>rejected at registration</b>: it needs an {@code AggregatingMergeTree} target with
 * {@code *State}/{@code *Merge} columns and a mergeability gate on every measure. The design permits
 * "reject or auto-downgrade"; rejecting is the honest choice, because a caller who asks for real-time
 * materialization and silently receives batched refreshes has no way to find out.
 *
 * <p>Stored by {@link Enum#name()} inside the {@code spec} JSONB; the wire form is the lowercase
 * {@link #code()}.
 */
public enum FreshnessMode {
    PERIODIC("periodic"),
    INCREMENTAL("incremental");

    private final String code;

    FreshnessMode(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
