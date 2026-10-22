package com.epam.aidial.analytics.service.table;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Interval unit of a table's row-expiry TTL, rendered as the ClickHouse {@code INTERVAL} keyword
 * ({@code TTL <col> + INTERVAL <n> DAY} etc. — see {@link TableDdlRenderer}). Stored by
 * {@link Enum#name()} (uppercase) in PostgreSQL; the wire form is the lowercase {@link #code()}.
 */
public enum TtlUnit {
    HOUR("hour"),
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    YEAR("year");

    private final String code;

    TtlUnit(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
