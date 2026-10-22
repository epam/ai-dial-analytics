package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Comparison operator codes. Wire values are the lowercase codes ({@code eq}, {@code ne}, …).
 * The contains family is four operators: {@code co}/{@code nc} match case-sensitively (aligned with
 * SQL {@code LIKE}), {@code ico}/{@code inc} case-insensitively (SQL {@code ILIKE}).
 */
public enum ComparisonOp {
    EQ("eq"),
    NE("ne"),
    CO("co"),
    NC("nc"),
    ICO("ico"),
    INC("inc"),
    LT("lt"),
    GT("gt"),
    LE("le"),
    GE("ge"),
    IN("in");

    private final String code;

    ComparisonOp(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
