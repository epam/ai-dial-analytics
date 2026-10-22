package com.epam.aidial.analytics.service.table;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Materialization lifecycle of a catalog table. Create records identity only as {@code PENDING} (no
 * ClickHouse table yet); {@code POST /v1/tables/{name}/schema} defines the complete physical schema and
 * materializes the table — it issues the ClickHouse {@code CREATE TABLE} and flips the definition to
 * {@code ACTIVE} on success or {@code FAILED} on error. {@code ACTIVE} therefore means the ClickHouse
 * table is confirmed created and the definition is queryable/writable; {@code PENDING} is a durable
 * "identity recorded, not materialized" state; {@code FAILED} means the last materialization attempt was
 * rejected or did not confirm. {@code PENDING} and {@code FAILED} are the un-materialized states (not
 * queryable/writable) — and because row writes require {@code ACTIVE}, a {@code FAILED} table holds no
 * user data, so re-defining its schema (retrying) is safe. {@code FAILED} SHALL be entered only from a
 * failed materialization, never from {@code ACTIVE}: a failed live {@code ALTER} ({@code PATCH .../schema})
 * leaves the table {@code ACTIVE}. Stored by {@link Enum#name()} (uppercase) in PostgreSQL; the wire form
 * is the lowercase {@link #code()}.
 */
public enum TableStatus {
    PENDING("pending"),
    ACTIVE("active"),
    FAILED("failed");

    private final String code;

    TableStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
