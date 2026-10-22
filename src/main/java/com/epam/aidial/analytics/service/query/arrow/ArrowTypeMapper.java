package com.epam.aidial.analytics.service.query.arrow;

import com.epam.aidial.analytics.service.query.sql.ResultColumn;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * The single normative {@link ResultColumnKind} → Arrow type mapping. All fields are
 * nullable; timestamps are millisecond-precision UTC; decimals are {@code Decimal128(38, 9)}.
 * Array columns map to Arrow {@code List} with a single child field named {@value #LIST_ITEM_NAME}
 * typed by the element kind's scalar mapping (e.g. an array of strings is {@code List<item: Utf8>}).
 * Object columns map to {@code Utf8} carrying JSON text: a ClickHouse {@code JSON} column is
 * schemaless and row-heterogeneous, so no Arrow {@code Struct} (one fixed field set per batch) or
 * {@code Map} (homogeneous, flattens nesting) can represent it faithfully.
 * Both {@code getFlightInfo} and {@code doGet} derive their schema through this class, so the two
 * always agree.
 */
public final class ArrowTypeMapper {

    public static final int DECIMAL_PRECISION = 38;
    public static final int DECIMAL_SCALE = 9;

    /** Name of the element child field of a list (array) column. */
    public static final String LIST_ITEM_NAME = "item";

    private ArrowTypeMapper() {}

    /** The Arrow schema for a translated query's result columns. */
    public static Schema schema(List<ResultColumn> columns) {
        final List<Field> fields = new ArrayList<>(columns.size());
        for (final ResultColumn column : columns) {
            fields.add(field(column));
        }
        return new Schema(fields);
    }

    /**
     * The Arrow field for one result column; array columns carry their typed element child, and an
     * object column is {@code Utf8} (JSON text). The object mapping lives here rather than in
     * {@link #arrowType} so that {@code arrowType} stays a <em>scalar-only</em> table: an array
     * whose element kind is {@code ARRAY} or {@code OBJECT} is not writable, and failing at schema
     * time is better than advertising a schema the batch writer then chokes on mid-stream. (The
     * catalog rejects such element types at declaration, so this is a guard, not a live path.)
     */
    public static Field field(ResultColumn column) {
        if (column.kind() == ResultColumnKind.ARRAY) {
            final Field item = new Field(
                    LIST_ITEM_NAME, FieldType.nullable(arrowType(column.elementKind())), null);
            return new Field(column.name(), FieldType.nullable(ArrowType.List.INSTANCE), List.of(item));
        }
        if (column.kind() == ResultColumnKind.OBJECT) {
            return new Field(column.name(), FieldType.nullable(ArrowType.Utf8.INSTANCE), null);
        }
        return new Field(column.name(), FieldType.nullable(arrowType(column.kind())), null);
    }

    /** The Arrow type for one scalar result column kind. */
    public static ArrowType arrowType(ResultColumnKind kind) {
        return switch (kind) {
            case STRING -> ArrowType.Utf8.INSTANCE;
            case INT32 -> new ArrowType.Int(32, true);
            case INT64 -> new ArrowType.Int(64, true);
            case FLOAT64 -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
            case DECIMAL -> new ArrowType.Decimal(DECIMAL_PRECISION, DECIMAL_SCALE, 128);
            case BOOL -> ArrowType.Bool.INSTANCE;
            case DATE -> new ArrowType.Date(org.apache.arrow.vector.types.DateUnit.DAY);
            case TIMESTAMP -> new ArrowType.Timestamp(TimeUnit.MILLISECOND, "UTC");
            case ARRAY, OBJECT -> throw new IllegalArgumentException(
                    kind + " has no scalar Arrow type; map it via field(ResultColumn)");
        };
    }
}
