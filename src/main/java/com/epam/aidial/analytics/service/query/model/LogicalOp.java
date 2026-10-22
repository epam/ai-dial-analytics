package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** Logical operators. Wire values {@code and}/{@code or}/{@code not}. */
public enum LogicalOp {
    AND("and"),
    OR("or"),
    NOT("not");

    private final String code;

    LogicalOp(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
