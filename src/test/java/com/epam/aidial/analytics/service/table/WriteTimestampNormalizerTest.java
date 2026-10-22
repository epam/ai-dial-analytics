package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the one accepted row-write timestamp format (ISO-8601 with an explicit zone) and for the
 * discriminated rejections — each message has to name the specific mistake, since that is what lets a caller
 * fix the request from the response alone.
 */
class WriteTimestampNormalizerTest {

    private static final String COLUMN = "recorded_at";

    private final WriteTimestampNormalizer normalizer = new WriteTimestampNormalizer();

    @Test
    void acceptsUtcDesignator() {
        assertThat(normalize("2026-08-11T10:00:00Z")).isEqualTo("2026-08-11 10:00:00.000");
    }

    /** An explicit offset denotes an instant, so it is stored as that instant in the column's UTC zone. */
    @Test
    void acceptsExplicitOffsetAndResolvesItToTheInstant() {
        assertThat(normalize("2026-08-11T13:00:00+03:00")).isEqualTo("2026-08-11 10:00:00.000");
        assertThat(normalize("2026-08-11T07:00:00-03:00")).isEqualTo("2026-08-11 10:00:00.000");
        assertThat(normalize("2026-08-11T10:00:00+00:00")).isEqualTo("2026-08-11 10:00:00.000");
    }

    @Test
    void acceptsOmittedSeconds() {
        assertThat(normalize("2026-08-11T10:00Z")).isEqualTo("2026-08-11 10:00:00.000");
        assertThat(normalize("2026-08-11T13:00+03:00")).isEqualTo("2026-08-11 10:00:00.000");
    }

    /** Fractional seconds are accepted at any precision; the column stores milliseconds. */
    @Test
    void acceptsFractionalSecondsAtAnyPrecision() {
        assertThat(normalize("2026-08-11T10:00:00.1Z")).isEqualTo("2026-08-11 10:00:00.100");
        assertThat(normalize("2026-08-11T10:00:00.123Z")).isEqualTo("2026-08-11 10:00:00.123");
        assertThat(normalize("2026-08-11T10:00:00.123456Z")).isEqualTo("2026-08-11 10:00:00.123");
        assertThat(normalize("2026-08-11T10:00:00.123456789Z")).isEqualTo("2026-08-11 10:00:00.123");
    }

    /** Truncation is toward the past, so a sub-millisecond remainder never rounds the instant forward. */
    @Test
    void truncatesSubMillisecondPrecisionTowardThePast() {
        assertThat(normalize("2026-08-11T10:00:00.999999Z")).isEqualTo("2026-08-11 10:00:00.999");
        assertThat(normalize("2026-08-11T10:00:00.000999Z")).isEqualTo("2026-08-11 10:00:00.000");
    }

    /**
     * The value the query path returns for a {@code timestamp} column has to be writable back unchanged —
     * the round-trip asymmetry this normalization exists to remove.
     */
    @Test
    void acceptsTheFormTheReadPathEmits() {
        assertThat(normalize("2026-08-11T10:00:00Z")).isEqualTo("2026-08-11 10:00:00.000");
        assertThat(normalize("2026-08-11T10:00:00.250Z")).isEqualTo("2026-08-11 10:00:00.250");
    }

    /** A real leap day is a real instant, so it is accepted like any other. */
    @Test
    void acceptsTheLeapDayOfLeapYears() {
        assertThat(normalize("2024-02-29T10:00:00Z")).isEqualTo("2024-02-29 10:00:00.000");
        assertThat(normalize("2000-02-29T10:00:00Z")).isEqualTo("2000-02-29 10:00:00.000");
    }

    /**
     * The clamping resolver (the {@code DateTimeFormatterBuilder} default) would silently move an
     * out-of-range day to the month's last day — storing an instant a day or more before the one the caller
     * sent, with no error. The resolver is pinned strict so such a value is rejected and named instead.
     */
    @Test
    void rejectsDaysThatDoNotExistOnTheCalendarRatherThanClampingThem() {
        assertThat(List.of(
                        "2026-02-29T10:00:00Z",
                        "2026-02-30T10:00:00Z",
                        "2026-04-31T10:00:00Z",
                        "2100-02-29T10:00:00Z",
                        "2026-06-31T10:00:00.500+03:00"))
                .allSatisfy(value -> assertThatThrownBy(() -> normalize(value))
                        .isInstanceOf(TableValidationException.class)
                        .hasMessageContaining("does not exist on the calendar")
                        .hasMessageContaining(value.substring(0, 10))
                        .hasMessageContaining(COLUMN));
    }

    /** A date-only value with an impossible day is still reported as a bad day, not as an unknown shape. */
    @Test
    void rejectsDateOnlyValueWhoseDayDoesNotExist() {
        assertThatThrownBy(() -> normalize("2026-02-30"))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("does not exist on the calendar");
    }

    /** A nullable timestamp column stores null; the value is passed through untouched. */
    @Test
    void passesNullThrough() {
        assertThat(normalizer.normalize(COLUMN, null, 1)).isNull();
    }

    @Test
    void rejectsZonelessValueNamingTheMissingZone() {
        assertThatThrownBy(() -> normalize("2026-08-11T10:00:00"))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("has no time zone")
                .hasMessageContaining("'Z'")
                .hasMessageContaining("+03:00")
                .hasMessageContaining(COLUMN)
                .hasMessageContaining("2026-08-11T10:00:00");
    }

    /** The classic mistake while migrating off the old accepted form, so it gets its own message. */
    @Test
    void rejectsSpaceSeparatedFormNamingTheSeparator() {
        assertThatThrownBy(() -> normalize("2026-08-11 10:00:00.000"))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("space separator")
                .hasMessageContaining("'T'")
                .hasMessageContaining("2026-08-11T10:00:00Z");
    }

