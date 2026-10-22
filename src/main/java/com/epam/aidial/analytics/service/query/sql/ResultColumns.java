package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * The single declared-type → {@link ResultColumnKind} mapping, shared by everything that must agree on
 * a column's Arrow type: the SQL translator deriving a statement's result columns
 * ({@link RelToStructuredQueryTranslator}) and the Flight SQL catalog advertising an entity's schema
 * ({@code web.flight.FlightCatalogStreams}). Both then render through
 * {@link com.epam.aidial.analytics.service.query.arrow.ArrowTypeMapper}, so a table's advertised
 * schema and the schema of a query over that table cannot disagree about a column's type.
 */
public final class ResultColumns {

    private ResultColumns() {}

    /**
     * The result column for one declared catalog field. An {@code array} field becomes
     * {@link ResultColumnKind#ARRAY} carrying its element's scalar kind; every other type maps through
     * {@link #scalarKind}. {@code elementType} is only consulted for an array field and defaults to
     * {@code string} when the catalog row predates element-type validation.
     */
    public static ResultColumn column(String name, QueryFieldType type, QueryFieldType elementType) {
        if (type == QueryFieldType.ARRAY) {
            final QueryFieldType element = elementType == null ? QueryFieldType.STRING : elementType;
            return new ResultColumn(name, ResultColumnKind.ARRAY, scalarKind(element));
        }
        return new ResultColumn(name, scalarKind(type));
    }

    /**
     * The runtime value family of a scalar declared type ({@code uuid} surfaces as a string, an
     * {@code object} and a {@code map} as JSON text). Rejects {@code array}, whose element kind must be resolved by
     * {@link #column} before any scalar mapping.
     */
    public static ResultColumnKind scalarKind(QueryFieldType type) {
        if (type == null) {
            throw new IllegalStateException("a result column references a field with no declared type");
        }
        return switch (type) {
            // This one mapping is what keeps the Arrow and Flight layers free of enum awareness: both
            // switch on ResultColumnKind, not on QueryFieldType, so an enum lands in a UTF-8 vector
            // with no case of its own anywhere below here.
            case STRING, UUID, ENUM -> ResultColumnKind.STRING;
            case INTEGER -> ResultColumnKind.INT32;
            case LONG -> ResultColumnKind.INT64;
            case DECIMAL -> ResultColumnKind.DECIMAL;
            case BOOLEAN -> ResultColumnKind.BOOL;
            case DATE -> ResultColumnKind.DATE;
            case TIMESTAMP -> ResultColumnKind.TIMESTAMP;
            // A ClickHouse Map(String, String) comes back over the read path as a JSON object — the
            // same runtime family as a JSON column — so it reuses OBJECT rather than adding a kind that
            // would branch identically at every consumer.
            case OBJECT, MAP -> ResultColumnKind.OBJECT;
            case ARRAY -> throw new IllegalStateException(
                    "array fields resolve their element kind before scalar mapping");
        };
    }

    /**
     * The runtime value family of a <b>validated SQL</b> type, for the two places a column's family has to
     * be read off Calcite's own type rather than off a catalog declaration: the catalog relations, whose
     * columns are declared in Calcite's type system, and a set operation's output column where the branches
     * disagree and Calcite's least-restrictive unification decides.
     *
     * <p>Only the families those two can produce are mapped. {@code null} means "no mapping" and leaves the
     * caller to word the failure — the catalog executor calls it a translator bug, since its relations
     * carry no other type, while a set operation calls it a branch-type mismatch, since the caller's two
     * branches are what produced it.
     */
    public static ResultColumnKind sqlKind(SqlTypeName type) {
        return switch (type) {
            case VARCHAR, CHAR -> ResultColumnKind.STRING;
            case INTEGER, SMALLINT, TINYINT -> ResultColumnKind.INT32;
            case BIGINT -> ResultColumnKind.INT64;
            case DOUBLE, FLOAT, REAL -> ResultColumnKind.FLOAT64;
            case DECIMAL -> ResultColumnKind.DECIMAL;
            case BOOLEAN -> ResultColumnKind.BOOL;
            case DATE -> ResultColumnKind.DATE;
            case TIMESTAMP -> ResultColumnKind.TIMESTAMP;
            default -> null;
        };
    }

    /** The declared DSL type a result-column family reads back as, for a column typed from SQL. */
    public static QueryFieldType fieldType(ResultColumnKind kind) {
        return switch (kind) {
            case STRING -> QueryFieldType.STRING;
            case INT32 -> QueryFieldType.INTEGER;
            case INT64 -> QueryFieldType.LONG;
            // FLOAT64 and DECIMAL are one declared type: the DSL has no separate float, which is exactly
            // why RelationColumn carries the runtime kind alongside this.
            case FLOAT64, DECIMAL -> QueryFieldType.DECIMAL;
            case BOOL -> QueryFieldType.BOOLEAN;
            case DATE -> QueryFieldType.DATE;
            case TIMESTAMP -> QueryFieldType.TIMESTAMP;
            case OBJECT -> QueryFieldType.OBJECT;
            case ARRAY -> QueryFieldType.ARRAY;
        };
    }
}
