package com.epam.aidial.analytics.web.dto.query;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Group of a supported query function, mirroring the structured-query "Supported function catalog"
 * spec table. Wire values are the lowercase snake_case names.
 */
public enum QueryFunctionGroup {
    SCALAR("scalar"),
    AGGREGATE("aggregate"),
    ORDERED_SET_AGGREGATE("ordered_set_aggregate"),
    /** Accepted only as the function of a {@code window} expression (an {@code OVER} clause in SQL). */
    WINDOW("window");

    private final String code;

    QueryFunctionGroup(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
