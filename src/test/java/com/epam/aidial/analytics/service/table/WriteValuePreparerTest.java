package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.table.WriteValuePreparer.ColumnShape;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the per-column dispatch of the row-write value checks: which column types get checked at
 * all, that each scalar type reaches its validator, the enum and map rules that live here, and the array
 * handling — no null element, each element checked as the element type, and a copy only when an element's
 * storage form differs.
 */
class WriteValuePreparerTest {

    private static final String COLUMN = "col";

    private final WriteValuePreparer preparer =
            new WriteValuePreparer(new WriteTimestampNormalizer(), new WriteDateValidator(), new WriteNumericRangeValidator());

    // --- shapeOf ---------------------------------------------------------------------------------------

    /** Types whose values ClickHouse stores faithfully or refuses need no shape, so the write path skips them. */
    @Test
    void shapeOfIsNullForTypesThatNeedNoChecks() {
        assertThat(List.of(QueryFieldType.STRING, QueryFieldType.BOOLEAN, QueryFieldType.UUID, QueryFieldType.OBJECT))
                .allSatisfy(type -> assertThat(WriteValuePreparer.shapeOf(column(type, null, null))).isNull());
    }

    @Test
    void shapeOfIsPresentForEveryCheckedType() {
        assertThat(List.of(QueryFieldType.TIMESTAMP, QueryFieldType.DATE, QueryFieldType.INTEGER, QueryFieldType.LONG,
                        QueryFieldType.DECIMAL, QueryFieldType.MAP))
                .allSatisfy(type -> assertThat(WriteValuePreparer.shapeOf(column(type, null, null)))
                        .isEqualTo(new ColumnShape(type, null, Set.of())));
        assertThat(WriteValuePreparer.shapeOf(column(QueryFieldType.ARRAY, QueryFieldType.STRING, null)))
                .isEqualTo(new ColumnShape(QueryFieldType.ARRAY, QueryFieldType.STRING, Set.of()));
    }

    @Test
    void shapeOfCarriesTheEnumDomainInDeclaredOrder() {
        final ColumnShape shape = WriteValuePreparer.shapeOf(column(QueryFieldType.ENUM, null, List.of("low", "mid", "high")));

        assertThat(shape.type()).isEqualTo(QueryFieldType.ENUM);
        assertThat(shape.enumValues()).containsExactly("low", "mid", "high");
    }

    // --- scalars ---------------------------------------------------------------------------------------

    /** Whether the column may hold a null is the write path's non-nullable check, which runs first. */
    @Test
    void passesNullThroughForEveryShape() {
        assertThat(List.of(scalar(QueryFieldType.TIMESTAMP), scalar(QueryFieldType.DATE), scalar(QueryFieldType.INTEGER),
                        scalar(QueryFieldType.MAP), enumShape("a"), array(QueryFieldType.TIMESTAMP)))
                .allSatisfy(shape -> assertThat(preparer.prepare(COLUMN, shape, null, 1)).isNull());
    }

    @Test
    void rendersTimestampIntoItsStorageSpelling() {
        assertThat(preparer.prepare(COLUMN, scalar(QueryFieldType.TIMESTAMP), "2026-08-11T13:00:00+03:00", 1))
                .isEqualTo("2026-08-11 10:00:00.000");
    }

    @Test
    void rejectsAnOutOfRangeTimestamp() {
        assertThatThrownBy(() -> preparer.prepare(COLUMN, scalar(QueryFieldType.TIMESTAMP), "2300-01-01T00:00:00Z", 3))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("row 3, timestamp column 'col'")
                .hasMessageContaining("out of range for timestamp");
    }

    @Test
    void passesValidDateThroughAsSupplied() {
        final String value = "2026-08-11";

        assertThat(preparer.prepare(COLUMN, scalar(QueryFieldType.DATE), value, 1)).isSameAs(value);
    }

    @Test
    void rejectsAnInvalidDate() {
        assertThat(List.<Object>of("2026-02-30", "2200-01-01", 20000))
                .allSatisfy(value -> assertThatThrownBy(() -> preparer.prepare(COLUMN, scalar(QueryFieldType.DATE), value, 2))
                        .isInstanceOf(TableValidationException.class)
                        .hasMessageContaining("row 2, date column 'col'"));
    }

    @Test
    void passesValidNumberThroughAsSupplied() {
        final Object integer = 42;
        final Object decimal = "94.25";

        assertThat(preparer.prepare(COLUMN, scalar(QueryFieldType.INTEGER), integer, 1)).isSameAs(integer);
        assertThat(preparer.prepare(COLUMN, scalar(QueryFieldType.DECIMAL), decimal, 1)).isSameAs(decimal);
    }

