package com.epam.aidial.analytics.service.query.sql;

/**
 * The runtime value family of a result column, as ClickHouse will actually return it through the
 * structured-query executor. This is the single normative source for the Arrow schema:
 * declared field types map 1:1 ({@code uuid} surfaces as a string), and aggregate results follow
 * ClickHouse semantics ({@code count}/{@code length} → INT64, {@code avg}/{@code percentile_*} →
 * FLOAT64) rather than Calcite's validated types. {@code ARRAY} marks a projected array column;
 * its scalar element family travels separately as {@link ResultColumn#elementKind()}.
 * {@code OBJECT} marks a projected {@code object} (ClickHouse {@code JSON}) column: it is
 * schemaless and row-heterogeneous, so Arrow carries it as JSON text rather than a structured
 * type (the REST surfaces still emit it as a native JSON value).
 */
public enum ResultColumnKind {
    STRING,
    INT32,
    INT64,
    FLOAT64,
    DECIMAL,
    BOOL,
    DATE,
    TIMESTAMP,
    ARRAY,
    OBJECT
}
