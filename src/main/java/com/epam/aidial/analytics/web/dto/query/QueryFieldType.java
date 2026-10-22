package com.epam.aidial.analytics.web.dto.query;

import com.epam.aidial.analytics.service.query.model.ValueType;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Declared type of a flat schema field, aligned with the DSL literal vocabulary ({@link ValueType})
 * plus {@code OBJECT}/{@code ARRAY} for JSONB fields surfaced as-is (not flattened) and {@code MAP} for
 * a string-keyed string bag (ClickHouse {@code Map(String, String)}, what OpenTelemetry-style attribute
 * bags need). Wire values are the lowercase names.
 *
 * <p>{@code ENUM} is a <em>closed set of strings</em>, parameterized by the column's declared
 * {@code enum_values} the way {@code ARRAY} is parameterized by its {@code element_type}, and
 * materialized as ClickHouse {@code Enum8}/{@code Enum16}. It carries no literal of its own: the DSL
 * and SQL both name an enum value with the string it is, so an enum field resolves to exactly what a
 * {@code STRING} field does on every read surface (VARCHAR to jOOQ and Calcite,
 * {@code CHARACTER VARYING} in {@code information_schema}, a UTF-8 Arrow vector). Only the catalog
 * and the schema-discovery surfaces know it is narrower than a string — which is the point, since
 * that is where the declared domain is published.
 */
public enum QueryFieldType {
    STRING("string"),
    INTEGER("integer"),
    LONG("long"),
    DECIMAL("decimal"),
    BOOLEAN("boolean"),
    DATE("date"),
    TIMESTAMP("timestamp"),
    UUID("uuid"),
    ENUM("enum"),
    OBJECT("object"),
    ARRAY("array"),
    MAP("map");

    private final String code;

    QueryFieldType(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    /** Resolves a wire code (e.g. {@code "string"}) to its type, or null if {@code code} is null. */
    public static QueryFieldType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (final QueryFieldType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown field type code: " + code);
    }
}
