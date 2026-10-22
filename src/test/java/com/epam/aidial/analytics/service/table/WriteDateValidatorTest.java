package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for the row-write {@code date} check: the one accepted form is a {@code YYYY-MM-DD} string naming
 * a real calendar day within {@code Date}'s range, and every rejection names the specific mistake, since
 * ClickHouse would otherwise store a different day with no error.
 */
class WriteDateValidatorTest {

    private static final String COLUMN = "event_date";

    private final WriteDateValidator validator = new WriteDateValidator();

    @Test
    void acceptsCalendarDaysInsideTheRange() {
        assertThat(List.of("2026-08-11", "2024-02-29", "2000-02-29", "1999-12-31"))
                .allSatisfy(value -> assertThatCode(() -> check(value)).doesNotThrowAnyException());
    }

    /** Both ends of {@code Date}'s {@code UInt16} day count are storable exactly. */
    @Test
    void acceptsTheRangeBoundaries() {
        assertThatCode(() -> check("1970-01-01")).doesNotThrowAnyException();
        assertThatCode(() -> check("2149-06-06")).doesNotThrowAnyException();
    }

    /** ClickHouse clamps a day past either end to the boundary, so one day beyond each is refused. */
    @Test
    void rejectsDaysOutsideTheRange() {
        assertThat(List.of("1969-12-31", "2149-06-07", "2200-01-01", "1900-01-01"))
                .allSatisfy(value -> assertThatThrownBy(() -> check(value))
                        .isInstanceOf(TableValidationException.class)
                        .hasMessageContaining("out of range for date")
                        .hasMessageContaining("1970-01-01 to 2149-06-06")
                        .hasMessageContaining("'" + value + "'")
                        .hasMessageContaining(COLUMN));
    }

    /** ClickHouse rolls a day off the calendar over into the next month; the strict resolver refuses it. */
    @Test
    void rejectsDaysThatDoNotExistOnTheCalendar() {
        assertThat(List.of("2026-02-30", "2026-02-29", "2100-02-29", "2026-04-31"))
                .allSatisfy(value -> assertThatThrownBy(() -> check(value))
                        .isInstanceOf(TableValidationException.class)
                        .hasMessageContaining("does not exist on the calendar")
                        .hasMessageContaining(value));
    }

    @Test
    void rejectsUnparseableValuesWithAnExample() {
        assertThat(List.of("", "yesterday", "2026-13-01", "2026-00-10", "2026-8-11", "11/08/2026", "2026-08-11T00:00:00Z",
                        "2026-08-11 00:00:00", " 2026-08-11"))
                .allSatisfy(value -> assertThatThrownBy(() -> check(value))
                        .isInstanceOf(TableValidationException.class)
                        .hasMessageContaining("is not a date")
                        .hasMessageContaining("'YYYY-MM-DD'")
                        .hasMessageContaining("2026-08-11"));
    }

    /** A bare number would be read by ClickHouse as a day count ({@code 20000} stores {@code 2024-10-04}). */
    @Test
    void rejectsNonStringJsonTypes() {
        assertThatThrownBy(() -> validator.check(COLUMN, 20000, 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("must be a 'YYYY-MM-DD' date string, not a number")
                .hasMessageContaining(COLUMN);
        assertThatThrownBy(() -> validator.check(COLUMN, true, 1))
                .hasMessageContaining("not a boolean");
        assertThatThrownBy(() -> validator.check(COLUMN, List.of("2026-08-11"), 1))
                .hasMessageContaining("not an array");
        assertThatThrownBy(() -> validator.check(COLUMN, Map.of("d", "2026-08-11"), 1))
                .hasMessageContaining("not an object");
    }

    @Test
    void namesTheRowAndTheColumnType() {
        assertThatThrownBy(() -> validator.check(COLUMN, "nope", 4269))
                .hasMessageStartingWith("row 4269, date column '" + COLUMN + "': ");
    }

    @Test
    void capsTheEchoedValue() {
        assertThatThrownBy(() -> check("x".repeat(5000)))
                .isInstanceOf(TableValidationException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).hasSizeLessThan(300));
    }

    /** The typed predicate the dry-run preview shares with {@link WriteDateValidator#check}. */
    @Test
    void typedPredicateHoldsExactlyTheDateRange() {
        assertThat(WriteDateValidator.holds(LocalDate.of(1970, 1, 1))).isTrue();
        assertThat(WriteDateValidator.holds(LocalDate.of(2149, 6, 6))).isTrue();
        assertThat(WriteDateValidator.holds(LocalDate.of(1969, 12, 31))).isFalse();
        assertThat(WriteDateValidator.holds(LocalDate.of(2149, 6, 7))).isFalse();
    }

    private void check(String value) {
        validator.check(COLUMN, value, 1);
    }
}
