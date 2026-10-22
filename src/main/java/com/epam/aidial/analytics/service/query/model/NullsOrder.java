package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Null ordering for a sort key. Wire values {@code first}/{@code last}. Optional on {@link
 * SortItem}: when omitted, the database default applies ({@code ASC} → NULLS LAST, {@code DESC} →
 * NULLS FIRST).
 */
public enum NullsOrder {
    FIRST("first"),
    LAST("last");

    private final String code;

    NullsOrder(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