    /** ClickHouse coerces a boolean or an empty string into a numeric column without error. */
    @Test
    void rejectsNonNumbersInNumericColumn() {
        assertThat(List.<Object>of(true, "", "abc"))
                .allSatisfy(value -> assertThatThrownBy(() -> preparer.prepare(COLUMN, scalar(QueryFieldType.INTEGER), value, 1))
                        .isInstanceOf(TableValidationException.class)
                        .hasMessageContaining("integer column 'col'"));
        assertThatThrownBy(() -> preparer.prepare(COLUMN, scalar(QueryFieldType.LONG), 3e30, 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("long column 'col'");
    }

    // --- enum ------------------------------------------------------------------------------------------

    @Test
    void acceptsDeclaredEnumName() {
        final String value = "b";

        assertThat(preparer.prepare(COLUMN, enumShape("a", "b"), value, 1)).isSameAs(value);
    }

    @Test
    void rejectsAnUndeclaredEnumNameListingTheDomain() {
        assertThatThrownBy(() -> preparer.prepare(COLUMN, enumShape("a", "b"), "c", 5))
                .isInstanceOf(TableValidationException.class)
                .hasMessage("row 5, enum column 'col': value 'c' is not one of the declared values ('a', 'b')");
    }

    /** Enum names are case-sensitive; ClickHouse would refuse the miss opaquely. */
    @Test
    void rejectsAnEnumNameInTheWrongCase() {
        assertThatThrownBy(() -> preparer.prepare(COLUMN, enumShape("a", "b"), "A", 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("value 'A' is not one of the declared values");
    }

    /** ClickHouse maps a bare number to the element at that internal id, which is not the column's contract. */
    @Test
    void rejectsNumberForAnEnumEvenWhenItIsValidInternalId() {
        assertThat(List.<Object>of(1, 2, true))
                .allSatisfy(value -> assertThatThrownBy(() -> preparer.prepare(COLUMN, enumShape("a", "b"), value, 1))
                        .isInstanceOf(TableValidationException.class)
                        .hasMessageContaining("must be one of the declared values as a string, not a")
                        .hasMessageContaining("('a', 'b')"));
    }

    @Test
    void elidesLongEnumDomainInTheRejection() {
        final String[] names = IntStream.rangeClosed(1, 12).mapToObj(i -> "v" + i).toArray(String[]::new);

        assertThatThrownBy(() -> preparer.prepare(COLUMN, enumShape(names), "nope", 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("'v1', 'v2'")
                .hasMessageContaining("'v10', …)")
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("'v11'"));
    }

    // --- map -------------------------------------------------------------------------------------------

    @Test
    void passesMapWithoutNullValuesThroughAsSupplied() {
        final Map<String, Object> value = Map.of("k", "v", "empty", "");

        assertThat(preparer.prepare(COLUMN, scalar(QueryFieldType.MAP), value, 1)).isSameAs(value);
        assertThat(preparer.prepare(COLUMN, scalar(QueryFieldType.MAP), Map.of(), 1)).isEqualTo(Map.of());
    }

    /** ClickHouse would store {@code ''} for a null map value with no error. */
    @Test
    void rejectsNullMapValueNamingTheKey() {
        final Map<String, Object> value = new HashMap<>();
        value.put("k", null);

        assertThatThrownBy(() -> preparer.prepare(COLUMN, scalar(QueryFieldType.MAP), value, 7))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("row 7, map column 'col'")
                .hasMessageContaining("the value for key 'k' must not be null")
                .hasMessageContaining("omit the key instead");
    }

    /** A non-object value for a map column is ClickHouse's to refuse; this check only looks inside a map. */
    @Test
    void passesNonMapValueForMapColumnThrough() {
        final String value = "not a map";

        assertThat(preparer.prepare(COLUMN, scalar(QueryFieldType.MAP), value, 1)).isSameAs(value);
    }

    // --- arrays ----------------------------------------------------------------------------------------

    /** An array no element of which changes is handed on as the very same list — no needless copy. */
    @Test
    void returnsAnUntouchedArrayAsTheSameInstance() {
        final List<Object> integers = List.of(1, -2147483648, "7");
        final List<Object> dates = List.of("2026-08-11", "1970-01-01");
        final List<Object> strings = List.of("a", "");

        assertThat(preparer.prepare(COLUMN, array(QueryFieldType.INTEGER), integers, 1)).isSameAs(integers);
        assertThat(preparer.prepare(COLUMN, array(QueryFieldType.DATE), dates, 1)).isSameAs(dates);
        assertThat(preparer.prepare(COLUMN, array(QueryFieldType.STRING), strings, 1)).isSameAs(strings);
        assertThat(preparer.prepare(COLUMN, array(QueryFieldType.TIMESTAMP), List.of(), 1)).isEqualTo(List.of());
    }

    /** Timestamp elements get their storage spelling in a copy; the caller's list is never mutated. */
    @Test
    void rendersTimestampElementsIntoCopy() {
        final List<Object> original = new ArrayList<>(List.of("2026-08-11T13:00:00+03:00", "2026-08-11T10:00:00.5Z"));

        final Object prepared = preparer.prepare(COLUMN, array(QueryFieldType.TIMESTAMP), original, 1);

        assertThat(prepared).isNotSameAs(original)
                .isEqualTo(List.of("2026-08-11 10:00:00.000", "2026-08-11 10:00:00.500"));
        assertThat(original).containsExactly("2026-08-11T13:00:00+03:00", "2026-08-11T10:00:00.5Z");
    }

    /** Elements are never {@code Nullable}, so a null element would store the element type's default. */
    @Test
    void rejectsNullElementNamingItsIndexAndTheDefault() {
        final List<Object> elements = Arrays.asList(1, null, 3);

        assertThatThrownBy(() -> preparer.prepare(COLUMN, array(QueryFieldType.INTEGER), elements, 4))
                .isInstanceOf(TableValidationException.class)
                .hasMessage("row 4, array column 'col[1]': an array element must not be null; ClickHouse would store the integer"
                        + " default in its place");
        assertThatThrownBy(() -> preparer.prepare(COLUMN, array(QueryFieldType.STRING), Arrays.asList("a", null), 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("'col[1]'")
                .hasMessageContaining("string default");
    }

    @Test
    void checksEachElementAsTheElementTypeNamingItsIndex() {
        assertThatThrownBy(() -> preparer.prepare(COLUMN, array(QueryFieldType.TIMESTAMP),
                        List.of("2026-08-11T10:00:00Z", "2026-08-11T10:00:00"), 2))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("row 2, timestamp column 'col[1]'")
                .hasMessageContaining("has no time zone");
        assertThatThrownBy(() -> preparer.prepare(COLUMN, array(QueryFieldType.INTEGER), List.of(1, 3000000000L), 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("integer column 'col[1]'")
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> preparer.prepare(COLUMN, array(QueryFieldType.INTEGER), List.of(true), 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("'col[0]'");
        assertThatThrownBy(() -> preparer.prepare(COLUMN, array(QueryFieldType.DATE), List.of("2026-08-11", "2026-02-30"), 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("date column 'col[1]'");
    }

    /** A non-list value for an array column is ClickHouse's to refuse; the element checks only see a list. */
    @Test
    void passesNonListValueForAnArrayColumnThrough() {
        final String value = "not an array";

        assertThat(preparer.prepare(COLUMN, array(QueryFieldType.INTEGER), value, 1)).isSameAs(value);
    }

    /** Only the backfilled {@code dial_usage_log} can carry an array without a recorded element type. */
    @Test
    void returnsAnArrayWithoutAnElementTypeUnchecked() {
        final List<Object> elements = Arrays.asList(null, "x");

        assertThat(preparer.prepare(COLUMN, new ColumnShape(QueryFieldType.ARRAY, null, Set.of()), elements, 1)).isSameAs(elements);
    }

    private static ColumnMappingEntity column(QueryFieldType type, QueryFieldType elementType, List<String> enumValues) {
        final ColumnMappingEntity column = new ColumnMappingEntity();
        column.setTableName("t");
        column.setSourceName(COLUMN);
        column.setName(COLUMN);
        column.setType(type);
        column.setElementType(elementType);
        column.setEnumValues(enumValues);
        return column;
    }

    private static ColumnShape scalar(QueryFieldType type) {
        return WriteValuePreparer.shapeOf(column(type, null, null));
    }

    private static ColumnShape array(QueryFieldType elementType) {
        return WriteValuePreparer.shapeOf(column(QueryFieldType.ARRAY, elementType, null));
    }

    private static ColumnShape enumShape(String... names) {
        return WriteValuePreparer.shapeOf(column(QueryFieldType.ENUM, null, List.of(names)));
    }
}
