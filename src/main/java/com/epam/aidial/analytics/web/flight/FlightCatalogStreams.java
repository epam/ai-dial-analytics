package com.epam.aidial.analytics.web.flight;

import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.WildcardProjection;
import com.epam.aidial.analytics.service.query.arrow.ArrowTypeMapper;
import com.epam.aidial.analytics.service.query.sql.ResultColumn;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import com.epam.aidial.analytics.service.query.sql.ResultColumns;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.sql.FlightSqlProducer;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.WriteChannel;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.pojo.Schema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Builds the row content of the Flight SQL catalog RPCs from {@link CatalogQueryService} — the same
 * catalog that backs {@code GET /v1/queries/entities} and
 * {@code GET /v1/queries/entities/schema/{name}}. There is no second catalog: an entity is listed here
 * exactly when it is listed there, with the columns that caller may see, so the RPC and REST views
 * cannot drift.
 *
 * <p>Each method returns a populated {@link VectorSchemaRoot} over the protocol's fixed schema
 * ({@link FlightSqlProducer.Schemas}) built on the caller's allocator; the caller owns and closes it.
 * A method that throws leaves nothing allocated — the root is closed here before the failure
 * propagates, since a root that never reaches the caller could never be closed by it and would leave
 * the request's child allocator un-closeable (see {@link #build}). Callers must invoke these under the
 * Flight caller's identity (see
 * {@code AdasFlightSqlProducer.asCaller}) — {@code CatalogQueryService} resolves sensitive-column
 * visibility from the security context, and a catalog that lists a column the query path hides would
 * leak the column's existence.
 *
 * <p>These RPCs declare no catalog and no schema namespace: {@link #catalogs} and {@link #dbSchemas} are
 * empty and table rows carry null {@code catalog_name}/{@code db_schema_name}. That is deliberate — a
 * client driven by the RPCs then generates the unqualified {@code FROM <entity>} that has always been the
 * canonical form, rather than qualifying with a namespace it was handed.
 *
 * <p>It is a statement about what these RPCs declare, not about the limit of what the front end accepts.
 * The SQL surface also resolves an entity qualified by the data schema name, and serves the
 * {@code INFORMATION_SCHEMA} relations, for the clients that discover through catalog SQL instead of
 * through these RPCs — so a qualified statement is answerable. The two channels are deliberately
 * asymmetric; see {@code specs/flight-sql-endpoint} ("Catalog metadata RPCs stream from the query
 * catalog") for why the RPCs stay narrower.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "flight", name = "enabled", havingValue = "true")
public class FlightCatalogStreams {

    /** The only table type ADAS exposes: queryable entities are the catalog's ACTIVE source tables. */
    private static final String TABLE_TYPE = "TABLE";

    private final CatalogQueryService catalog;

    /** No catalogs: ADAS has no catalog namespace, so the stream is empty (not an error). */
    public VectorSchemaRoot catalogs(BufferAllocator allocator) {
        return empty(FlightSqlProducer.Schemas.GET_CATALOGS_SCHEMA, allocator);
    }

    /** No schemas: ADAS has no schema namespace, so the stream is empty (not an error). */
    public VectorSchemaRoot dbSchemas(BufferAllocator allocator) {
        return empty(FlightSqlProducer.Schemas.GET_SCHEMAS_SCHEMA, allocator);
    }

    /** Exactly one table type, {@value #TABLE_TYPE}. */
    public VectorSchemaRoot tableTypes(BufferAllocator allocator) {
        return build(FlightSqlProducer.Schemas.GET_TABLE_TYPES_SCHEMA, allocator, root -> {
            setUtf8(root, "table_type", 0, TABLE_TYPE);
            root.setRowCount(1);
        });
    }

    /**
     * One row per queryable entity, in the catalog's stable alphabetical order, honoring the command's
     * filters. When {@code include_schema} is set each row carries the IPC-serialized Arrow schema of
     * the entity's wildcard projection — byte-identical to what {@code SELECT * FROM <entity>}
     * advertises, so a client that reads the catalog and then issues a star gets what it was promised.
     */
    public VectorSchemaRoot tables(FlightSql.CommandGetTables command, BufferAllocator allocator) {
        final boolean includeSchema = command.getIncludeSchema();
        final Schema rowSchema = includeSchema
                ? FlightSqlProducer.Schemas.GET_TABLES_SCHEMA
                : FlightSqlProducer.Schemas.GET_TABLES_SCHEMA_NO_SCHEMA;
        return build(rowSchema, allocator, root -> {
            int row = 0;
            for (final String name : matchingEntityNames(command)) {
                // catalog_name and db_schema_name stay null: the namespace does not exist (see class javadoc).
                root.getVector("catalog_name").setNull(row);
                root.getVector("db_schema_name").setNull(row);
                setUtf8(root, "table_name", row, name);
                setUtf8(root, "table_type", row, TABLE_TYPE);
                if (includeSchema) {
                    setBinary(root, "table_schema", row, serialize(starSchema(name)));
                }
                row++;
            }
            root.setRowCount(row);
        });
    }

    /**
     * One row per scalar result-column family the Arrow type mapping can produce, narrowed to a single
     * row when the command carries a {@code data_type} filter. Driven by an exhaustive switch over
     * {@link ResultColumnKind}, so a new kind cannot be added to the Arrow mapping without failing to
     * compile here. {@code uuid} and {@code object} columns surface as {@code VARCHAR} and so are
     * covered by that row; {@code array} columns have no XDBC type code and are deliberately absent.
     */
    public VectorSchemaRoot xdbcTypeInfo(FlightSql.CommandGetXdbcTypeInfo command, BufferAllocator allocator) {
        final Integer filter = command.hasDataType() ? command.getDataType() : null;
        return build(FlightSqlProducer.Schemas.GET_TYPE_INFO_SCHEMA, allocator, root -> {
            int row = 0;
            for (final XdbcType type : XdbcType.ALL) {
                if (filter != null && filter != type.dataType()) {
                    continue;
                }
                writeTypeInfoRow(root, row++, type);
            }
            root.setRowCount(row);
        });
    }

    // ---- table filtering -------------------------------------------------------------------

    /**
     * The entity names a {@code GetTables} command selects. Filter semantics follow the Flight SQL
     * protocol: an omitted {@code catalog} matches everything and an empty one matches objects without
     * a catalog (all of ours), while a non-empty one matches nothing since ADAS publishes no catalog;
     * an omitted {@code table_types} list is no filter, and a list without {@value #TABLE_TYPE} matches
     * nothing. The schema filter pattern is matched against the empty schema name, so only a pattern
     * that accepts it (e.g. {@code %}) leaves any rows.
     */
    private List<String> matchingEntityNames(FlightSql.CommandGetTables command) {
        if (command.hasCatalog() && !command.getCatalog().isEmpty()) {
            return List.of();
        }
        if (command.getTableTypesCount() > 0 && !command.getTableTypesList().contains(TABLE_TYPE)) {
            return List.of();
        }
        if (command.hasDbSchemaFilterPattern()
                && !likePattern(command.getDbSchemaFilterPattern()).matcher("").matches()) {
            return List.of();
        }
        final Pattern namePattern = command.hasTableNameFilterPattern()
                ? likePattern(command.getTableNameFilterPattern())
                : null;
        final List<String> names = new ArrayList<>();
        // TABLE_READ, not TABLE_GET: Flight is an execution protocol, so it must not advertise a table a
        // subsequent DoGet would refuse. A caller holding only TABLE_GET on a table sees it over HTTP and
        // not here — deliberately narrower than HTTP discovery.
        for (final String name : catalog.queryableEntityNames(Privilege.TABLE_READ)) {
            if (namePattern == null || namePattern.matcher(name).matches()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Compiles a SQL {@code LIKE} pattern into an equivalent regex: {@code %} matches any sequence,
     * {@code _} any single character, a backslash escapes either, and every other character is matched
     * literally. Matching is case-insensitive, mirroring the SQL front end's case-insensitive
     * identifier handling.
     */
    private static Pattern likePattern(String like) {
        final StringBuilder regex = new StringBuilder(like.length() * 2);
        for (int i = 0; i < like.length(); i++) {
            final char c = like.charAt(i);
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                case '\\' -> {
                    // Escape: the next character is literal (a trailing backslash is itself literal).
                    if (i + 1 < like.length()) {
                        regex.append(Pattern.quote(String.valueOf(like.charAt(++i))));
                    } else {
                        regex.append(Pattern.quote("\\"));
                    }
                }
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    // ---- entity schema ---------------------------------------------------------------------

    /**
     * The Arrow schema of {@code entity}'s wildcard projection: its non-heavy source columns
     * ({@link WildcardProjection}) typed through the shared declared-type mapping and
     * {@link ArrowTypeMapper}. An entity whose expansion is empty yields an empty schema — it is still
     * queryable by naming columns explicitly.
     */
    private Schema starSchema(String entity) {
        final QueryEntityModel model = catalog.findModel(entity, Privilege.TABLE_READ)
                .orElseThrow(() -> CallStatus.INTERNAL
                .withDescription("entity '" + entity + "' disappeared from the catalog mid-request")
                .toRuntimeException());
        final List<String> fields = WildcardProjection.sourceFields(model);
        final List<ResultColumn> columns = new ArrayList<>(fields.size());
        for (final String field : fields) {
            final QueryFieldBinding binding = model.bindings().get(field);
            columns.add(ResultColumns.column(field, binding.type(), binding.elementType()));
        }
        return ArrowTypeMapper.schema(columns);
    }

    /** The Arrow IPC encoding of a schema, the form the protocol's {@code table_schema} field carries. */
    private static byte[] serialize(Schema schema) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (WriteChannel channel = new WriteChannel(Channels.newChannel(bytes))) {
            MessageSerializer.serialize(channel, schema);
        } catch (IOException e) {
            throw CallStatus.INTERNAL.withDescription("failed to serialize table schema")
                    .toRuntimeException();
        }
        return bytes.toByteArray();
    }

    // ---- XDBC type info --------------------------------------------------------------------

    /**
     * One {@code GetXdbcTypeInfo} row. {@code columnSize} is the maximum precision (null when not
     * meaningful), {@code radix} the numeric radix, {@code scale} the fixed decimal scale (null for
     * everything that has none), {@code quote} the literal prefix/suffix a driver wraps values in, and
     * {@code fixedPrecScale} whether the type has a fixed precision and scale. Every per-type attribute
     * is a component set inside {@link #of}'s switch rather than derived from {@code typeName}, so the
     * whole row is covered by that switch's exhaustiveness over {@link ResultColumnKind}.
     */
    private record XdbcType(
            String typeName,
            int dataType,
            Integer columnSize,
            String quote,
            boolean caseSensitive,
            int searchable,
            Integer radix,
            Integer scale,
            boolean fixedPrecScale) {

        /** Every ADAS-visible SQL type, in {@link ResultColumnKind} order. */
        private static final List<XdbcType> ALL = all();

        private static List<XdbcType> all() {
            final List<XdbcType> types = new ArrayList<>();
            for (final ResultColumnKind kind : ResultColumnKind.values()) {
                final XdbcType type = of(kind);
                if (type != null) {
                    types.add(type);
                }
            }
            return List.copyOf(types);
        }

        /** The XDBC type for a result-column kind, or null for the kinds that have no XDBC code. */
        private static XdbcType of(ResultColumnKind kind) {
            final int full = FlightSql.Searchable.SEARCHABLE_FULL_VALUE;
            final int basic = FlightSql.Searchable.SEARCHABLE_BASIC_VALUE;
            return switch (kind) {
                case STRING -> new XdbcType("VARCHAR", FlightSql.XdbcDataType.XDBC_VARCHAR_VALUE,
                        null, "'", true, full, null, null, false);
                case INT32 -> new XdbcType("INTEGER", FlightSql.XdbcDataType.XDBC_INTEGER_VALUE,
                        10, null, false, basic, 10, 0, false);
                case INT64 -> new XdbcType("BIGINT", FlightSql.XdbcDataType.XDBC_BIGINT_VALUE,
                        19, null, false, basic, 10, 0, false);
                case FLOAT64 -> new XdbcType("DOUBLE", FlightSql.XdbcDataType.XDBC_DOUBLE_VALUE,
                        53, null, false, basic, 2, null, false);
                // The only fixed precision/scale type: every decimal column is Decimal128(38, 9).
                case DECIMAL -> new XdbcType("DECIMAL", FlightSql.XdbcDataType.XDBC_DECIMAL_VALUE,
                        ArrowTypeMapper.DECIMAL_PRECISION, null, false, basic, 10,
                        ArrowTypeMapper.DECIMAL_SCALE, true);
                case BOOL -> new XdbcType("BOOLEAN", FlightSql.XdbcDataType.XDBC_BIT_VALUE,
                        1, null, false, basic, null, null, false);
                case DATE -> new XdbcType("DATE", FlightSql.XdbcDataType.XDBC_DATE_VALUE,
                        10, "'", false, basic, null, null, false);
                case TIMESTAMP -> new XdbcType("TIMESTAMP", FlightSql.XdbcDataType.XDBC_TIMESTAMP_VALUE,
                        23, "'", false, basic, null, 3, false);
                // An object column surfaces as VARCHAR (JSON text) and an array has no XDBC code at
                // all; neither adds a row of its own. Inventing a code for a list would invite a
                // driver to generate casts ADAS cannot honor.
                case ARRAY, OBJECT -> null;
            };
        }
    }

    private static void writeTypeInfoRow(VectorSchemaRoot root, int row, XdbcType type) {
        setUtf8(root, "type_name", row, type.typeName());
        setInt(root, "data_type", row, type.dataType());
        setNullableInt(root, "column_size", row, type.columnSize());
        setNullableUtf8(root, "literal_prefix", row, type.quote());
        setNullableUtf8(root, "literal_suffix", row, type.quote());
        // No DDL through this surface, so there are no creation parameters to report.
        root.getVector("create_params").setNull(row);
        // Every ADAS column is nullable in Arrow, so every type is reported nullable.
        setInt(root, "nullable", row, FlightSql.Nullable.NULLABILITY_NULLABLE_VALUE);
        setBit(root, "case_sensitive", row, type.caseSensitive());
        setInt(root, "searchable", row, type.searchable());
        // Unsigned variants are not exposed; the flag is null rather than a misleading false.
        root.getVector("unsigned_attribute").setNull(row);
        setBit(root, "fixed_prec_scale", row, type.fixedPrecScale());
        // Read-only endpoint: nothing auto-increments.
        root.getVector("auto_increment").setNull(row);
        setUtf8(root, "local_type_name", row, type.typeName());
        setNullableInt(root, "minimum_scale", row, type.scale());
        setNullableInt(root, "maximum_scale", row, type.scale());
        setInt(root, "sql_data_type", row, type.dataType());
        root.getVector("datetime_subcode").setNull(row);
        setNullableInt(root, "num_prec_radix", row, type.radix());
        root.getVector("interval_precision").setNull(row);
    }

    // ---- vector helpers --------------------------------------------------------------------

    private static VectorSchemaRoot empty(Schema schema, BufferAllocator allocator) {
        return build(schema, allocator, root -> root.setRowCount(0));
    }

    /**
     * Allocates a root over {@code schema} and populates it, closing it if population throws. The close
     * is the whole point: population reads the catalog (a database round trip, and one per row when
     * {@code include_schema} is set), so it can fail after {@code allocateNew} has taken buffers. An
     * exception thrown then would leave a root nobody holds a reference to — the caller's
     * try-with-resources never binds it — and the request's Arrow child allocator would refuse to close
     * with outstanding allocation, leaking the buffers, wedging the root allocator's byte count above
     * zero for the process's life, and turning shutdown into a second failure.
     */
    private static VectorSchemaRoot build(Schema schema, BufferAllocator allocator, Consumer<VectorSchemaRoot> rows) {
        final VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);
        try {
            root.allocateNew();
            rows.accept(root);
            return root;
        } catch (RuntimeException e) {
            root.close();
            throw e;
        }
    }

    private static void setUtf8(VectorSchemaRoot root, String field, int row, String value) {
        ((VarCharVector) root.getVector(field)).setSafe(row, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void setNullableUtf8(VectorSchemaRoot root, String field, int row, String value) {
        if (value == null) {
            root.getVector(field).setNull(row);
        } else {
            setUtf8(root, field, row, value);
        }
    }

    private static void setBinary(VectorSchemaRoot root, String field, int row, byte[] value) {
        ((VarBinaryVector) root.getVector(field)).setSafe(row, value);
    }

    private static void setInt(VectorSchemaRoot root, String field, int row, int value) {
        ((IntVector) root.getVector(field)).setSafe(row, value);
    }

    private static void setNullableInt(VectorSchemaRoot root, String field, int row, Integer value) {
        if (value == null) {
            root.getVector(field).setNull(row);
        } else {
            setInt(root, field, row, value);
        }
    }

    private static void setBit(VectorSchemaRoot root, String field, int row, boolean value) {
        ((BitVector) root.getVector(field)).setSafe(row, value ? 1 : 0);
    }
}
