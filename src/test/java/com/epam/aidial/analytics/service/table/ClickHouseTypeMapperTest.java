package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit coverage for the {@link QueryFieldType} → ClickHouse DDL type mapping. */
class ClickHouseTypeMapperTest {

    private final ClickHouseTypeMapper mapper = new ClickHouseTypeMapper();

    @Test
    void mapsScalarTypes() {
        assertThat(mapper.toDdlType(QueryFieldType.STRING, null, null, false)).isEqualTo("String");
        assertThat(mapper.toDdlType(QueryFieldType.INTEGER, null, null, false)).isEqualTo("Int32");
        assertThat(mapper.toDdlType(QueryFieldType.LONG, null, null, false)).isEqualTo("Int64");
        assertThat(mapper.toDdlType(QueryFieldType.DECIMAL, null, null, false)).isEqualTo("Decimal(38, 12)");
        assertThat(mapper.toDdlType(QueryFieldType.BOOLEAN, null, null, false)).isEqualTo("Bool");
        assertThat(mapper.toDdlType(QueryFieldType.DATE, null, null, false)).isEqualTo("Date");
        assertThat(mapper.toDdlType(QueryFieldType.TIMESTAMP, null, null, false)).isEqualTo("DateTime64(3, 'UTC')");
        assertThat(mapper.toDdlType(QueryFieldType.UUID, null, null, false)).isEqualTo("UUID");
        assertThat(mapper.toDdlType(QueryFieldType.OBJECT, null, null, false)).isEqualTo("JSON");
        assertThat(mapper.toDdlType(QueryFieldType.MAP, null, null, false)).isEqualTo("Map(String, String)");
    }

    /**
     * A zoneless {@code DateTime64} resolves to the ClickHouse server's zone, but the read path converts the
     * value to epoch millis at UTC and a materialized time bucket is computed in UTC — so leaving the zone
     * off would make both silently wrong by the server's offset on a non-UTC deployment.
     */
    @Test
    void timestampPinsUtcRatherThanInheritingTheServerZone() {
        assertThat(mapper.toDdlType(QueryFieldType.TIMESTAMP, null, null, false)).contains("'UTC'");
        assertThat(mapper.toDdlType(QueryFieldType.TIMESTAMP, null, null, true)).isEqualTo("Nullable(DateTime64(3, 'UTC'))");
    }

    @Test
    void mapsArrayWithElementType() {
        assertThat(mapper.toDdlType(QueryFieldType.ARRAY, QueryFieldType.STRING, null, false)).isEqualTo("Array(String)");
        assertThat(mapper.toDdlType(QueryFieldType.ARRAY, QueryFieldType.LONG, null, false)).isEqualTo("Array(Int64)");
    }

