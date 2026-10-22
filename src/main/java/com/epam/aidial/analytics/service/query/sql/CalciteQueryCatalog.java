package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.WildcardProjection;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.Frameworks;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Calcite schema the SQL front end validates against, from the same catalog as the
 * {@code /v1/queries} discovery endpoints: one table per ACTIVE queryable source entity, whose
 * columns are the entity's flat fields — bare source columns plus dotted enrichment columns
 * (addressable in SQL only as quoted identifiers, e.g. {@code "deployment_ref.display_name"}).
 * Every declared field type is visible: {@code array} fields as Calcite array types (element type
 * from the catalog's {@code element_type}) and {@code object} fields as character columns (Calcite
 * has no JSON type — its JSON support is defined over character strings, and the Arrow layer
 * carries an object as JSON text). Both are <em>projection-only</em>: the DSL has no array or
 * object literal, so the translator rejects every use outside a plain projection item.
 *
 * <p>The snapshot is rebuilt per request: the catalog is mutable via table management and the reads
 * are cheap Postgres queries. All columns are nullable — nullability governs Arrow schema shape,
 * not validation.
 */
@Component
@RequiredArgsConstructor
public class CalciteQueryCatalog {

    private final CatalogQueryService catalog;

    /**
     * A consistent point-in-time view: the Calcite root schema plus per-entity field-type maps
     * (and, for {@code array} fields, the per-entity element-type maps) and the per-entity wildcard
     * field list a {@code SELECT *} expands to.
     */
    public record Snapshot(
            SchemaPlus rootSchema,
            Map<String, Map<String, QueryFieldType>> entityFieldTypes,
            Map<String, Map<String, QueryFieldType>> entityElementTypes,
            Map<String, List<String>> entityWildcardFields) {

        /** Declared flat field types for {@code entity}, or null if it is not a queryable entity. */
        public Map<String, QueryFieldType> fieldTypes(String entity) {
            return entityFieldTypes.get(entity);
        }

        /** Element types of {@code entity}'s array fields (empty when it has none, null if unknown entity). */
        public Map<String, QueryFieldType> elementTypes(String entity) {
            return entityElementTypes.get(entity);
        }

        /**
         * The fields a star expands to for {@code entity} — its non-heavy source columns, per
         * {@link WildcardProjection} — or null if it is not a queryable entity. Deliberately a subset of
         * the table's row type: heavy and enrichment columns stay resolvable when named explicitly.
         */
        public List<String> wildcardFields(String entity) {
            return entityWildcardFields.get(entity);
        }
    }

    /**
     * Builds a fresh snapshot of every queryable entity the caller holds {@code bar} on. The translation and
     * validation endpoints pass {@link Privilege#TABLE_GET}: they resolve a statement against the catalog
     * model and never contact ClickHouse, so they disclose schema rather than data. An entity below the bar
     * is simply absent from the snapshot, so naming it fails as an unknown relation.
     */
    public Snapshot snapshot(Privilege bar) {
        final SchemaPlus root = Frameworks.createRootSchema(true);
        final Map<String, Map<String, QueryFieldType>> types = new LinkedHashMap<>();
        final Map<String, Map<String, QueryFieldType>> elementTypes = new LinkedHashMap<>();
        final Map<String, List<String>> wildcardFields = new LinkedHashMap<>();
        final Map<String, Table> entityTables = new LinkedHashMap<>();
        final List<QueryEntityModel> models = new ArrayList<>();
        for (final String name : catalog.queryableEntityNames(bar)) {
            catalog.findModel(name, bar).ifPresent(model -> {
                final EntityTable table = new EntityTable(model);
                root.add(model.entity(), table);
                entityTables.put(model.entity(), table);
                models.add(model);
                types.put(model.entity(), fieldTypes(model));
                elementTypes.put(model.entity(), elementTypes(model));
                wildcardFields.put(model.entity(), List.copyOf(WildcardProjection.sourceFields(model)));
            });
        }
        // The same table instances are reachable twice: bare in the root, which keeps every statement
        // written against ADAS today resolving exactly as before, and under the data schema, because a
        // client that discovered an entity through INFORMATION_SCHEMA read a schema name from
        // TABLE_SCHEMA and qualifies everything it generates with it.
        root.add(InformationSchema.DATA_SCHEMA, new AbstractSchema() {
            @Override
            protected Map<String, Table> getTableMap() {
                return entityTables;
            }
        });
        InformationSchema.attach(root, List.copyOf(models));
        return new Snapshot(root, Map.copyOf(types), Map.copyOf(elementTypes), Map.copyOf(wildcardFields));
    }

