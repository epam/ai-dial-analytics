package com.epam.aidial.analytics.sync.mapping;

import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.SyncProperties;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.service.ClickHouseIngestLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reconciles the configured column mapping against the live target table at startup and refuses to
 * start on drift. Runs as an ordered {@link ApplicationRunner} after {@code ClickHouseMigrationRunner}
 * has provisioned the schema, and before the sync scheduler fires — so config/table drift fails fast
 * at boot rather than mid-run.
 *
 * <p>Every configured column MUST exist in the table, and each column's extraction kind (or its
 * {@code iso-to-ch-datetime} transform) MUST be compatible with the table column's declared type.
 * ClickHouse type spellings are normalized by stripping {@code Nullable(...)} / {@code LowCardinality(...)}
 * wrappers before comparison; an unrecognized spelling is warned-and-allowed rather than rejected.
 * A column declared {@code nullable} in the mapping MUST be backed by a {@code Nullable(...)} table
 * column, and a column not declared nullable MUST NOT be — otherwise the engine's null-on-absent
 * output would be rejected, or a genuine NULL would be impossible. Table columns populated by a
 * table-side default (e.g. {@code _ingested_at}) may be unmapped. This validation step issues no DDL —
 * it only introspects via {@code DESCRIBE TABLE}; provisioning runs earlier in {@code ClickHouseMigrationRunner}.
 *
 * <p>Validation can be disabled for local/dev runs (where ClickHouse may be absent) via
 * {@code sync.validateSchema=false}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
// Ordered after ClickHouseMigrationRunner (@Order(0)) so the table exists before reconciliation.
@Order(1)
public class SchemaValidator implements ApplicationRunner {

    private enum TypeFamily {
        STRING, INTEGER, FLOAT, BOOL, DATETIME, ARRAY, JSON, UNKNOWN
    }

    private final List<SyncTarget> syncTargets;
    private final UsageLogWriter usageLogWriter;
    private final SyncProperties syncProperties;

    /** Validates every target at startup; throws to abort startup on unreachable ClickHouse or drift. */
    @Override
    public void run(ApplicationArguments args) {
        if (!syncProperties.validateSchema()) {
            log.info("Data-sync schema validation disabled (sync.validate-schema=false); skipping table reconciliation");
            return;
        }
        for (SyncTarget target : syncTargets) {
            validateAgainst(target.table(), target.mapping(), describeTable(target.table()));
            log.info("Data-sync mapping reconciled with table '{}': {} column(s) validated",
                    target.table(), target.mapping().columns().size());
        }
        validateLedger(describeTable(ClickHouseIngestLedger.TABLE));
        log.info("Data-sync ingest ledger '{}' validated", ClickHouseIngestLedger.TABLE);
    }

