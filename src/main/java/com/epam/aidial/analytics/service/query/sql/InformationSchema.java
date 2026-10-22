package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalog-describing relations of the SQL surface: {@code INFORMATION_SCHEMA.TABLES},
 * {@code .COLUMNS}, and {@code .SCHEMATA}, built from the same {@code CatalogQueryService} models that
 * back {@code GET /v1/queries/entities}. They exist because clients that discover through catalog SQL
 * — Power BI's Dremio connector among them — never call the Flight SQL metadata RPCs.
 *
 * <p>Rows are materialized once per {@link CalciteQueryCatalog.Snapshot} and scanned in process; a
 * metadata statement never reaches ClickHouse. Because the rows come from the caller's resolved
 * models, a column hidden from that caller by sensitive-column access control is absent here too,
 * without this class knowing anything about access control.
 *
 * <p>Column sets, ordering and value spellings follow Dremio's, verified against a live Dremio OSS
 * 26.0.5. That is not cosmetic: a Dremio-compatible client resolves {@code DATA_TYPE} against a fixed
 * twelve-name table and aborts the whole navigation on a name it does not recognise, so
 * {@code CHARACTER VARYING} (never {@code VARCHAR}) and {@code INTEGER} (never {@code INT}) are part
 * of the contract. {@code CATALOGS} and {@code VIEWS} are deliberately absent: ADAS has no catalog
 * namespace — inventing a name would contradict the empty catalog term the Flight endpoint declares —
 * and it has no views.
 */
public final class InformationSchema {

    /** The metadata schema's name, as clients spell it. Resolution is case-insensitive. */
    public static final String SCHEMA = "INFORMATION_SCHEMA";

    /**
     * The schema every queryable entity also answers to. A constant rather than configuration: the
     * value travels back as the qualifier on every statement a catalog-SQL client generates, so a
     * deployment-varying name would make saved statements deployment-specific.
     */
    public static final String DATA_SCHEMA = "adas";

    /** ADAS has no catalog namespace; the empty string matches the endpoint's empty catalog term. */
    private static final String NO_CATALOG = "";

    private static final String TYPE_TABLE = "TABLE";
    private static final String TYPE_SYSTEM_TABLE = "SYSTEM_TABLE";

    private static final String TABLES = "TABLES";
    private static final String COLUMNS = "COLUMNS";
    private static final String SCHEMATA = "SCHEMATA";

    private static final List<String> TABLES_COLUMNS =
            List.of("TABLE_CATALOG", "TABLE_SCHEMA", "TABLE_NAME", "TABLE_TYPE");

    private static final List<String> SCHEMATA_COLUMNS =
            List.of("CATALOG_NAME", "SCHEMA_NAME", "SCHEMA_OWNER", "TYPE", "IS_MUTABLE");

    /** Dremio's seventeen, in Dremio's order. {@code COLUMN_SIZE} is Dremio's extension, kept in place. */
    private static final List<String> COLUMNS_COLUMNS = List.of(
            "TABLE_CATALOG", "TABLE_SCHEMA", "TABLE_NAME", "COLUMN_NAME", "ORDINAL_POSITION",
            "COLUMN_DEFAULT", "IS_NULLABLE", "DATA_TYPE", "COLUMN_SIZE", "CHARACTER_MAXIMUM_LENGTH",
            "CHARACTER_OCTET_LENGTH", "NUMERIC_PRECISION", "NUMERIC_PRECISION_RADIX", "NUMERIC_SCALE",
            "DATETIME_PRECISION", "INTERVAL_TYPE", "INTERVAL_PRECISION");

    /** The integer-typed members of {@link #COLUMNS_COLUMNS}; every other column is character. */
    private static final List<String> COLUMNS_INTEGER_COLUMNS = List.of(
            "ORDINAL_POSITION", "COLUMN_SIZE", "CHARACTER_MAXIMUM_LENGTH", "CHARACTER_OCTET_LENGTH",
            "NUMERIC_PRECISION", "NUMERIC_PRECISION_RADIX", "NUMERIC_SCALE", "DATETIME_PRECISION",
            "INTERVAL_PRECISION");

    private InformationSchema() {}

