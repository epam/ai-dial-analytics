package com.epam.aidial.analytics.service.query.sql;

/**
 * One column of a translated SQL query's result: the output name (select alias or field name) and
 * the {@link ResultColumnKind} that decides its Arrow type. For an {@link ResultColumnKind#ARRAY}
 * column, {@code elementKind} carries the scalar family of the array's elements ({@code null} for
 * every scalar kind). Derived entirely from the translated
 * {@link com.epam.aidial.analytics.service.query.model.StructuredQuery} and the entity's declared
 * field types, so {@code getFlightInfo} and {@code doGet} always agree on the schema.
 */
public record ResultColumn(String name, ResultColumnKind kind, ResultColumnKind elementKind) {

    /** Convenience constructor for scalar columns. */
    public ResultColumn(String name, ResultColumnKind kind) {
        this(name, kind, null);
    }
}
