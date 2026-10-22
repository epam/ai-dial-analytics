package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Unit coverage for the row-write numeric range check: every value ClickHouse would silently wrap (or refuse
 * opaquely) is refused here, and every value it stores faithfully is left alone.
 */
class WriteNumericRangeValidatorTest {

    private static final String COLUMN = "amount";

    private final WriteNumericRangeValidator validator = new WriteNumericRangeValidator();

    @Test
    void acceptsIntegerBoundaries() {
        assertAccepted(QueryFieldType.INTEGER, Integer.MAX_VALUE);
        assertAccepted(QueryFieldType.INTEGER, Integer.MIN_VALUE);
        assertAccepted(QueryFieldType.INTEGER, 0);
    }

    /** The reported case: ClickHouse stores 3000000000 in an Int32 as -1294967296. */
    @Test
    void rejectsValueAboveIntegerRange() {
        assertThatThrownBy(() -> check(QueryFieldType.INTEGER, 3_000_000_000L))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("row 1")
                .hasMessageContaining("'" + COLUMN + "'")
                .hasMessageContaining("3000000000")
                .hasMessageContaining("out of range for integer (Int32)")
                .hasMessageContaining("declare the column long");
    }

    @Test
    void rejectsValueBelowIntegerRange() {
        assertThatThrownBy(() -> check(QueryFieldType.INTEGER, (long) Integer.MIN_VALUE - 1))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void longAcceptsWhatIntegerRefusesUpToItsBoundaries() {
        assertAccepted(QueryFieldType.LONG, 3_000_000_000L);
        assertAccepted(QueryFieldType.LONG, Long.MAX_VALUE);
        assertAccepted(QueryFieldType.LONG, Long.MIN_VALUE);
    }

    /** Jackson parses a JSON integer past the long range as a BigInteger. */
    @Test
    void rejectsValueBeyondLongRange() {
        assertThatThrownBy(() -> check(QueryFieldType.LONG, new BigInteger("99999999999999999999")))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("out of range for long (Int64)");
    }

    /** ClickHouse accepts a quoted integer for an integer column, and wraps it the same way. */
    @Test
    void rangeChecksIntegerSpelledAsString() {
        assertAccepted(QueryFieldType.INTEGER, "42");
        assertThatThrownBy(() -> check(QueryFieldType.INTEGER, "3000000000"))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("'3000000000'");
    }

    /** ClickHouse stores true as 1 and "" as 0 in an integer column, and refuses "abc" opaquely. */
    @Test
    void rejectsNonNumbersForIntegerColumns() {
        assertThatThrownBy(() -> check(QueryFieldType.INTEGER, true))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("must be a number, not a boolean");
        assertThatThrownBy(() -> check(QueryFieldType.LONG, ""))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("value '' is not a number");
        assertThatThrownBy(() -> check(QueryFieldType.INTEGER, "abc"))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("is not a number");
        assertThatThrownBy(() -> check(QueryFieldType.INTEGER, " 12"))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> check(QueryFieldType.INTEGER, List.of(1)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("not an array");
    }

    @Test
    void acceptsSignedIntegerText() {
        assertAccepted(QueryFieldType.INTEGER, "+42");
        assertAccepted(QueryFieldType.INTEGER, "-2147483648");
    }

    /** A negative-scale BigDecimal is whole but serializes as 1E+2, which ClickHouse cannot parse for an integer. */
    @Test
    void rejectsExponentFormBigDecimalForIntegerColumns() {
        assertAccepted(QueryFieldType.LONG, new BigDecimal("100"));
        assertThatThrownBy(() -> check(QueryFieldType.LONG, new BigDecimal("1E+2")))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("not a whole number");
    }

    /** BigInteger and BigDecimal parse in quadratic time, so an over-long spelling is refused before parsing. */
    @Test
    void rejectsOverLongNumericTextWithoutParsingIt() {
        final String digits = "1".repeat(WriteNumericRangeValidator.MAX_NUMERIC_TEXT_LENGTH + 1);
        assertThatThrownBy(() -> check(QueryFieldType.INTEGER, digits))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("longer than " + WriteNumericRangeValidator.MAX_NUMERIC_TEXT_LENGTH + " characters");
        assertThatThrownBy(() -> check(QueryFieldType.DECIMAL, "0." + digits))
                .isInstanceOf(TableValidationException.class);
    }

    /** The decimal pattern used to backtrack quadratically on a long run of digits that does not match. */
    @Test
    void rejectsLongNonMatchingDigitRunQuickly() {
        final String text = "1".repeat(WriteNumericRangeValidator.MAX_NUMERIC_TEXT_LENGTH - 1) + "x";
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            for (int i = 0; i < 1000; i++) {
                assertThatThrownBy(() -> check(QueryFieldType.DECIMAL, text)).isInstanceOf(TableValidationException.class);
            }
        });
    }

