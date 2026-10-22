package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.DataType;
import org.jooq.impl.SQLDataType;

/**
 * Maps the abstract {@link QueryFieldType} to a nominal jOOQ {@link DataType} for SQL rendering only
 * (column references and casts on the connection-less CLICKHOUSE render context). jOOQ never executes
 * here, so the exact type fidelity does not matter; {@code OBJECT}/{@code ARRAY}/{@code MAP} columns are opaque
 * (projectable, not comparison operands). Mirrors the mapping the hand-built schema used before the
 * catalog became the source of truth.
 */
public final class JooqTypes {

    private JooqTypes() {
    }

    /**
     * The Java class a field of {@code type} is declared with — {@link #of}'s type, named separately because
     * a hand-built {@code DSL.field} template takes a class rather than a {@link DataType}. Going through
     * this rather than picking a class at the call site is what keeps a computed value of some type declared
     * the same way a <em>column</em> of that type is.
     */
    public static Class<?> javaType(QueryFieldType type) {
        return of(type).getType();
    }

    public static DataType<?> of(QueryFieldType type) {
        return switch (type) {
            // An enum binds as the string it is: its values cross the wire as text, and the DSL
            // compares it against string literals.
            case STRING, UUID, ENUM -> SQLDataType.VARCHAR;
            case INTEGER -> SQLDataType.INTEGER;
            case LONG, TIMESTAMP -> SQLDataType.BIGINT;
            case DECIMAL -> SQLDataType.NUMERIC;
            case BOOLEAN -> SQLDataType.BOOLEAN;
            case DATE -> SQLDataType.LOCALDATE;
            case OBJECT, ARRAY, MAP -> SQLDataType.OTHER;
        };
    }
}
