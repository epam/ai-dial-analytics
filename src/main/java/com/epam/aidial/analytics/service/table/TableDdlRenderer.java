package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders the ClickHouse DDL ADAS issues for catalog tables it materializes, and the {@code ALTER}s
 * for schema evolution. Engine is chosen by table kind <em>and</em> write discipline: an {@code append}
 * {@code source} is a plain {@code MergeTree} (with an optional {@code PARTITION BY}); an
 * {@code upsert_by_key} {@code source} is a {@code ReplacingMergeTree(_updated_at)} ordered by its
 * {@code ordering_key} (which is therefore its unique key); an {@code enrichment} is a
 * {@code ReplacingMergeTree(_updated_at)} ordered by its grain key, so at most one row survives per
 * grain key (physical {@code ZERO_OR_ONE} cardinality). Every version-collapsing table carries the
 * system {@code _updated_at} column (the version key) — one definition, both callers; the
 * {@code _writer} column is deferred until ADAS has auth. Identifiers are
 * validated and backtick-quoted via {@link Identifiers}.
 *
 * <p>Live schema evolution renders as a <b>single multi-command</b> {@code ALTER TABLE} statement
 * ({@link #renderAlter} over the per-command builders), which ClickHouse validates as a whole — the
 * statement is rejected or applied in its entirety. An optional row-expiry TTL renders as
 * {@code TTL <col> + INTERVAL <n> <UNIT>} — the column wrapped as {@code toDateTime(col)} for a
 * {@code timestamp} ({@code DateTime64}) column, which ClickHouse would otherwise reject
 * ({@code BAD_TTL_EXPRESSION}).
 */
@Component
public class TableDdlRenderer {

    /** The version column stamped on every enrichment (ReplacingMergeTree dedup key). */
    public static final String UPDATED_AT = "_updated_at";
    // DEFAULT now64(3): the caller cannot set _updated_at, so ClickHouse stamps every write itself and
    // duplicate writes per grain collapse to the latest on a final=1 read. Ordering is only as fine as
    // the stamp — same-millisecond writes of one grain tie, and a tie keeps an unspecified row. The
    // row-inserting clients pin async_insert=0 so a stamp is per insert, not per async flush; see
    // ClickHouseWriterClient.ASYNC_INSERT.
    private static final String UPDATED_AT_TYPE = "DateTime64(3) DEFAULT now64(3)";

    /** One physical column to declare: its (validated) ClickHouse name and rendered DDL type. */
    public record DdlColumn(String name, String type) {}

    /**
     * A row-expiry TTL to render: the physical column, the interval, and whether the column must be
     * wrapped as {@code toDateTime(col)} (a {@code timestamp} column; see
     * {@link TableTtlRules#wrapsToDateTime}).
     */
    public record TtlDdl(String column, int interval, TtlUnit unit, boolean wrapToDateTime) {}

    /**
     * A table to create: its {@code kind} and {@code write} discipline (together they drive the engine +
     * system columns), its physical {@code columns} (user columns plus, for an enrichment, its grain-key
     * column and, when aligned, its alignment column — system columns are added here), the
     * {@code orderingKey}, an optional {@code PARTITION BY} (a source's, or an aligned enrichment's), an
     * optional row-expiry {@code ttl}, whether expired parts are dropped whole ({@code ttlOnlyDropParts}), and
     * the columns carrying a lookup index ({@code lookupIndexes}, see {@link #addLookupIndexCommand}).
     */
    public record CreateSpec(
            TableType kind,
            TableWriteMode write,
            List<DdlColumn> columns,
            List<String> orderingKey,
            String partitionColumn,
            PartitionGranularity partitionGranularity,
            TtlDdl ttl,
            boolean ttlOnlyDropParts,
            List<String> lookupIndexes) {

        /** A spec with no {@code ttl_only_drop_parts} setting and no lookup index. */
        public CreateSpec(TableType kind, TableWriteMode write, List<DdlColumn> columns, List<String> orderingKey,
                String partitionColumn, PartitionGranularity partitionGranularity, TtlDdl ttl) {
            this(kind, write, columns, orderingKey, partitionColumn, partitionGranularity, ttl, false, List.of());
        }

        public CreateSpec {
            lookupIndexes = lookupIndexes == null ? List.of() : List.copyOf(lookupIndexes);
        }
    }

    /**
     * The bloom-filter false-positive rate of a lookup index. The default ({@code 0.025}) floors a lookup at about
     * 2.5% of the table whatever the key's size — 153k rows for a 29-row session on a 6.1M-row table — where
     * {@code 0.001} floors it at about 15k for ~200 KiB of index.
     */
    static final String LOOKUP_INDEX_TYPE = "bloom_filter(0.001)";

    public String renderCreate(String tableName, CreateSpec spec) {
        if (spec.orderingKey().isEmpty()) {
            throw new TableValidationException("a table must declare a non-empty ordering_key");
        }
        // An enrichment always collapses (on its grain); a source collapses only when it declares the
        // upsert_by_key discipline. Both then need the version column the engine dedups on.
        final boolean collapsing = spec.kind() == TableType.ENRICHMENT
                || spec.write() == TableWriteMode.UPSERT_BY_KEY;
        final List<DdlColumn> columns = new ArrayList<>(spec.columns());
        if (collapsing) {
            columns.add(new DdlColumn(UPDATED_AT, UPDATED_AT_TYPE));
        }
        if (columns.isEmpty()) {
            throw new TableValidationException("a table must declare at least one column");
        }
        final List<String> definitions = new ArrayList<>();
        columns.forEach(col -> definitions.add("    " + Identifiers.quote(col.name()) + " " + col.type()));
        spec.lookupIndexes().forEach(column -> definitions.add("    " + lookupIndexDefinition(column)));
        final String columnDefs = String.join(",\n", definitions);
        final String orderBy = spec.orderingKey().stream()
                .map(Identifiers::quote)
                .collect(Collectors.joining(", "));
        final String engine = collapsing
                ? "ReplacingMergeTree(" + Identifiers.quote(UPDATED_AT) + ")"
                : "MergeTree";

        final StringBuilder ddl = new StringBuilder()
                .append("CREATE TABLE ").append(Identifiers.quote(tableName)).append(" (\n")
                .append(columnDefs).append("\n)")
                .append("\nENGINE = ").append(engine);
        if (spec.partitionColumn() != null) {
            ddl.append("\nPARTITION BY ").append(partitionExpr(spec.partitionColumn(), spec.partitionGranularity()));
        }
        ddl.append("\nORDER BY (").append(orderBy).append(")");
        if (spec.ttl() != null) {
            ddl.append("\nTTL ").append(ttlExpr(spec.ttl()));
        }
        if (spec.ttlOnlyDropParts()) {
            ddl.append("\nSETTINGS ttl_only_drop_parts = 1");
        }
        return ddl.toString();
    }

    /** The name of the lookup index on {@code column}: {@code idx_<column>}. */
    public static String lookupIndexName(String column) {
        return "idx_" + column;
    }

    /**
     * {@code ADD INDEX IF NOT EXISTS idx_<col> <col> TYPE bloom_filter(0.001) GRANULARITY 1} — a lookup index on a
     * live table. It covers parts written or merged from then on; nothing here materializes it over existing
     * parts, which stay correct to read and only slower to pre-select.
     */
    public String addLookupIndexCommand(String column) {
        return addLookupIndexCommand(column, lookupIndexName(column));
    }

    /**
     * As {@link #addLookupIndexCommand(String)}, under the index name {@code indexName} — for a column whose
     * {@code idx_<col>} is taken by an index a {@code RENAME COLUMN} left on another column.
     */
    public String addLookupIndexCommand(String column, String indexName) {
        return "ADD INDEX IF NOT EXISTS " + lookupIndexDefinition(column, indexName).substring("INDEX ".length());
    }

    /**
     * {@code DROP INDEX IF EXISTS <name>} — issued ahead of a {@code DROP COLUMN} of a column the index is over,
     * in the same {@code ALTER}: ClickHouse refuses to drop a column a skip index depends on.
     */
    public String dropIndexCommand(String indexName) {
        return "DROP INDEX IF EXISTS " + Identifiers.quote(indexName);
    }

    private static String lookupIndexDefinition(String column) {
        return lookupIndexDefinition(column, lookupIndexName(column));
    }

    private static String lookupIndexDefinition(String column, String indexName) {
        return "INDEX " + Identifiers.quote(indexName) + " " + Identifiers.quote(column)
                + " TYPE " + LOOKUP_INDEX_TYPE + " GRANULARITY 1";
    }

    /**
     * Renders one multi-command {@code ALTER TABLE} statement over {@code commands} (built by the
     * {@code *Command} methods below). ClickHouse validates the comma-separated commands as a whole:
     * one invalid command rejects the entire statement, so callers get all-or-nothing semantics.
     */
    public String renderAlter(String tableName, List<String> commands) {
        if (commands.isEmpty()) {
            throw new TableValidationException("an ALTER requires at least one command");
        }
        return "ALTER TABLE " + Identifiers.quote(tableName) + " " + String.join(", ", commands);
    }

    public String addColumnCommand(DdlColumn column) {
        return "ADD COLUMN " + Identifiers.quote(column.name()) + " " + column.type();
    }

    /**
     * {@code ADD COLUMN IF NOT EXISTS} — for a caller that reconciles rather than patches, and must be
     * replayable.
     *
     * <p><b>Separate from {@link #addColumnCommand} rather than replacing it.</b> The schema-patch
     * endpoint's unconditional {@code ADD COLUMN} is what makes a duplicate column a 422 there, and its
     * duplicate check is against the catalog — so tolerating an existing physical column would let a patch
     * report success for a column it did not add.
     *
     * <p>A reconciler wants the opposite. It issues its DDL and then writes the catalog, and that order is
     * only replayable if the DDL half can be re-issued: a crash between the two halves leaves a physical
     * column with no catalog row, which the next boot must be able to complete rather than be wedged by.
     * {@code IF NOT EXISTS} is already the established spelling in the ClickHouse migrations (V5, V7, V8).
     */
    public String addColumnIfNotExistsCommand(DdlColumn column) {
        return "ADD COLUMN IF NOT EXISTS " + Identifiers.quote(column.name()) + " " + column.type();
    }

    public String dropColumnCommand(String columnName) {
        return "DROP COLUMN " + Identifiers.quote(columnName);
    }

    public String renameColumnCommand(String from, String to) {
        return "RENAME COLUMN " + Identifiers.quote(from) + " TO " + Identifiers.quote(to);
    }

    public String modifyTtlCommand(TtlDdl ttl) {
        return "MODIFY TTL " + ttlExpr(ttl);
    }

    public String removeTtlCommand() {
        return "REMOVE TTL";
    }

    public String renderDrop(String tableName) {
        return "DROP TABLE IF EXISTS " + Identifiers.quote(tableName);
    }

    private String partitionExpr(String column, PartitionGranularity granularity) {
        final String fn = switch (granularity) {
            case DAY -> "toYYYYMMDD";
            case MONTH -> "toYYYYMM";
            case YEAR -> "toYear";
        };
        return fn + "(" + Identifiers.quote(column) + ")";
    }

    private String ttlExpr(TtlDdl ttl) {
        final String column = Identifiers.quote(ttl.column());
        final String expr = ttl.wrapToDateTime() ? "toDateTime(" + column + ")" : column;
        return expr + " + INTERVAL " + ttl.interval() + " " + ttl.unit().name();
    }
}
