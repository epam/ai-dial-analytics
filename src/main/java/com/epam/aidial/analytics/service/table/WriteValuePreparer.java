package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates one caller-supplied row value against its column's declared type and returns the form handed to
 * {@link com.epam.aidial.analytics.clickhouse.CatalogRowWriter} — the value itself unless it is a
 * {@code timestamp} (or holds some), which is rendered into its storage spelling. Every check here exists
 * because ClickHouse, on a {@code JSONEachRow} insert, either stores such a value as something else with no
 * error or refuses the whole batch with an error naming neither row nor column:
 * <ul>
 *   <li>{@code timestamp} — {@link WriteTimestampNormalizer};</li>
 *   <li>{@code date} — {@link WriteDateValidator};</li>
 *   <li>{@code integer} / {@code long} / {@code decimal} — {@link WriteNumericRangeValidator};</li>
 *   <li>{@code enum} — the value must be one of the column's declared names. ClickHouse refuses an unknown
 *       name opaquely, and maps a bare number to the element at that internal id, which is not part of the
 *       column's contract;</li>
 *   <li>{@code array} — no element may be null: elements are never {@code Nullable}, so a null element stores
 *       the element type's default ({@code 0}, {@code ''}, {@code false}, the nil UUID) with no error. Every
 *       element is then checked as a value of the element type;</li>
 *   <li>{@code map} — no value may be null, for the same reason: a null stores {@code ''}.</li>
 * </ul>
 * A null value for the column itself is returned as-is: whether the column may hold it is the write path's
 * non-nullable check, which runs first.
 */
@Component
@RequiredArgsConstructor
public class WriteValuePreparer {

    /** Most declared enum names listed in a rejection; a long domain is elided after this many. */
    private static final int MAX_LISTED_ENUM_VALUES = 10;

    private final WriteTimestampNormalizer timestamps;
    private final WriteDateValidator dates;
    private final WriteNumericRangeValidator numerics;

    /**
     * The checks a column's value needs, or null when its type needs none ({@code string}, {@code boolean},
     * {@code uuid}, {@code object}, whose values ClickHouse either stores faithfully or refuses).
     */
    static ColumnShape shapeOf(ColumnMappingEntity column) {
        final ColumnShape shape = new ColumnShape(column.getType(), column.getElementType(),
                column.getEnumValues() == null ? Set.of() : new LinkedHashSet<>(column.getEnumValues()));
        return shape.needsChecks() ? shape : null;
    }

    /**
     * Returns {@code value} in the form to store for {@code column}.
     *
     * @param column    the physical column name, named in a rejection
     * @param shape     the column's type, from {@link #shapeOf}
     * @param value     the caller-supplied value
     * @param rowNumber the row's 1-based index within the batch, named in a rejection
     * @throws TableValidationException if the value does not fit the column's type
     */
    public Object prepare(String column, ColumnShape shape, Object value, int rowNumber) {
        if (value == null) {
            return null;
        }
        if (shape.type() == QueryFieldType.ARRAY) {
            return value instanceof List<?> elements ? prepareArray(column, shape.elementType(), elements, rowNumber) : value;
        }
        return prepareScalar(column, shape.type(), shape.enumValues(), value, rowNumber);
    }

    private Object prepareScalar(String column, QueryFieldType type, Set<String> enumValues, Object value, int rowNumber) {
        switch (type) {
            case TIMESTAMP -> {
                return timestamps.normalize(column, value, rowNumber);
            }
            case DATE -> dates.check(column, value, rowNumber);
            case INTEGER, LONG, DECIMAL -> numerics.check(column, type, value, rowNumber);
            case ENUM -> checkEnum(column, enumValues, value, rowNumber);
            case MAP -> checkMap(column, value, rowNumber);
            default -> {
                // Stored faithfully or refused by ClickHouse; nothing to check.
            }
        }
        return value;
    }

    /** {@code elements}, or a copy with each element's storage form when any element changed. */
    private List<?> prepareArray(String column, QueryFieldType elementType, List<?> elements, int rowNumber) {
        if (elementType == null) {
            return elements;
        }
        List<Object> copy = null;
        for (int i = 0; i < elements.size(); i++) {
            final Object element = elements.get(i);
            final String label = column + "[" + i + "]";
            if (element == null) {
                throw new TableValidationException("row " + rowNumber + ", array column '" + label
                        + "': an array element must not be null; ClickHouse would store the "
                        + elementType.code() + " default in its place");
            }
            final Object prepared = prepareScalar(label, elementType, Set.of(), element, rowNumber);
            if (prepared != element && copy == null) {
                copy = new ArrayList<>(elements);
            }
            if (copy != null) {
                copy.set(i, prepared);
            }
        }
        return copy == null ? elements : copy;
    }

    private static void checkEnum(String column, Set<String> enumValues, Object value, int rowNumber) {
        if (value instanceof String text && enumValues.contains(text)) {
            return;
        }
        final String declared = enumValues.stream().limit(MAX_LISTED_ENUM_VALUES).map(v -> "'" + v + "'")
                .reduce((a, b) -> a + ", " + b).orElse("")
                + (enumValues.size() > MAX_LISTED_ENUM_VALUES ? ", …" : "");
        throw new TableValidationException("row " + rowNumber + ", enum column '" + column + "': "
                + (value instanceof String ? "value " + WriteValueText.echo(value) + " is not one of the declared values"
                        : "must be one of the declared values as a string, not " + WriteValueText.describeJsonType(value))
                + " (" + declared + ")");
    }

    private static void checkMap(String column, Object value, int rowNumber) {
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() == null) {
                throw new TableValidationException("row " + rowNumber + ", map column '" + column + "': the value for key "
                        + WriteValueText.echo(String.valueOf(entry.getKey())) + " must not be null; ClickHouse would store ''"
                        + " in its place — omit the key instead");
            }
        }
    }

    /**
     * A column's declared type, as far as the value checks need it.
     *
     * @param type        the column's type
     * @param elementType the element type when {@code type} is {@code ARRAY}, else null
     * @param enumValues  the declared names when {@code type} is {@code ENUM}, else empty
     */
    record ColumnShape(QueryFieldType type, QueryFieldType elementType, Set<String> enumValues) {

        private boolean needsChecks() {
            return switch (type) {
                case TIMESTAMP, DATE, INTEGER, LONG, DECIMAL, ENUM, MAP, ARRAY -> true;
                default -> false;
            };
        }
    }
}
