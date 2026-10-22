package com.epam.aidial.analytics.service.pipeline.preview;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit coverage for the preview's two target-coercion notes. */
class TargetCoercionTest {

    private static PreviewColumn measure(String name, QueryFieldType type, QueryFieldType target) {
        return new PreviewColumn(name, PreviewColumnRole.MEASURE, type, target);
    }

    @Test
    void countIntoAnIntegerNarrowsWithoutAnyValueOutOfRange() {
        final List<PreviewNote> notes = TargetCoercion.notes(
                List.of(measure("calls", QueryFieldType.LONG, QueryFieldType.INTEGER)),
                List.of(Map.of("calls", BigInteger.valueOf(42))), Set.of());

        assertThat(notes).extracting(PreviewNote::code).containsExactly(PreviewNoteCode.TARGET_TYPE_NARROWING);
        assertThat(notes.getFirst().subject()).isEqualTo("calls");
    }

    @Test
    void sumPastThirtyTwoBitsIsBothNarrowingAndOutOfRange() {
        final List<PreviewNote> notes = TargetCoercion.notes(
                List.of(measure("tokens", QueryFieldType.LONG, QueryFieldType.INTEGER)),
                List.of(Map.of("tokens", 3_000_000_000L), Map.of("tokens", 7L)), Set.of());

        assertThat(notes).extracting(PreviewNote::code).containsExactly(
                PreviewNoteCode.TARGET_TYPE_NARROWING, PreviewNoteCode.VALUE_OUT_OF_TARGET_RANGE);
        assertThat(notes.get(1).message()).startsWith("1 sampled value").contains("wrapped value without an error");
    }

    @Test
    void decimalOutOfRangeSaysTheRefreshWouldFail() {
        final List<PreviewNote> notes = TargetCoercion.notes(
                List.of(measure("cost", QueryFieldType.DECIMAL, QueryFieldType.DECIMAL)),
                List.of(Map.of("cost", new BigDecimal("1e26"))), Set.of());

        assertThat(notes).extracting(PreviewNote::code).containsExactly(PreviewNoteCode.VALUE_OUT_OF_TARGET_RANGE);
        assertThat(notes.getFirst().message()).contains("refresh would fail with a decimal overflow").doesNotContain("without an error");
    }

    @Test
    void dateOutOfRangeSaysTheValueIsClamped() {
        final List<PreviewNote> notes = TargetCoercion.notes(
                List.of(measure("day", QueryFieldType.DATE, QueryFieldType.DATE)),
                List.of(Map.of("day", LocalDate.of(2149, 6, 7))), Set.of());

        assertThat(notes.getFirst().message()).contains("clamped value without an error");
    }

    @Test
    void nonFiniteFloatingPointIsNeverHeldByNumericTarget() {
        for (final QueryFieldType target : List.of(QueryFieldType.INTEGER, QueryFieldType.LONG, QueryFieldType.DECIMAL)) {
            assertThat(TargetCoercion.holds(target, Double.NaN)).as("%s NaN", target).isFalse();
            assertThat(TargetCoercion.holds(target, Double.POSITIVE_INFINITY)).as("%s +Inf", target).isFalse();
            assertThat(TargetCoercion.holds(target, Double.NEGATIVE_INFINITY)).as("%s -Inf", target).isFalse();
            assertThat(TargetCoercion.holds(target, Float.NaN)).as("%s float NaN", target).isFalse();
            assertThat(TargetCoercion.holds(target, 1.5d)).as("%s finite", target).isTrue();
        }
    }

    @Test
    void matchingTypeIsNeitherNarrowingNorOutOfRange() {
        assertThat(TargetCoercion.notes(List.of(measure("tokens", QueryFieldType.LONG, QueryFieldType.LONG)),
                List.of(Map.of("tokens", 3_000_000_000L)), Set.of())).isEmpty();
    }

    @Test
    void temporalNarrowingCountsOnlyForValuePreservingMeasure() {
        final PreviewColumn latest = measure("latest", QueryFieldType.TIMESTAMP, QueryFieldType.DATE);

        assertThat(TargetCoercion.notes(List.of(latest), List.of(), Set.of("latest")))
                .extracting(PreviewNote::code).containsExactly(PreviewNoteCode.TARGET_TYPE_NARROWING);
        assertThat(TargetCoercion.notes(List.of(latest), List.of(), Set.of())).isEmpty();
    }

    @Test
    void columnWithNoTargetIsNotChecked() {
        assertThat(TargetCoercion.notes(List.of(new PreviewColumn("id", PreviewColumnRole.GRAIN_KEY,
                QueryFieldType.LONG, null)), List.of(Map.of("id", Long.MAX_VALUE)), Set.of())).isEmpty();
    }

    @Test
    void holdsDispatchesEachSampledJavaTypeToTheWritePathRange() {
        assertThat(TargetCoercion.holds(QueryFieldType.INTEGER, Integer.MAX_VALUE)).isTrue();
        assertThat(TargetCoercion.holds(QueryFieldType.INTEGER, (long) Integer.MAX_VALUE + 1)).isFalse();
        assertThat(TargetCoercion.holds(QueryFieldType.LONG, new BigInteger("9223372036854775808"))).isFalse();
        assertThat(TargetCoercion.holds(QueryFieldType.LONG, new BigDecimal("12.75"))).isTrue();
        assertThat(TargetCoercion.holds(QueryFieldType.DECIMAL, new BigDecimal("1e26"))).isFalse();
        assertThat(TargetCoercion.holds(QueryFieldType.DECIMAL, new BigDecimal("99999999999999999999999999"))).isTrue();
        assertThat(TargetCoercion.holds(QueryFieldType.DATE, LocalDate.of(2149, 6, 7))).isFalse();
        assertThat(TargetCoercion.holds(QueryFieldType.DATE, LocalDate.of(2026, 10, 12))).isTrue();
        assertThat(TargetCoercion.holds(QueryFieldType.TIMESTAMP,
                OffsetDateTime.of(1899, 12, 31, 23, 0, 0, 0, ZoneOffset.UTC))).isFalse();
        assertThat(TargetCoercion.holds(QueryFieldType.TIMESTAMP,
                OffsetDateTime.of(2026, 10, 12, 0, 0, 0, 0, ZoneOffset.UTC))).isTrue();
        assertThat(TargetCoercion.holds(QueryFieldType.STRING, "anything")).isTrue();
    }
}