    @Test
    void arrayWithoutElementTypeIsRejected() {
        assertThatThrownBy(() -> mapper.toDdlType(QueryFieldType.ARRAY, null, null, false))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void elementTypeOnNonArrayIsRejected() {
        assertThatThrownBy(() -> mapper.toDdlType(QueryFieldType.STRING, QueryFieldType.STRING, null, false))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void nestedArrayElementIsRejected() {
        assertThatThrownBy(() -> mapper.toDdlType(QueryFieldType.ARRAY, QueryFieldType.ARRAY, null, false))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void objectArrayElementIsRejected() {
        assertThatThrownBy(() -> mapper.toDdlType(QueryFieldType.ARRAY, QueryFieldType.OBJECT, null, false))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void arrayIgnoresNullableFlag() {
        // ClickHouse forbids Nullable(Array); the mapper never wraps arrays (the validator rejects
        // an explicit nullable:true before rendering).
        assertThat(mapper.toDdlType(QueryFieldType.ARRAY, QueryFieldType.STRING, null, true)).isEqualTo("Array(String)");
    }

    @Test
    void wrapsNullableScalars() {
        assertThat(mapper.toDdlType(QueryFieldType.STRING, null, null, true)).isEqualTo("Nullable(String)");
        assertThat(mapper.toDdlType(QueryFieldType.LONG, null, null, true)).isEqualTo("Nullable(Int64)");
        assertThat(mapper.toDdlType(QueryFieldType.STRING, null, null, false)).isEqualTo("String");
    }

    @Test
    void doesNotWrapJsonInNullable() {
        // ClickHouse forbids Nullable(JSON); the mapper leaves OBJECT unwrapped even when nullable.
        assertThat(mapper.toDdlType(QueryFieldType.OBJECT, null, null, true)).isEqualTo("JSON");
    }

    @Test
    void doesNotWrapMapInNullable() {
        // ClickHouse forbids Nullable(Map) too; an absent attribute bag is the empty map, not null.
        assertThat(mapper.toDdlType(QueryFieldType.MAP, null, null, true)).isEqualTo("Map(String, String)");
    }

    @Test
    void mapIsNotValidArrayElementType() {
        assertThatThrownBy(() -> mapper.toDdlType(QueryFieldType.ARRAY, QueryFieldType.MAP, null, false))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("array elements must be scalar");
    }

    @Test
    void mapColumnRejectsAnElementType() {
        assertThatThrownBy(() -> mapper.toDdlType(QueryFieldType.MAP, QueryFieldType.STRING, null, false))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("element_type is only valid for ARRAY columns");
    }

    @Test
    void rendersEnumWithPositionalIds() {
        assertThat(mapper.toDdlType(
                QueryFieldType.ENUM, null, List.of("pending", "running", "failed"), false))
                .isEqualTo("Enum8('pending' = 1, 'running' = 2, 'failed' = 3)");
    }

    /**
     * Ids are assigned by declared position, so the same values in a different order are a different
     * physical type — which is exactly why the declared order is stored rather than normalized.
     */
    @Test
    void enumIdsFollowDeclarationOrderNotAlphabeticalOrder() {
        assertThat(mapper.toDdlType(QueryFieldType.ENUM, null, List.of("low", "medium", "high"), false))
                .isEqualTo("Enum8('low' = 1, 'medium' = 2, 'high' = 3)");
    }

    /** Ids start at 1, so Int8's 127 is the last that fits; a 128th value needs the wider type. */
    @Test
    void switchesToEnum16PastTheEnum8Range() {
        final List<String> justFits = values(127);
        assertThat(mapper.toDdlType(QueryFieldType.ENUM, null, justFits, false)).startsWith("Enum8(");
        assertThat(mapper.toDdlType(QueryFieldType.ENUM, null, values(128), false)).startsWith("Enum16(");
    }

    /** Unlike Array and Map, ClickHouse does allow Nullable over an Enum. */
    @Test
    void wrapsNullableEnum() {
        assertThat(mapper.toDdlType(QueryFieldType.ENUM, null, List.of("a"), true))
                .isEqualTo("Nullable(Enum8('a' = 1))");
    }

    /**
     * Enum values are free-form caller text becoming SQL syntax, so this is the one escaping-sensitive
     * spot in the DDL path: a quote must close nothing, and a backslash must not introduce an escape.
     */
    @Test
    void escapesQuotesAndBackslashesInEnumValues() {
        assertThat(mapper.toDdlType(QueryFieldType.ENUM, null, List.of("it's"), false))
                .isEqualTo("Enum8('it''s' = 1)");
        assertThat(mapper.toDdlType(QueryFieldType.ENUM, null, List.of("a\\b"), false))
                .isEqualTo("Enum8('a\\\\b' = 1)");
        // The shape an injection attempt would take: it stays one literal rather than closing the type.
        assertThat(mapper.toDdlType(QueryFieldType.ENUM, null, List.of("x') ENGINE = Log --"), false))
                .isEqualTo("Enum8('x'') ENGINE = Log --' = 1)");
    }

    @Test
    void enumColumnRequiresValues() {
        assertThatThrownBy(() -> mapper.toDdlType(QueryFieldType.ENUM, null, null, false))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("ENUM column requires a non-empty enum_values");
        assertThatThrownBy(() -> mapper.toDdlType(QueryFieldType.ENUM, null, List.of(), false))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("ENUM column requires a non-empty enum_values");
    }

    @Test
    void nonEnumColumnRejectsEnumValues() {
        assertThatThrownBy(() -> mapper.toDdlType(QueryFieldType.STRING, null, List.of("a"), false))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("enum_values is only valid for ENUM columns");
    }

    @Test
    void enumIsNotValidArrayElementType() {
        assertThatThrownBy(() -> mapper.toDdlType(QueryFieldType.ARRAY, QueryFieldType.ENUM, null, false))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("array elements must be scalar");
    }

    private static List<String> values(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "v" + i).toList();
    }
}
