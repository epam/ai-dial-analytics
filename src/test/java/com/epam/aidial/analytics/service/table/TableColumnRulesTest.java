package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link TableColumnRules#resolveNullable} (explicit nullability wins, enrichment
 * columns default nullable, and an {@code array} column always resolves non-nullable — ClickHouse has
 * no {@code Nullable(Array(...))}), {@link TableColumnRules#normalizeTagOrder}, and the {@code enum}
 * column rules ({@link TableColumnRules#normalizeEnumValues} plus the required-iff-{@code enum}
 * declaration check).
 */
class TableColumnRulesTest {

    private static ColumnInput col(QueryFieldType type, QueryFieldType elementType, Boolean nullable) {
        return new ColumnInput("c", "c", type, elementType, null, nullable, null, null, null, false, false);
    }

    @Test
    void explicitNullabilityWins() {
        assertThat(TableColumnRules.resolveNullable(
                col(QueryFieldType.STRING, null, true), TableType.SOURCE)).isTrue();
        assertThat(TableColumnRules.resolveNullable(
                col(QueryFieldType.STRING, null, false), TableType.ENRICHMENT)).isFalse();
    }

    @Test
    void mapColumnAlwaysResolvesNonNullable() {
        // ClickHouse has no Nullable(Map); an absent attribute bag is the empty map, on either table kind.
        assertThat(TableColumnRules.resolveNullable(
                col(QueryFieldType.MAP, null, null), TableType.SOURCE)).isFalse();
        assertThat(TableColumnRules.resolveNullable(
                col(QueryFieldType.MAP, null, null), TableType.ENRICHMENT)).isFalse();
    }

    @Test
    void explicitlyNullableMapColumnIsRejected() {
        assertThatThrownBy(() -> TableColumnRules.requireValidUserColumn(col(QueryFieldType.MAP, null, true)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("cannot be nullable");
    }

    @Test
    void mapColumnWithAnElementTypeIsRejected() {
        assertThatThrownBy(() -> TableColumnRules.requireValidUserColumn(
                col(QueryFieldType.MAP, QueryFieldType.STRING, null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("element_type is only valid for array columns");
    }

    @Test
    void mapIsNotValidArrayElementType() {
        assertThatThrownBy(() -> TableColumnRules.requireValidUserColumn(
                col(QueryFieldType.ARRAY, QueryFieldType.MAP, null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("array element_type must be scalar");
    }

    @Test
    void omittedNullabilityDefaultsByTableKind() {
        assertThat(TableColumnRules.resolveNullable(
                col(QueryFieldType.STRING, null, null), TableType.SOURCE)).isFalse();
        assertThat(TableColumnRules.resolveNullable(
                col(QueryFieldType.STRING, null, null), TableType.ENRICHMENT)).isTrue();
    }

    @Test
    void arrayColumnResolvesNonNullableEvenOnEnrichment() {
        assertThat(TableColumnRules.resolveNullable(
                col(QueryFieldType.ARRAY, QueryFieldType.STRING, null), TableType.ENRICHMENT)).isFalse();
    }

    @Test
    void reservedPrefixColumnIsRejectedRegardlessOfWriteDiscipline() {
        // The `_`-prefix is ADAS-owned, so the version column an upsert_by_key source carries is not
        // declarable — the same generic rule that already protects an enrichment's `_updated_at`. The rule
        // is kind- and discipline-agnostic, which is why the write facet needed no new reservation.
        final ColumnInput versionColumn =
                new ColumnInput("_updated_at", "_updated_at", QueryFieldType.TIMESTAMP,
                        null, null, null, null, null, null, false, false);
        assertThatThrownBy(() -> TableColumnRules.requireValidUserColumn(versionColumn))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void nullOrEmptyNormalizesToNull() {
        assertThat(TableColumnRules.normalizeTagOrder(null)).isNull();
        assertThat(TableColumnRules.normalizeTagOrder(List.of())).isNull();
    }

    @Test
    void entriesAreTrimmedAndOrderIsPreserved() {
        assertThat(TableColumnRules.normalizeTagOrder(List.of("  identifier ", "cost")))
                .containsExactly("identifier", "cost");
    }

    @Test
    void nonExistentTagNamesAreAccepted() {
        // The ordering is a decoupled hint: an entry need not match any column tag.
        assertThat(TableColumnRules.normalizeTagOrder(List.of("no_such_tag", "another")))
                .containsExactly("no_such_tag", "another");
    }

    @Test
    void blankEntryIsRejected() {
        final List<String> withBlank = java.util.Arrays.asList("identifier", "   ");
        assertThatThrownBy(() -> TableColumnRules.normalizeTagOrder(withBlank))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void overLongEntryIsRejected() {
        final List<String> tooLong = List.of("x".repeat(TableColumnRules.MAX_TAG_LENGTH + 1));
        assertThatThrownBy(() -> TableColumnRules.normalizeTagOrder(tooLong))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void duplicateEntriesAfterTrimAreRejected() {
        assertThatThrownBy(() -> TableColumnRules.normalizeTagOrder(List.of("cost", " cost ")))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void oversizeListIsRejected() {
        final List<String> tooMany = IntStream.rangeClosed(0, TableColumnRules.MAX_TAG_ORDER_ENTRIES)
                .mapToObj(i -> "tag_" + i)
                .toList();
        assertThat(tooMany).hasSize(TableColumnRules.MAX_TAG_ORDER_ENTRIES + 1);
        assertThatThrownBy(() -> TableColumnRules.normalizeTagOrder(tooMany))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void maxEntriesIsAccepted() {
        final List<String> exactly = IntStream.range(0, TableColumnRules.MAX_TAG_ORDER_ENTRIES)
                .mapToObj(i -> "tag_" + i)
                .toList();
        assertThat(TableColumnRules.normalizeTagOrder(exactly)).hasSize(TableColumnRules.MAX_TAG_ORDER_ENTRIES);
    }

    @Test
    void unmodifiableInputIsHandled() {
        // Guard against accidental mutation of the caller's list.
        assertThat(TableColumnRules.normalizeTagOrder(Collections.singletonList("cost")))
                .containsExactly("cost");
    }

    // ------------------------------------------------------------------ enum columns

    private static ColumnInput enumCol(QueryFieldType type, List<String> enumValues) {
        return new ColumnInput("c", "c", type, null, enumValues, null, null, null, null, false, false);
    }

    @Test
    void enumColumnIsAcceptedWithItsDomain() {
        assertThatNoException().isThrownBy(() -> TableColumnRules.requireValidUserColumn(
                enumCol(QueryFieldType.ENUM, List.of("pending", "running"))));
    }

    /**
     * An enum column has no nullability restriction, unlike array and map: ClickHouse allows
     * {@code Nullable(Enum8(...))}, so it follows the ordinary kind-based default.
     */
    @Test
    void enumColumnFollowsOrdinaryNullabilityDefaults() {
        assertThat(TableColumnRules.resolveNullable(
                enumCol(QueryFieldType.ENUM, List.of("a")), TableType.SOURCE)).isFalse();
        assertThat(TableColumnRules.resolveNullable(
                enumCol(QueryFieldType.ENUM, List.of("a")), TableType.ENRICHMENT)).isTrue();
    }

    @Test
    void enumColumnWithoutValuesIsRejected() {
        assertThatThrownBy(() -> TableColumnRules.requireValidUserColumn(enumCol(QueryFieldType.ENUM, null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("requires a non-empty enum_values");
        assertThatThrownBy(() -> TableColumnRules.requireValidUserColumn(
                enumCol(QueryFieldType.ENUM, List.of())))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("requires a non-empty enum_values");
    }

    @Test
    void enumValuesOnNonEnumColumnAreRejected() {
        assertThatThrownBy(() -> TableColumnRules.requireValidUserColumn(
                enumCol(QueryFieldType.STRING, List.of("a"))))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("enum_values is only valid for enum columns");
    }

    @Test
    void enumIsRejectedAsAnArrayElementType() {
        assertThatThrownBy(() -> TableColumnRules.requireValidUserColumn(
                col(QueryFieldType.ARRAY, QueryFieldType.ENUM, null)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("array element_type must be scalar");
    }

    /** Order is the id assignment, so it is preserved rather than normalized away. */
    @Test
    void enumValuesAreTrimmedAndKeepDeclaredOrder() {
        assertThat(TableColumnRules.normalizeEnumValues(List.of(" high ", "low", "medium")))
                .containsExactly("high", "low", "medium");
    }

    @Test
    void blankEnumValueIsRejected() {
        assertThatThrownBy(() -> TableColumnRules.normalizeEnumValues(List.of("a", "  ")))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("must be non-blank");
    }

    @Test
    void duplicateEnumValuesAreRejected() {
        // Compared after trimming, so a padded duplicate is still a duplicate.
        assertThatThrownBy(() -> TableColumnRules.normalizeEnumValues(List.of("a", " a ")))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("duplicate value 'a'");
    }

    @Test
    void overLongEnumValueIsRejected() {
        final String tooLong = "v".repeat(TableColumnRules.MAX_ENUM_VALUE_LENGTH + 1);
        assertThatThrownBy(() -> TableColumnRules.normalizeEnumValues(List.of(tooLong)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("at most " + TableColumnRules.MAX_ENUM_VALUE_LENGTH + " characters");
    }

    @Test
    void tooManyEnumValuesAreRejected() {
        final List<String> tooMany = IntStream.rangeClosed(0, TableColumnRules.MAX_ENUM_VALUES)
                .mapToObj(i -> "v" + i)
                .toList();
        assertThatThrownBy(() -> TableColumnRules.normalizeEnumValues(tooMany))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("at most " + TableColumnRules.MAX_ENUM_VALUES + " values");
    }

    @Test
    void maxEnumValuesIsAccepted() {
        final List<String> exactly = IntStream.range(0, TableColumnRules.MAX_ENUM_VALUES)
                .mapToObj(i -> "v" + i)
                .toList();
        assertThat(TableColumnRules.normalizeEnumValues(exactly)).hasSize(TableColumnRules.MAX_ENUM_VALUES);
    }
}