    /**
     * Checks the ingest ledger, which every ingest path reads and writes: a deployment that skipped its
     * migration fails here, at startup, rather than failing every poll cycle and day.
     *
     * @param tableColumns the ledger's {@code DESCRIBE TABLE}; empty when the table does not exist
     * @throws IllegalStateException naming each missing or mistyped column
     */
    void validateLedger(Map<String, String> tableColumns) {
        List<String> problems = new ArrayList<>();
        ClickHouseIngestLedger.COLUMNS.forEach((column, expected) -> {
            String type = tableColumns.get(column);
            if (type == null) {
                problems.add("'" + column + "' is missing");
            } else if (!expected.equals(type)) {
                problems.add("'" + column + "' has type '" + type + "', expected '" + expected + "'");
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Ingest ledger table '" + ClickHouseIngestLedger.TABLE + "' does not match "
                    + "ClickHouse migration V13: " + String.join("; ", problems));
        }
    }

    private Map<String, String> describeTable(String table) {
        try {
            return usageLogWriter.describeTable(table);
        } catch (ClickHouseUnavailableException ex) {
            throw new IllegalStateException("ClickHouse is unreachable at startup; cannot validate the data-sync "
                    + "mapping against table '" + table + "'", ex);
        }
    }

    /**
     * Reconciles one target's mapping against the given table description.
     *
     * @param table        the target table name (for error messages)
     * @param mapping      the target's column mapping
     * @param tableColumns column name to ClickHouse type, as returned by {@code DESCRIBE TABLE}
     * @throws IllegalStateException listing the offending column(s) on mismatch
     */
    void validateAgainst(String table, MappingConfig mapping, Map<String, String> tableColumns) {
        List<String> problems = new ArrayList<>();
        for (ColumnMapping column : mapping.columns()) {
            String type = tableColumns.get(column.column());
            if (type == null) {
                problems.add("'" + column.column() + "' is missing from table '" + table + "'");
                continue;
            }
            if (isNullable(type) != column.nullable()) {
                problems.add("'" + column.column() + "' is "
                        + (column.nullable() ? "declared nullable but table type '" + type + "' is not Nullable"
                                : "not declared nullable but table type '" + type + "' is Nullable"));
            }
            TypeFamily expected = expectedFamily(column);
            TypeFamily actual = classify(normalize(type));
            if (actual == TypeFamily.UNKNOWN) {
                log.warn("Column '{}' has unrecognized ClickHouse type '{}'; skipping type compatibility check",
                        column.column(), type);
                continue;
            }
            if (actual != expected) {
                problems.add("'" + column.column() + "' kind '" + describeExpectation(column) + "' is incompatible with"
                        + " table type '" + type + "'");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Data-sync mapping does not reconcile with table '"
                    + table + "': " + String.join("; ", problems));
        }
    }

    private static TypeFamily expectedFamily(ColumnMapping column) {
        if ("iso-to-ch-datetime".equals(column.transform())) {
            return TypeFamily.DATETIME;
        }
        return switch (column.kind()) {
            case STRING -> TypeFamily.STRING;
            case LONG -> TypeFamily.INTEGER;
            case DOUBLE -> TypeFamily.FLOAT;
            case BOOL -> TypeFamily.BOOL;
            case ARRAY_STRING -> TypeFamily.ARRAY;
            case OBJECT -> TypeFamily.JSON;
        };
    }

    private static String describeExpectation(ColumnMapping column) {
        return "iso-to-ch-datetime".equals(column.transform())
                ? column.kind().token() + "+iso-to-ch-datetime"
                : column.kind().token();
    }

    private static final String[] TYPE_WRAPPERS = {"Nullable(", "LowCardinality("};

    /**
     * Whether the table type is nullable, looking through {@code LowCardinality(...)} wrappers in any
     * nesting order — ClickHouse may report either {@code LowCardinality(Nullable(String))} or
     * {@code Nullable(LowCardinality(String))}.
     */
    private static boolean isNullable(String type) {
        String result = type.trim();
        while (true) {
            if (result.startsWith("Nullable(")) {
                return true;
            }
            String inner = stripWrapper(result);
            if (inner == null) {
                return false;
            }
            result = inner;
        }
    }

    /** Strips {@code Nullable(...)} and {@code LowCardinality(...)} wrappers (any order) for comparison. */
    private static String normalize(String type) {
        String result = type.trim();
        String inner;
        while ((inner = stripWrapper(result)) != null) {
            result = inner;
        }
        return result;
    }

    /** Removes one matching outer {@code Nullable(...)}/{@code LowCardinality(...)} wrapper, or null if none. */
    private static String stripWrapper(String type) {
        for (String wrapper : TYPE_WRAPPERS) {
            if (type.startsWith(wrapper) && type.endsWith(")")) {
                return type.substring(wrapper.length(), type.length() - 1).trim();
            }
        }
        return null;
    }

    private static TypeFamily classify(String type) {
        if (type.equals("String") || type.startsWith("FixedString") || type.equals("UUID")) {
            return TypeFamily.STRING;
        }
        if (type.matches("U?Int\\d+")) {
            return TypeFamily.INTEGER;
        }
        if (type.startsWith("Float") || type.startsWith("Decimal")) {
            return TypeFamily.FLOAT;
        }
        if (type.equals("Bool")) {
            return TypeFamily.BOOL;
        }
        if (type.startsWith("DateTime") || type.startsWith("Date")) {
            return TypeFamily.DATETIME;
        }
        if (type.startsWith("Array(")) {
            return TypeFamily.ARRAY;
        }
        if (type.equals("JSON")) {
            return TypeFamily.JSON;
        }
        return TypeFamily.UNKNOWN;
    }
}
