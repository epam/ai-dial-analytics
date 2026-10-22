package com.epam.aidial.analytics.web.dto.query;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Kind of a supported function's argument: {@code EXPRESSION} accepts any resolvable expression
 * (field, literal, nested function), the literal kinds require a literal {@code value}
 * expression of the given type (validated by the translator before any SQL is built), and
 * {@code LAMBDA} requires a {@code lambda} expression — the per-element body of a higher-order array
 * function, which is the one argument kind that is not a value at all. Wire values
 * are the lowercase snake_case names.
 */
public enum QueryFunctionArgKind {
    EXPRESSION("expression"),
    LAMBDA("lambda"),
    INTEGER_LITERAL("integer_literal"),
    NUMERIC_LITERAL("numeric_literal"),
    STRING_LITERAL("string_literal");

    private final String code;

    QueryFunctionArgKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
