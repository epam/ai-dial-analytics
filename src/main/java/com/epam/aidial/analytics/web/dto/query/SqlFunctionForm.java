package com.epam.aidial.analytics.web.dto.query;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How a SQL function catalog entry is written ({@code GET /v1/queries/sql-functions}). Wire values are the
 * lowercase names.
 */
public enum SqlFunctionForm {
    /** A call, {@code name(args)}. */
    FUNCTION("function"),
    /** An infix or prefix operator: {@code +}, {@code ||}, {@code LIKE}. */
    OPERATOR("operator"),
    /** A keyword construct that is neither: {@code CASE … END}, {@code CAST(x AS VARCHAR)}. */
    SYNTAX("syntax");

    private final String code;

    SqlFunctionForm(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