    /**
     * The {@code DATA_TYPE} spelling for a declared field type. Every value here is one of the twelve
     * names a Dremio-compatible client accepts; anything outside that set fails its navigation with
     * {@code TYPE NOT FOUND}. {@code object} reports as character for the same reason the Calcite row
     * type does — it travels as JSON text — and because {@code OBJECT} is not one of the twelve;
     * {@code map} reports as character for exactly the same reason.
     */
    public static String dataType(QueryFieldType type) {
        return switch (type) {
            // An enum reports as character data, so a BI client reading this catalog sees text and
            // compares it with string literals; the declared domain is published on the schema
            // discovery surface instead, deliberately not encoded into the SQL type.
            case STRING, UUID, ENUM, OBJECT, MAP -> "CHARACTER VARYING";
            case INTEGER -> "INTEGER";
            case LONG -> "BIGINT";
            case DECIMAL -> "DECIMAL";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP";
            case ARRAY -> "ARRAY";
        };
    }

    /** True when {@code name} is the metadata schema, case-insensitively. */
    public static boolean isMetadataSchema(String name) {
        return SCHEMA.equalsIgnoreCase(name);
    }

    /** Adds the metadata schema and its relations to {@code root}, populated from {@code models}. */
    public static void attach(SchemaPlus root, List<QueryEntityModel> models) {
        final Map<String, org.apache.calcite.schema.Table> relations = new LinkedHashMap<>();
        relations.put(TABLES, new MetadataTable(TABLES_COLUMNS, List.of(), tableRows(models)));
        relations.put(COLUMNS, new MetadataTable(COLUMNS_COLUMNS, COLUMNS_INTEGER_COLUMNS, columnRows(models)));
        relations.put(SCHEMATA, new MetadataTable(SCHEMATA_COLUMNS, List.of(), schemataRows()));
        root.add(SCHEMA, new AbstractSchema() {
            @Override
            protected Map<String, org.apache.calcite.schema.Table> getTableMap() {
                return relations;
            }
        });
    }

    private static List<Object[]> tableRows(List<QueryEntityModel> models) {
        final List<Object[]> rows = new ArrayList<>();
        for (final QueryEntityModel model : models) {
            rows.add(new Object[] {NO_CATALOG, DATA_SCHEMA, model.entity(), TYPE_TABLE});
        }
        // The catalog describes itself: a client that lists tables and then asks for their columns
        // should not find relations it can query missing from the listing.
        for (final String relation : List.of(COLUMNS, SCHEMATA, TABLES)) {
            rows.add(new Object[] {NO_CATALOG, SCHEMA, relation, TYPE_SYSTEM_TABLE});
        }
        return rows;
    }

    private static List<Object[]> schemataRows() {
        // SCHEMA_OWNER and TYPE mirror Dremio's constants; IS_MUTABLE is NO because the SQL surface is
        // read-only — no statement it accepts can create anything in either schema.
        return List.of(
                new Object[] {NO_CATALOG, DATA_SCHEMA, "<owner>", "SIMPLE", "NO"},
                new Object[] {NO_CATALOG, SCHEMA, "<owner>", "SIMPLE", "NO"});
    }

    private static List<Object[]> columnRows(List<QueryEntityModel> models) {
        final List<Object[]> rows = new ArrayList<>();
        for (final QueryEntityModel model : models) {
            int ordinal = 1;
            for (final Map.Entry<String, com.epam.aidial.analytics.service.query.QueryFieldBinding> entry
                    : model.bindings().entrySet()) {
                rows.add(columnRow(DATA_SCHEMA, model.entity(), entry.getKey(), ordinal++,
                        dataType(entry.getValue().type()), entry.getValue().type()));
            }
        }
        addSelfDescribingColumns(rows, TABLES, TABLES_COLUMNS, List.of());
        addSelfDescribingColumns(rows, COLUMNS, COLUMNS_COLUMNS, COLUMNS_INTEGER_COLUMNS);
        addSelfDescribingColumns(rows, SCHEMATA, SCHEMATA_COLUMNS, List.of());
        return rows;
    }

