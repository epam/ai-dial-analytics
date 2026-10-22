package com.epam.aidial.analytics.web.dto.query;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Declared return type of a supported query function. {@code SAME_AS_ARGUMENT} marks functions
 * whose result type follows the operand ({@code min}, {@code max}, {@code percentile_disc}) — the
 * server does not type-infer expressions at discovery time; clients know the operand's type from
 * the entity schema. {@code ARRAY} marks a function returning an array whose <em>element</em> type
 * follows the operand ({@code group_uniq_array}); like {@code SAME_AS_ARGUMENT} it names the shape,
 * not the element, for the same reason. {@code ELEMENT_OF_ARGUMENT} is {@code ARRAY}'s inverse — the
 * result is the <em>element</em> type of the first argument ({@code array_join}) — and needs a token of
 * its own because neither of the other two can name it: {@code ARRAY} would claim the result is an
 * array, and {@code SAME_AS_ARGUMENT} would report the operand's own type, which is {@code array}
 * again. Wire values are the lowercase snake_case names.
 */
public enum QueryFunctionReturnType {
    STRING("string"),
    INTEGER("integer"),
    LONG("long"),
    NUMERIC("numeric"),
    BOOLEAN("boolean"),
    TIMESTAMP("timestamp"),
    ARRAY("array"),
    SAME_AS_ARGUMENT("same_as_argument"),
    ELEMENT_OF_ARGUMENT("element_of_argument");

    private final String code;

    QueryFunctionReturnType(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