    @Test
    void rejectsFractionalAndExponentNumbers() {
        assertThatThrownBy(() -> check(QueryFieldType.INTEGER, 1.5d))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("not a whole number");
        assertThatThrownBy(() -> check(QueryFieldType.LONG, 3.0e9d))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("not a whole number");
        assertThatThrownBy(() -> check(QueryFieldType.INTEGER, new BigDecimal("1.0")))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("not a whole number");
    }

    /** Decimal(38, 12) holds 26 integer digits, at either sign. */
    @Test
    void acceptsDecimalUpToItsIntegerDigits() {
        assertAccepted(QueryFieldType.DECIMAL, "99999999999999999999999999.999999999999");
        assertAccepted(QueryFieldType.DECIMAL, "-99999999999999999999999999.5");
        assertAccepted(QueryFieldType.DECIMAL, "94.25");
        assertAccepted(QueryFieldType.DECIMAL, 1.5d);
        assertAccepted(QueryFieldType.DECIMAL, 42);
        assertAccepted(QueryFieldType.DECIMAL, new BigDecimal("1E-20"));
    }

    @Test
    void rejectsDecimalPastItsIntegerDigits() {
        assertThatThrownBy(() -> check(QueryFieldType.DECIMAL, "100000000000000000000000000"))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("row 1")
                .hasMessageContaining("decimal column '" + COLUMN + "'")
                .hasMessageContaining("out of range for decimal (Decimal(38, 12))")
                .hasMessageContaining("at most 26 digits");
        assertThatThrownBy(() -> check(QueryFieldType.DECIMAL, "-100000000000000000000000000.1"))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> check(QueryFieldType.DECIMAL, new BigInteger("100000000000000000000000000")))
                .isInstanceOf(TableValidationException.class);
    }

    /** ClickHouse reads an exponent in a number or a string alike, so both are range-checked. */
    @Test
    void rangeChecksDecimalExponentForms() {
        assertThatThrownBy(() -> check(QueryFieldType.DECIMAL, 1e30d))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> check(QueryFieldType.DECIMAL, "1e30"))
                .isInstanceOf(TableValidationException.class);
        assertAccepted(QueryFieldType.DECIMAL, "1e25");
    }

    /** ClickHouse stores "" as 0 in a decimal column and refuses the rest opaquely. */
    @Test
    void rejectsNonNumbersForDecimalColumns() {
        assertThatThrownBy(() -> check(QueryFieldType.DECIMAL, ""))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("is not a number");
        assertThatThrownBy(() -> check(QueryFieldType.DECIMAL, "abc"))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> check(QueryFieldType.DECIMAL, " 12"))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> check(QueryFieldType.DECIMAL, false))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("not a boolean");
        assertThatThrownBy(() -> check(QueryFieldType.DECIMAL, "1e99999999999"))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("exponent too large");
        assertAccepted(QueryFieldType.DECIMAL, "1e-30");
        assertAccepted(QueryFieldType.DECIMAL, ".5");
    }

    private void check(QueryFieldType type, Object value) {
        validator.check(COLUMN, type, value, 1);
    }

    /** The typed predicates the dry-run preview shares with {@link WriteNumericRangeValidator#check}. */
    @Test
    void typedPredicatesHoldExactlyTheColumnsRange() {
        assertThat(WriteNumericRangeValidator.holds(QueryFieldType.INTEGER, BigInteger.valueOf(Integer.MAX_VALUE))).isTrue();
        assertThat(WriteNumericRangeValidator.holds(QueryFieldType.INTEGER, BigInteger.valueOf(Integer.MIN_VALUE - 1L))).isFalse();
        assertThat(WriteNumericRangeValidator.holds(QueryFieldType.LONG, BigInteger.valueOf(Long.MIN_VALUE))).isTrue();
        assertThat(WriteNumericRangeValidator.holds(QueryFieldType.INTEGER, BigInteger.valueOf(3_000_000_000L))).isFalse();
        assertThat(WriteNumericRangeValidator.holds(QueryFieldType.LONG, BigInteger.valueOf(Long.MAX_VALUE))).isTrue();
        assertThat(WriteNumericRangeValidator.holds(QueryFieldType.LONG, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))).isFalse();
        assertThat(WriteNumericRangeValidator.holds(new BigDecimal("99999999999999999999999999.5"))).isTrue();
        assertThat(WriteNumericRangeValidator.holds(new BigDecimal("-1e26"))).isFalse();
    }

    @Test
    void integerPredicateRefusesNonIntegerType() {
        assertThatThrownBy(() -> WriteNumericRangeValidator.holds(QueryFieldType.DECIMAL, BigInteger.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteNumericRangeValidator.holds(QueryFieldType.STRING, BigInteger.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertAccepted(QueryFieldType type, Object value) {
        assertThatCode(() -> check(type, value)).doesNotThrowAnyException();
    }
}