    private static void addSelfDescribingColumns(
            List<Object[]> rows, String relation, List<String> columns, List<String> integerColumns) {
        int ordinal = 1;
        for (final String column : columns) {
            final QueryFieldType type =
                    integerColumns.contains(column) ? QueryFieldType.INTEGER : QueryFieldType.STRING;
            rows.add(columnRow(SCHEMA, relation, column, ordinal++, dataType(type), type));
        }
    }

    /**
     * One {@code COLUMNS} row. The size and precision facts follow Dremio's conventions — notably
     * {@code NUMERIC_PRECISION} in <em>bits</em> with a radix of 2 for the integer and float families,
     * and digits with a radix of 10 only for {@code DECIMAL}. {@code IS_NULLABLE} is {@code YES}
     * throughout, which is not a shrug: every field of an ADAS result is nullable, as the Arrow mapping
     * and {@code GetXdbcTypeInfo} already report. A fact ADAS does not fix — the length of an unbounded
     * string, any interval property — is null rather than zero.
     */
    private static Object[] columnRow(
            String schema, String table, String column, int ordinal, String dataType, QueryFieldType type) {
        final Facets facets = facets(type);
        return new Object[] {
            NO_CATALOG, schema, table, column, ordinal,
            null, "YES", dataType, facets.columnSize(), null,
            null, facets.numericPrecision(), facets.numericRadix(), facets.numericScale(),
            facets.datetimePrecision(), null, null,
        };
    }

    /**
     * The size and precision facts for a field type. A switch expression rather than a statement so
     * that adding a {@link QueryFieldType} is a compile error here instead of a silently all-null row.
     */
    private static Facets facets(QueryFieldType type) {
        return switch (type) {
            // ADAS declares no length bound for character or array columns, so every length is
            // genuinely absent rather than zero.
            case STRING, UUID, ENUM, OBJECT, ARRAY, MAP -> new Facets(null, null, null, null, null);
            case BOOLEAN -> new Facets(1, null, null, null, null);
            case INTEGER -> new Facets(32, 32, 2, 0, null);
            case LONG -> new Facets(64, 64, 2, 0, null);
            // Matches the DECIMAL(38, 9) the Calcite row type declares for a decimal field.
            case DECIMAL -> new Facets(38, 38, 10, 9, null);
            case DATE -> new Facets(10, null, null, null, 0);
            // yyyy-mm-dd hh:mm:ss plus '.' and the three fractional digits the row type declares.
            case TIMESTAMP -> new Facets(23, null, null, null, 3);
        };
    }

    /**
     * Note the unit convention, which is Dremio's and looks wrong at a glance: for the integer
     * families {@code numericPrecision} is in <em>bits</em> and the radix is 2, while {@code DECIMAL}
     * reports digits with a radix of 10.
     */
    private record Facets(
            Integer columnSize,
            Integer numericPrecision,
            Integer numericRadix,
            Integer numericScale,
            Integer datetimePrecision) {}

    /**
     * One metadata relation: a fixed column list over rows materialized when the snapshot was taken.
     * Scannable so that Calcite's interpreter can execute a statement over it directly, which is what
     * lets projection, {@code DISTINCT}, {@code WHERE}, {@code ORDER BY} and {@code LIMIT} work with
     * standard semantics instead of a hand-matched set of statement shapes.
     */
    private static final class MetadataTable extends AbstractTable implements ScannableTable {

        private final List<String> columns;
        private final List<String> integerColumns;
        private final List<Object[]> rows;

        private MetadataTable(List<String> columns, List<String> integerColumns, List<Object[]> rows) {
            this.columns = columns;
            this.integerColumns = integerColumns;
            this.rows = rows;
        }

        @Override
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
            final RelDataTypeFactory.Builder builder = typeFactory.builder();
            for (final String column : columns) {
                final SqlTypeName type =
                        integerColumns.contains(column) ? SqlTypeName.INTEGER : SqlTypeName.VARCHAR;
                builder.add(column, typeFactory.createSqlType(type)).nullable(true);
            }
            return builder.build();
        }

        @Override
        public Enumerable<Object[]> scan(DataContext root) {
            return Linq4j.asEnumerable(rows);
        }
    }
}
