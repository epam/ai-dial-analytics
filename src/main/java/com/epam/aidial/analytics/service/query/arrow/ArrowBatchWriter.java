package com.epam.aidial.analytics.service.query.arrow;

import com.epam.aidial.analytics.service.query.sql.ResultColumn;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeStampMilliTZVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Fills a {@link VectorSchemaRoot} from the structured-query executor's row maps and hands each
 * full batch to a sink. A batch
 * flushes when it reaches {@code batchRows} rows or when the per-request allocator has
 * {@code batchMaxBytes} allocated; the root is cleared after each flush so the byte bound measures
 * per-batch volume. Null cells (including unmatched enrichment LEFT JOIN columns) become Arrow
 * nulls. Value coercion accepts the Java types the ClickHouse client-v2 surfaces; timestamps
 * without an explicit zone are interpreted as UTC, matching the storage convention. Array cells
 * (a {@link Collection} or Java array after the executor's {@code ArrayValue} unwrap) are written
 * as {@link ListVector} values whose elements reuse the element kind's scalar coercion — an empty
 * array is an empty (non-null) list, a null cell a null list. Object cells (ClickHouse
 * {@code JSON}, surfaced by the client as a {@link Map}) are serialized to compact JSON text with
 * the caller's {@link JsonMapper} and written into a {@code VarCharVector} — never
 * {@code toString}, which would emit Java map notation rather than JSON.
 */
public final class ArrowBatchWriter {

    private ArrowBatchWriter() {}

    /**
     * Writes {@code rows} into {@code root} in batches, invoking {@code sink} for each filled batch
     * (row count already set). The caller owns the root's lifecycle; an empty result invokes the sink
     * zero times (the stream's schema message alone describes the result). {@code jsonMapper}
     * serializes {@code object} cells to JSON text.
     */
    public static void drain(
            VectorSchemaRoot root,
            List<Map<String, Object>> rows,
            List<ResultColumn> columns,
            int batchRows,
            long batchMaxBytes,
            BufferAllocator allocator,
            JsonMapper jsonMapper,
            Consumer<VectorSchemaRoot> sink) {
        int rowInBatch = 0;
        for (final Map<String, Object> row : rows) {
            for (int col = 0; col < columns.size(); col++) {
                final ResultColumn column = columns.get(col);
                setValue(root.getVector(col), rowInBatch, column, row.get(column.name()), jsonMapper);
            }
            rowInBatch++;
            if (rowInBatch >= batchRows || allocator.getAllocatedMemory() >= batchMaxBytes) {
                flush(root, rowInBatch, sink);
                rowInBatch = 0;
            }
        }
        if (rowInBatch > 0) {
            flush(root, rowInBatch, sink);
        }
    }

    private static void flush(VectorSchemaRoot root, int rowCount, Consumer<VectorSchemaRoot> sink) {
        root.setRowCount(rowCount);
        sink.accept(root);
        root.clear();
    }

    private static void setValue(
            FieldVector vector, int index, ResultColumn column, Object value, JsonMapper jsonMapper) {
        if (value == null) {
            vector.setNull(index);
            return;
        }
        try {
            switch (column.kind()) {
                case ARRAY -> writeList((ListVector) vector, index, column.elementKind(), value);
                case OBJECT -> ((VarCharVector) vector)
                        .setSafe(index, jsonText(value, jsonMapper).getBytes(StandardCharsets.UTF_8));
                default -> setScalar(vector, index, column.kind(), value);
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "cannot write value of type " + value.getClass().getSimpleName() + " into "
                            + column.kind() + " column '" + column.name() + "'", e);
        }
    }

    private static void setScalar(FieldVector vector, int index, ResultColumnKind kind, Object value) {
        switch (kind) {
            case STRING -> ((VarCharVector) vector)
                    .setSafe(index, value.toString().getBytes(StandardCharsets.UTF_8));
            case INT32 -> ((IntVector) vector).setSafe(index, toNumber(value).intValue());
            case INT64 -> ((BigIntVector) vector).setSafe(index, toNumber(value).longValue());
            case FLOAT64 -> ((Float8Vector) vector).setSafe(index, toNumber(value).doubleValue());
            case DECIMAL -> ((DecimalVector) vector).setSafe(index, toDecimal(value));
            case BOOL -> ((BitVector) vector).setSafe(index, toBoolean(value) ? 1 : 0);
            case DATE -> ((DateDayVector) vector).setSafe(index, (int) toLocalDate(value).toEpochDay());
            case TIMESTAMP -> ((TimeStampMilliTZVector) vector).setSafe(index, toEpochMillis(value));
            default -> throw new IllegalStateException("no scalar coercion for column kind " + kind);
        }
    }

    /**
     * Writes one array cell: a new list value whose elements are coerced by the element kind's
     * scalar coercion into the list's data vector. The element kind defaults to STRING when absent
     * (matching the result-schema derivation's defensive default).
     */
    private static void writeList(ListVector vector, int index, ResultColumnKind elementKind, Object value) {
        final List<?> items = listItems(value);
        final ResultColumnKind kind = elementKind == null ? ResultColumnKind.STRING : elementKind;
        final int start = vector.startNewValue(index);
        final FieldVector elements = vector.getDataVector();
        for (int i = 0; i < items.size(); i++) {
            final Object item = items.get(i);
            if (item == null) {
                elements.setNull(start + i);
            } else {
                setScalar(elements, start + i, kind, item);
            }
        }
        vector.endValue(index, items.size());
    }

    /**
     * Renders one object cell as compact JSON text. The ClickHouse client surfaces a {@code JSON}
     * column as a {@link Map} (nested values as maps/lists/boxed scalars), whose {@code toString} is
     * Java map notation rather than JSON — so every cell goes through Jackson, whatever its Java
     * type. Deliberately no {@code String} pass-through: the REST surfaces serialize the same cell
     * with the same mapper, so encoding it any other way here would make Arrow and JSON disagree on
     * a value the specs require them to agree on. Agreement is the invariant, not a guess about
     * whether a {@code String} cell was "really" pre-serialized JSON.
     */
    private static String jsonText(Object value, JsonMapper jsonMapper) {
        return jsonMapper.writeValueAsString(value);
    }

    /** Normalizes an array cell to a list: accepts any {@link Collection} or Java array. */
    private static List<?> listItems(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection instanceof List<?> list ? list : new ArrayList<>(collection);
        }
        if (value.getClass().isArray()) {
            final int length = Array.getLength(value);
            final List<Object> items = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                items.add(Array.get(value, i));
            }
            return items;
        }
        throw new IllegalStateException("array cell is neither a Collection nor an array");
    }

    private static Number toNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        return new BigDecimal(value.toString());
    }

    private static BigDecimal toDecimal(Object value) {
        final BigDecimal decimal = value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
        return decimal.setScale(ArrowTypeMapper.DECIMAL_SCALE, RoundingMode.HALF_UP);
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static LocalDate toLocalDate(Object value) {
        return switch (value) {
            case LocalDate date -> date;
            case LocalDateTime dateTime -> dateTime.toLocalDate();
            case ZonedDateTime zoned -> zoned.toLocalDate();
            default -> LocalDate.parse(value.toString());
        };
    }

    private static long toEpochMillis(Object value) {
        return switch (value) {
            case LocalDateTime local -> local.toInstant(ZoneOffset.UTC).toEpochMilli();
            case ZonedDateTime zoned -> zoned.toInstant().toEpochMilli();
            case OffsetDateTime offset -> offset.toInstant().toEpochMilli();
            case Instant instant -> instant.toEpochMilli();
            case LocalDate date -> date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            case Number number -> number.longValue();
            default -> Instant.parse(value.toString()).toEpochMilli();
        };
    }
}
