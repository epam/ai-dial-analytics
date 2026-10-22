package com.epam.aidial.analytics.service.table;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Cardinality of an enrichment relative to its source grain key. v1 supports only {@code ZERO_OR_ONE}
 * (at most one enrichment row per grain key, like {@code feedback}); higher cardinalities are reserved
 * for a later phase. Stored by {@link Enum#name()} (uppercase) in PostgreSQL; the wire form is the
 * lowercase {@link #code()}.
 */
public enum Cardinality {
    ZERO_OR_ONE("zero_or_one");

    private final String code;

    Cardinality(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
