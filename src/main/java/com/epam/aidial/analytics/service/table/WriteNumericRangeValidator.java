package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Refuses a caller-supplied value for an {@code integer}-, {@code long}- or {@code decimal}-typed column (or
 * an element of an {@code array} of one) that the column's ClickHouse type cannot hold, applied on the
 * row-write path before the batch reaches {@link com.epam.aidial.analytics.clickhouse.CatalogRowWriter}.
 *
 * <p><b>ClickHouse does not range-check integers on a {@code JSONEachRow} insert — it wraps them.</b>
 * {@code 3000000000} written to an {@code Int32} column stores {@code -1294967296} (the low 32 bits) with no
 * error, and the same holds for a quoted {@code "3000000000"}, for an element of an {@code Array(Int32)}, and
 * for a value past the {@code Int64} range. The write succeeds and the stored value is simply a different
 * number, so the check has to live here — the same reasoning as the write path's non-nullable check.
 *
 * <p><b>Nor does it refuse a non-number it can coerce.</b> {@code true} / {@code false} / {@code "true"} store
 * {@code 1} / {@code 0} in an integer column, and an empty string stores {@code 0} in an integer or decimal
 * column (a {@code Nullable} one included). So only a number, or a string spelling a plain one, is accepted;
 * anything else is refused here rather than left to ClickHouse, which coerces some of it and refuses the rest
 * with an error naming neither row nor column.
 *
 * <p><b>A {@code decimal} past its range is refused by ClickHouse, but opaquely.</b> {@code Decimal(38, 12)}
 * holds 26 integer digits; a 27-digit value (or {@code 1e30}) fails the whole insert with ClickHouse's
 * {@code Decimal value is too big}. Checking it here turns that into the same diagnosable rejection the integer
 * types get. Fractional digits beyond the scale are not checked: ClickHouse truncates them, which is a
 * precision question rather than a range one.
 *
 * <p>Validation only: an accepted value is handed on exactly as supplied. What is accepted:
 * <ul>
 *   <li>for {@code integer}/{@code long}, a JSON integer within range, or a string spelling one
 *       ({@code "42"}, {@code "+42"}); a number with a fraction or exponent ({@code 1.5}, {@code 3.0e9}) is
 *       refused as not a whole number, which ClickHouse already fails with an opaque parse error;</li>
 *   <li>for {@code decimal}, a JSON number, or a string spelling a plain number with an optional exponent
 *       ({@code "94.25"}, {@code "1e3"}), below {@code 10^26} in magnitude.</li>
 * </ul>
 * A numeric string longer than {@value #MAX_NUMERIC_TEXT_LENGTH} characters — the bound Jackson already puts on
 * a JSON number — is refused before it is parsed: {@link BigInteger} and {@link BigDecimal} parse in time
 * quadratic in the length, and a write batch may carry thousands of values.
 *
 * <p>A rejection is a {@link TableValidationException} (HTTP 422 {@code validation_error}) naming the row's
 * 1-based index, the column, the value (length-capped), and the column's range.
 */
@Component
public class WriteNumericRangeValidator {

    private static final BigInteger INT32_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger INT32_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger INT64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger INT64_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /** Integer digits a {@code DECIMAL} column holds: precision minus scale. */
    private static final int DECIMAL_INTEGER_DIGITS =
            ClickHouseTypeMapper.DECIMAL_PRECISION - ClickHouseTypeMapper.DECIMAL_SCALE;

    /** The smallest magnitude a {@code DECIMAL} column cannot hold: {@code 10^26}. */
    private static final BigDecimal DECIMAL_LIMIT = BigDecimal.TEN.pow(DECIMAL_INTEGER_DIGITS);

    /** Longest numeric string parsed; Jackson's {@code StreamReadConstraints} default for a JSON number. */
    static final int MAX_NUMERIC_TEXT_LENGTH = 1000;

    /**
     * A plain integer spelling — the only string form accepted for an integer column. Possessive quantifiers
     * keep the match linear: a greedy {@code \d+\.?\d*} backtracks quadratically on a long run of digits.
     */
    private static final Pattern INTEGER_TEXT = Pattern.compile("[+-]?+\\d++");

    /** A plain decimal spelling, exponent allowed — the only string form accepted for a decimal column. */
    private static final Pattern DECIMAL_TEXT = Pattern.compile("[+-]?+(?:\\d++(?:\\.\\d*+)?+|\\.\\d++)(?:[eE][+-]?+\\d++)?+");

    /**
     * Checks {@code value} for {@code column}.
     *
     * @param column    the physical column name (with the element index for an array), named in a rejection
     * @param type      the numeric type of the column, or of its elements:
     *                  {@link QueryFieldType#INTEGER}, {@link QueryFieldType#LONG} or {@link QueryFieldType#DECIMAL}
     * @param value     the caller-supplied, non-null value
     * @param rowNumber the row's 1-based index within the batch, named in a rejection
     * @throws TableValidationException if the value is not a number the column's type holds
     */
    public void check(String column, QueryFieldType type, Object value, int rowNumber) {
        if (value instanceof String text && text.length() > MAX_NUMERIC_TEXT_LENGTH) {
            throw reject(column, type, rowNumber, "value " + WriteValueText.echo(text) + " is longer than "
                    + MAX_NUMERIC_TEXT_LENGTH + " characters");
        }
        if (type == QueryFieldType.DECIMAL) {
            checkDecimal(column, value, rowNumber);
        } else {
            checkInteger(column, type, value, rowNumber);
        }
    }

    /**
     * Whether an {@code integer} ({@code Int32}) or {@code long} ({@code Int64}) column holds {@code value}. The
     * predicate behind {@link #check}, shared with the dry-run preview's target-range note so the two cannot
     * drift: a pipeline's {@code INSERT … SELECT} wraps an out-of-range value exactly as a row write would.
     *
     * @throws IllegalArgumentException for any other type — a {@code decimal} has its own {@link #holds(BigDecimal)}
     */
    public static boolean holds(QueryFieldType type, BigInteger value) {
        if (type != QueryFieldType.INTEGER && type != QueryFieldType.LONG) {
            throw new IllegalArgumentException("not an integer column type: " + type);
        }
        final boolean int32 = type == QueryFieldType.INTEGER;
        return value.compareTo(int32 ? INT32_MIN : INT64_MIN) >= 0 && value.compareTo(int32 ? INT32_MAX : INT64_MAX) <= 0;
    }

    /**
     * Whether a {@code decimal} ({@code Decimal(38, 12)}) column holds {@code value}: fewer than 26 integer digits.
     * Fractional digits past the scale are truncated, which is precision rather than range.
     */
    public static boolean holds(BigDecimal value) {
        return value.abs().compareTo(DECIMAL_LIMIT) < 0;
    }

    private static void checkInteger(String column, QueryFieldType type, Object value, int rowNumber) {
        final BigInteger number = wholeNumber(column, type, value, rowNumber);
        final boolean int32 = type == QueryFieldType.INTEGER;
        final BigInteger min = int32 ? INT32_MIN : INT64_MIN;
        final BigInteger max = int32 ? INT32_MAX : INT64_MAX;
        if (!holds(type, number)) {
            throw reject(column, type, rowNumber, "value " + WriteValueText.echo(value) + " is out of range for "
                    + (int32 ? "integer (Int32)" : "long (Int64)") + "; the range is " + min + " to " + max
                    + (int32 ? " — declare the column long for wider values" : ""));
        }
    }

    private static void checkDecimal(String column, Object value, int rowNumber) {
        final BigDecimal number = decimal(column, value, rowNumber);
        if (!holds(number)) {
            throw reject(column, QueryFieldType.DECIMAL, rowNumber, "value " + WriteValueText.echo(value)
                    + " is out of range for decimal (Decimal(" + ClickHouseTypeMapper.DECIMAL_PRECISION + ", "
                    + ClickHouseTypeMapper.DECIMAL_SCALE + ")); at most " + DECIMAL_INTEGER_DIGITS
                    + " digits before the decimal point");
        }
    }

    /** The value as an exact integer. */
    private static BigInteger wholeNumber(String column, QueryFieldType type, Object value, int rowNumber) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigInteger big) {
            return big;
        }
        // Scale 0 only: a negative scale (1E+2) is whole, but serializes in the exponent form ClickHouse cannot
        // parse for an integer column.
        if (value instanceof BigDecimal decimal && decimal.scale() == 0) {
            return decimal.toBigInteger();
        }
        if (value instanceof Number) {
            throw reject(column, type, rowNumber, "value " + WriteValueText.echo(value) + " is not a whole number");
        }
        if (value instanceof String text && INTEGER_TEXT.matcher(text).matches()) {
            return new BigInteger(text);
        }
        throw reject(column, type, rowNumber, nonNumeric(value, "a JSON integer or a string spelling one (e.g. 42 or '42')"));
    }

    /**
     * The value as an exact decimal. A double is read through {@link Double#toString}, which is the spelling the
     * JSON serializer hands ClickHouse.
     */
    private static BigDecimal decimal(String column, Object value, int rowNumber) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger big) {
            return new BigDecimal(big);
        }
        if (value instanceof Double || value instanceof Float) {
            final double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d)) {
                throw reject(column, QueryFieldType.DECIMAL, rowNumber, "value " + d + " is not a finite number");
            }
            return new BigDecimal(Double.toString(d));
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (value instanceof String text && DECIMAL_TEXT.matcher(text).matches()) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ex) {
                // Only an exponent past BigDecimal's int scale range gets here; such a value has no Decimal form.
                throw reject(column, QueryFieldType.DECIMAL, rowNumber, "value " + WriteValueText.echo(text)
                        + " has an exponent too large to be a decimal");
            }
        }
        throw reject(column, QueryFieldType.DECIMAL, rowNumber,
                nonNumeric(value, "a JSON number or a string spelling one (e.g. 94.25 or '94.25')"));
    }

    private static String nonNumeric(Object value, String expected) {
        return (value instanceof String ? "value " + WriteValueText.echo(value) + " is not a number"
                : "must be a number, not " + WriteValueText.describeJsonType(value)) + "; expected " + expected;
    }

    private static TableValidationException reject(String column, QueryFieldType type, int rowNumber, String detail) {
        return new TableValidationException("row " + rowNumber + ", " + type.code() + " column '" + column + "': " + detail);
    }
}