    @Test
    void rejectsDateOnlyValueAsNotTimestamp() {
        assertThatThrownBy(() -> normalize("2026-08-11"))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("is a date, not a timestamp")
                .hasMessageContaining("2026-08-11T00:00:00Z");
    }

    /** A bare epoch number is ambiguous between seconds and milliseconds, so it is not guessed at. */
    @Test
    void rejectsNumericEpochAsAmbiguous() {
        assertThatThrownBy(() -> normalizer.normalize(COLUMN, 1786514400000L, 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("not a number")
                .hasMessageContaining("ambiguous")
                .hasMessageContaining(COLUMN);
        assertThatThrownBy(() -> normalizer.normalize(COLUMN, 1786514400, 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void rejectsOtherNonStringJsonTypes() {
        assertThatThrownBy(() -> normalizer.normalize(COLUMN, true, 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("not a boolean");
        assertThatThrownBy(() -> normalizer.normalize(COLUMN, List.of("2026-08-11T10:00:00Z"), 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("not an array");
        assertThatThrownBy(() -> normalizer.normalize(COLUMN, Map.of("at", "2026-08-11T10:00:00Z"), 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("not an object");
    }

    @Test
    void rejectsUnparseableValuesWithAnExample() {
        assertThat(List.of("", "   ", "yesterday", "11/08/2026", "2026-13-45T10:00:00Z", "not-a-date"))
                .allSatisfy(value -> assertThatThrownBy(() -> normalize(value))
                        .isInstanceOf(TableValidationException.class)
                        .hasMessageContaining("2026-08-11T10:00:00Z"));
    }

    /** A malformed offset is not a missing zone, so it falls through to the generic diagnosis. */
    @Test
    void rejectsMalformedOffset() {
        assertThatThrownBy(() -> normalize("2026-08-11T10:00:00+3"))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("is not an ISO-8601 timestamp");
        assertThatThrownBy(() -> normalize("2026-08-11T10:00:00[Europe/Kyiv]"))
                .isInstanceOf(TableValidationException.class);
    }

    /** Both ends of the storable range are held faithfully by every supported server, so both are accepted. */
    @Test
    void acceptsTheRangeBoundaries() {
        assertThat(normalize("1900-01-01T00:00:00Z")).isEqualTo("1900-01-01 00:00:00.000");
        assertThat(normalize("2299-12-31T23:59:59.999Z")).isEqualTo("2299-12-31 23:59:59.999");
        // Sub-millisecond digits past the upper bound truncate onto it rather than past it.
        assertThat(normalize("2299-12-31T23:59:59.999999Z")).isEqualTo("2299-12-31 23:59:59.999");
    }

    /** ClickHouse 26.3 clamps an instant outside the range with no error, so it is refused instead. */
    @Test
    void rejectsInstantsOutsideTheStorableRange() {
        assertThat(List.of("1899-12-31T23:59:59.999Z", "2300-01-01T00:00:00Z", "1066-10-14T09:00:00Z", "9999-12-31T23:59:59Z"))
                .allSatisfy(value -> assertThatThrownBy(() -> normalize(value))
                        .isInstanceOf(TableValidationException.class)
                        .hasMessageContaining("out of range for timestamp")
                        .hasMessageContaining("1900-01-01T00:00:00Z to 2299-12-31T23:59:59.999Z")
                        .hasMessageContaining("'" + value + "'")
                        .hasMessageContaining(COLUMN));
    }

    /**
     * The range is checked on the UTC instant, not on the wall clock as written: an offset can carry a value
     * whose local date is past a bound onto either side of it.
     */
    @Test
    void checksTheRangeOnTheUtcInstantNotTheWallClock() {
        assertThat(normalize("2300-01-01T01:00:00+02:00")).isEqualTo("2299-12-31 23:00:00.000");
        assertThat(normalize("1899-12-31T23:00:00-01:00")).isEqualTo("1900-01-01 00:00:00.000");
        assertThatThrownBy(() -> normalize("2299-12-31T23:30:00-01:00"))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("out of range for timestamp");
        assertThatThrownBy(() -> normalize("1900-01-01T00:30:00+01:00"))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("out of range for timestamp");
    }

    /** A batch may carry up to 10 000 rows, so the column name alone would not locate the offending value. */
    @Test
    void namesTheOneBasedRowIndex() {
        assertThatThrownBy(() -> normalizer.normalize(COLUMN, "nope", 1))
                .hasMessageContaining("row 1");
        assertThatThrownBy(() -> normalizer.normalize(COLUMN, "nope", 4269))
                .hasMessageContaining("row 4269");
    }

    /** A pathological value must not inflate the error body. */
    @Test
    void capsTheEchoedValue() {
        final String huge = "x".repeat(5000);
        assertThatThrownBy(() -> normalize(huge))
                .isInstanceOf(TableValidationException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).hasSizeLessThan(400));
    }

    /** The typed predicate the dry-run preview shares with {@link WriteTimestampNormalizer#normalize}. */
    @Test
    void typedPredicateHoldsExactlyTheTimestampRange() {
        assertThat(WriteTimestampNormalizer.holds(Instant.parse("1900-01-01T00:00:00Z"))).isTrue();
        assertThat(WriteTimestampNormalizer.holds(Instant.parse("2299-12-31T23:59:59.999Z"))).isTrue();
        assertThat(WriteTimestampNormalizer.holds(Instant.parse("1899-12-31T23:59:59.999Z"))).isFalse();
        assertThat(WriteTimestampNormalizer.holds(Instant.parse("2300-01-01T00:00:00Z"))).isFalse();
    }

    private String normalize(String value) {
        return (String) normalizer.normalize(COLUMN, value, 1);
    }
}
