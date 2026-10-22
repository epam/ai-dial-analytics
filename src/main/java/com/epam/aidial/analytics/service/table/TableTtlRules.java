package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

import java.util.Map;

/**
 * Validation of a table's structured TTL declaration ({@link TtlInput}), shared by the
 * define-and-materialize flow and the live schema PATCH. ClickHouse requires a table TTL expression
 * to be a non-nullable {@code Date}/{@code DateTime}, so the referenced column must be a declared
 * non-nullable {@code date} or {@code timestamp} column of the table; a {@code timestamp}
 * ({@code DateTime64(3)}) column is rendered wrapped as {@code toDateTime(col)} (a bare
 * {@code DateTime64} is rejected by ClickHouse with {@code BAD_TTL_EXPRESSION}).
 */
public final class TableTtlRules {

    private TableTtlRules() {}

    /** Type + nullability of one physical column, as needed to validate a TTL declaration. */
    public record PhysicalColumn(QueryFieldType type, boolean nullable) {}

    /**
     * Validates {@code ttl} against the table's physical columns (keyed by {@code source_name}),
     * throwing a 422 {@link TableValidationException} on any violation. For the live PATCH the map
     * reflects the request's own drop/rename/add operations, so a just-added or just-renamed column
     * is referenceable.
     */
    public static void requireValid(TtlInput ttl, Map<String, PhysicalColumn> physicalColumns) {
        if (ttl.interval() < 1) {
            throw new TableValidationException("ttl interval must be a positive integer");
        }
        final PhysicalColumn column = physicalColumns.get(ttl.column());
        if (column == null) {
            throw new TableValidationException(
                    "ttl column '" + ttl.column() + "' is not a declared column of this table");
        }
        if (column.type() != QueryFieldType.DATE && column.type() != QueryFieldType.TIMESTAMP) {
            throw new TableValidationException("ttl column '" + ttl.column()
                    + "' must be of type date or timestamp");
        }
        if (column.nullable()) {
            throw new TableValidationException("ttl column '" + ttl.column()
                    + "' must be non-nullable (ClickHouse rejects a TTL over a nullable column)");
        }
    }

    /** Whether the TTL expression over a column of {@code type} must be wrapped as {@code toDateTime(col)}. */
    public static boolean wrapsToDateTime(QueryFieldType type) {
        return type == QueryFieldType.TIMESTAMP;
    }
}
