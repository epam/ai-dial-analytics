package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Refuses a caller-supplied value for a {@code date}-typed column (or an element of an {@code array} of
 * {@code date}) that ClickHouse's {@code Date} would store as a different day, applied on the row-write path
 * before the batch reaches {@link com.epam.aidial.analytics.clickhouse.CatalogRowWriter}.
 *
 * <p><b>ClickHouse accepts every wrong date silently.</b> Measured on a {@code JSONEachRow} insert: a day past
 * the type's range is clamped ({@code 2200-01-01} stores {@code 2149-06-06}, {@code 1969-12-31} stores
 * {@code 1970-01-01}), a day not on the calendar rolls over ({@code 2026-02-30} stores {@code 2026-03-02}), a
 * month of {@code 13} or {@code 00} stores {@code 1970-01-01}, and a bare JSON number is read as a day count
 * ({@code 20000} stores {@code 2024-10-04}). Each is a {@code 200} with a different day in the table.
 *
 * <p>The one accepted form is therefore a string {@code YYYY-MM-DD} naming a real calendar day within
 * {@code Date}'s range, {@code 1970-01-01} to {@code 2149-06-06} — what the query path returns for a
 * {@code date} column, so a value read from ADAS is writable back unchanged. An accepted value is handed on
 * exactly as supplied.
 */
@Component
public class WriteDateValidator {

    /** {@code uuuu}, not {@code yyyy}: strict resolution of a year-of-era pattern needs an era. */
    private static final DateTimeFormatter ACCEPTED =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    /** The same shape with the clamping resolver — used only to tell "no such day" from "unparseable". */
    private static final DateTimeFormatter CLAMPING = ACCEPTED.withResolverStyle(ResolverStyle.SMART);

    /** {@code Date} is a {@code UInt16} day count from the epoch. */
    static final LocalDate MIN = LocalDate.of(1970, 1, 1);
    static final LocalDate MAX = LocalDate.of(2149, 6, 6);

    private static final String EXAMPLE = "2026-08-11";

    /**
     * Checks {@code value} for the {@code date} column {@code column}.
     *
     * @param column    the physical column name (with the element index for an array), named in a rejection
     * @param value     the caller-supplied, non-null value
     * @param rowNumber the row's 1-based index within the batch, named in a rejection
     * @throws TableValidationException if the value is not a {@code YYYY-MM-DD} day within the type's range
     */
    public void check(String column, Object value, int rowNumber) {
        if (!(value instanceof String text)) {
            throw reject(column, rowNumber, "must be a 'YYYY-MM-DD' date string, not "
                    + WriteValueText.describeJsonType(value) + " (e.g. '" + EXAMPLE + "')");
        }
        final LocalDate date;
        try {
            date = LocalDate.parse(text, ACCEPTED);
        } catch (DateTimeParseException ex) {
            throw reject(column, rowNumber, "value " + WriteValueText.echo(text) + (parses(text)
                    ? " names a day that does not exist on the calendar; check the month's length and whether the year is a leap year"
                    : " is not a date; expected 'YYYY-MM-DD' (e.g. '" + EXAMPLE + "')"));
        }
        if (!holds(date)) {
            throw reject(column, rowNumber, "value " + WriteValueText.echo(text) + " is out of range for date; the range is "
                    + MIN + " to " + MAX);
        }
    }

    /**
     * Whether a {@code date} column holds {@code date}: ClickHouse's {@code Date} is a {@code UInt16} day count,
     * so a day outside {@link #MIN}–{@link #MAX} is clamped on insert. Shared with the dry-run preview's
     * target-range note so the two cannot drift.
     */
    public static boolean holds(LocalDate date) {
        return !date.isBefore(MIN) && !date.isAfter(MAX);
    }

    private static boolean parses(String text) {
        try {
            LocalDate.parse(text, CLAMPING);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static TableValidationException reject(String column, int rowNumber, String detail) {
        return new TableValidationException("row " + rowNumber + ", date column '" + column + "': " + detail);
    }
}