    private static Map<String, QueryFieldType> fieldTypes(QueryEntityModel model) {
        final Map<String, QueryFieldType> types = new LinkedHashMap<>();
        model.bindings().forEach((name, binding) -> types.put(name, binding.type()));
        return Map.copyOf(types);
    }

    private static Map<String, QueryFieldType> elementTypes(QueryEntityModel model) {
        final Map<String, QueryFieldType> types = new LinkedHashMap<>();
        model.bindings().forEach((name, binding) -> {
            if (binding.type() == QueryFieldType.ARRAY) {
                types.put(name, binding.elementType() == null ? QueryFieldType.STRING : binding.elementType());
            }
        });
        return Map.copyOf(types);
    }

    /** One queryable entity as a Calcite table; row type from the flat catalog schema. */
    private static final class EntityTable extends AbstractTable {

        private final QueryEntityModel model;

        private EntityTable(QueryEntityModel model) {
            this.model = model;
        }

        @Override
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
            final RelDataTypeFactory.Builder builder = typeFactory.builder();
            model.bindings().forEach((name, binding) ->
                    builder.add(name, fieldType(typeFactory, binding.type(), binding.elementType())).nullable(true));
            return builder.build();
        }

        private static RelDataType fieldType(
                RelDataTypeFactory typeFactory, QueryFieldType type, QueryFieldType elementType) {
            if (type == QueryFieldType.ARRAY) {
                final QueryFieldType element = elementType == null ? QueryFieldType.STRING : elementType;
                return typeFactory.createArrayType(scalarType(typeFactory, element), -1);
            }
            if (type == QueryFieldType.OBJECT || type == QueryFieldType.MAP) {
                // Calcite has no JSON type — its own JSON support (JSON_VALUE, JSON_EXISTS, ...) is
                // defined over character strings, which also matches how the Arrow layer carries an
                // object (Utf8 holding JSON text). Typing it VARCHAR is also what lets the document
                // carve-out type-check: LIKE/ILIKE/=/<>/IN against a character literal parse here, and
                // the translator admits exactly that shape for an object field. Every other use — a
                // sort or group key, DISTINCT, IS NULL, an ordering comparison, a non-extractor
                // function argument — the translator still rejects.
                return typeFactory.createSqlType(SqlTypeName.VARCHAR);
            }
            return scalarType(typeFactory, type);
        }

        private static RelDataType scalarType(RelDataTypeFactory typeFactory, QueryFieldType type) {
            return switch (type) {
                // An enum is a string to the SQL surface: inbound SQL compares it with string literals
                // and a client reading the catalog sees text, so nothing outside the catalog needs to
                // know the column is narrower than a string. Its declared domain is published on
                // /v1/queries/.../schema instead, deliberately not encoded into the SQL type.
                case STRING, UUID, ENUM -> typeFactory.createSqlType(SqlTypeName.VARCHAR);
                case INTEGER -> typeFactory.createSqlType(SqlTypeName.INTEGER);
                case LONG -> typeFactory.createSqlType(SqlTypeName.BIGINT);
                case DECIMAL -> typeFactory.createSqlType(
                        SqlTypeName.DECIMAL,
                        Math.min(38, typeFactory.getTypeSystem().getMaxPrecision(SqlTypeName.DECIMAL)),
                        9);
                case BOOLEAN -> typeFactory.createSqlType(SqlTypeName.BOOLEAN);
                case DATE -> typeFactory.createSqlType(SqlTypeName.DATE);
                case TIMESTAMP -> typeFactory.createSqlType(SqlTypeName.TIMESTAMP, 3);
                case OBJECT, MAP -> throw new IllegalStateException(
                        "object and map fields are typed by fieldType, not as scalars");
                case ARRAY -> throw new IllegalStateException("array elements must be scalar");
            };
        }
    }
}
