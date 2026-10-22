package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;

/**
 * Validates a caller-supplied value for a {@code timestamp}-typed column and renders it into the form
 * ClickHouse stores, applied on the row-write path before the batch reaches
 * {@link com.epam.aidial.analytics.clickhouse.CatalogRowWriter}.
 *
 * <p><b>The one accepted format is ISO-8601 with an explicit zone</b> — either a {@code Z} designator
 * ({@code 2026-08-11T10:00:00Z}) or an explicit offset ({@code 2026-08-11T13:00:00+03:00}). The seconds
 * field is optional ({@code 2026-08-11T10:00Z}) and fractional seconds are optional at any precision,
 * truncated toward the past to the column's {@code DateTime64(3)} millisecond precision. This is exactly
 * what the query path returns for a {@code timestamp} column, so a value read from ADAS is writable back
 * to it unchanged — the round-trip this class exists to guarantee.
 *
 * <p>The offset is <b>required by the grammar</b> rather than checked afterwards: a zone-less value fails
 * to parse instead of silently resolving to UTC. Assuming a zone would store a local wall clock as the
 * wrong instant with no error, which is the failure mode that rules out ClickHouse's
 * {@code date_time_input_format=best_effort} in the first place.
 *
 * <p>For the same reason the parse resolves under {@link ResolverStyle#STRICT}: a date that does not exist
 * in the ISO calendar ({@code 2026-02-29} in a non-leap year, {@code 2026-04-31}) is rejected rather than
 * clamped to the month's last day. The builder's default is {@link ResolverStyle#SMART}, which would clamp
 * — storing an instant a day before the one the caller sent, with a {@code 200} and no diagnostic. Note
 * that {@link DateTimeFormatterBuilder#append(DateTimeFormatter)} copies only the appended formatter's
 * printer/parser, so {@link DateTimeFormatter#ISO_LOCAL_DATE}'s own strict resolver does <em>not</em> carry
 * over and has to be re-pinned on the assembled formatter.
 *
 * <p>The value handed on is rendered as {@code yyyy-MM-dd HH:mm:ss.SSS} at UTC. That keeps the API
 * contract independent of ClickHouse's {@code date_time_input_format}: ADAS decides what it accepts and
 * hands the store a form already proven to work, so the two do not have to move together.
 *
 * <p>A rejection is a {@link TableValidationException} (HTTP 422 {@code validation_error}) naming the
 * row's 1-based index in the batch, the column, the value, and <em>which</em> mistake was made — a
 * missing zone, a space separator, a date without a time, a day that is not on the calendar, an instant
 * outside the storable range ({@link #MIN} to {@link #MAX}), a non-string value, or an unparseable one.
 * Naming the specific defect is the point: it is what lets a caller fix the request from the response
 * alone. The value is echoed because it is the caller's own input from the request they just sent, so it
 * discloses nothing they do not already hold; it is length-capped so a pathological value cannot inflate
 * the error body.
 */
@Component
public class WriteTimestampNormalizer {

    /** The accepted wire format: ISO-8601, {@code T}-separated, optional seconds/fraction, required offset. */
    private static final DateTimeFormatter ACCEPTED = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral('T')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .appendOffsetId()
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT)
            .withChronology(IsoChronology.INSTANCE);

    /** The same shape without an offset — used only to tell "no zone" from "unparseable" in a rejection. */
    private static final DateTimeFormatter ZONELESS = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral('T')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT)
            .withChronology(IsoChronology.INSTANCE);

    /**
     * A date parsed with the clamping resolver — used only to tell "no such day on the calendar" from
     * "unparseable", so an out-of-range day is named rather than reported as a shape problem.
     */
    private static final DateTimeFormatter CLAMPING_DATE =
            DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.SMART);

    /**
     * The ClickHouse {@code DateTime64(3)} storage spelling. {@code uuuu} (proleptic year), not {@code yyyy}
     * (year-of-era): the latter is unsigned and prints no era, so a non-positive year would render as a
     * different year entirely — silently storing the wrong instant, which is what this class exists to
     * prevent. Matches {@code StructuredQueryToSqlRenderer}, which spells the same form the same way.
     */
    private static final DateTimeFormatter STORAGE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    /**
     * The storable range. ClickHouse 26.3 clamps a {@code DateTime64} outside it with no error (and stores a
     * 1899 instant as an unrelated 1900 one); 26.8 stores 0001 to 9999, but the narrower range is what every
     * supported server holds faithfully.
     */
    static final Instant MIN = Instant.parse("1900-01-01T00:00:00Z");
    static final Instant MAX = Instant.parse("2299-12-31T23:59:59.999Z");

    private static final String EXAMPLE = "2026-08-11T10:00:00Z";
    private static final String OFFSET_EXAMPLE = "2026-08-11T13:00:00+03:00";

    /** Index of the date/time separator in a well-formed value, where a space is the classic mistake. */
    private static final int SEPARATOR_INDEX = 10;

    /** Longest echoed value; beyond this the message is truncated so a huge value cannot bloat the body. */
    private static final int MAX_ECHOED_LENGTH = 64;

    /**
     * Returns the storage spelling of {@code value} for the {@code timestamp} column {@code column}, or
     * {@code value} itself when it is {@code null} (a nullable column stores null).
     *
     * @param column    the physical column name, named in a rejection
     * @param value     the caller-supplied value
     * @param rowNumber the row's 1-based index within the batch, named in a rejection
     * @throws TableValidationException if the value is not ISO-8601 with an explicit zone
     */
    public Object normalize(String column, Object value, int rowNumber) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw reject(column, rowNumber, "must be an ISO-8601 timestamp string, not "
                    + WriteValueText.describeJsonType(value) + " — a bare epoch number is ambiguous between seconds and "
                    + "milliseconds, so it is not accepted (e.g. '" + EXAMPLE + "')");
        }
        final Instant instant;
        try {
            instant = OffsetDateTime.parse(text, ACCEPTED).toInstant().truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException ex) {
            throw reject(column, rowNumber, "value '" + echo(text) + "' " + diagnose(text));
        }
        if (!holds(instant)) {
            throw reject(column, rowNumber, "value '" + echo(text) + "' is out of range for timestamp; the range is "
                    + MIN + " to " + MAX);
        }
        return STORAGE.format(instant);
    }

    /**
     * Whether a {@code timestamp} column holds {@code instant}: {@link #MIN}–{@link #MAX}, outside which
     * ClickHouse clamps on insert. Shared with the dry-run preview's target-range note so the two cannot drift.
     */
    public static boolean holds(Instant instant) {
        return !instant.isBefore(MIN) && !instant.isAfter(MAX);
    }

    /**
     * Explains why {@code text} was not accepted, in the caller's terms. Ordered most-specific first, and
     * deliberately covering each form ClickHouse's default parser used to take, so a caller migrating off
     * one is told exactly what to send instead rather than only that the value is invalid.
     */
    private static String diagnose(String text) {
        if (isDateOnly(text)) {
            return "is a date, not a timestamp; supply a time and an explicit zone (e.g. '"
                    + text + "T00:00:00Z')";
        }
        if (text.length() > SEPARATOR_INDEX && text.charAt(SEPARATOR_INDEX) == ' ') {
            return "uses a space separator; ISO-8601 requires 'T' between the date and the time, plus an "
                    + "explicit zone (e.g. '" + EXAMPLE + "')";
        }
        if (parsesWithoutZone(text)) {
            return "has no time zone; supply 'Z' for UTC or an explicit offset (e.g. '" + EXAMPLE
                    + "' or '" + OFFSET_EXAMPLE + "')";
        }
        if (hasImpossibleCalendarDate(text)) {
            return "names a day that does not exist on the calendar ('" + text.substring(0, SEPARATOR_INDEX)
                    + "'); check the month's length and whether the year is a leap year (e.g. '" + EXAMPLE + "')";
        }
        return "is not an ISO-8601 timestamp; expected 'YYYY-MM-DDTHH:MM:SS[.sss]' followed by 'Z' or an "
                + "explicit offset (e.g. '" + EXAMPLE + "' or '" + OFFSET_EXAMPLE + "')";
    }

    private static boolean isDateOnly(String text) {
        return parsesAsDate(text, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Whether the leading date is well-shaped but not a real day — {@code 2026-02-29} outside a leap year,
     * {@code 2026-04-31}. It is the clamping resolver that accepts these, so a value the strict grammar
     * rejected while the clamping one takes is exactly an out-of-range day-of-month.
     */
    private static boolean hasImpossibleCalendarDate(String text) {
        if (text.length() < SEPARATOR_INDEX) {
            return false;
        }
        final String date = text.substring(0, SEPARATOR_INDEX);
        return parsesAsDate(date, CLAMPING_DATE) && !parsesAsDate(date, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static boolean parsesAsDate(String text, DateTimeFormatter format) {
        try {
            LocalDate.parse(text, format);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static boolean parsesWithoutZone(String text) {
        try {
            LocalDateTime.parse(text, ZONELESS);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static String echo(String text) {
        return text.length() <= MAX_ECHOED_LENGTH ? text : text.substring(0, MAX_ECHOED_LENGTH) + "…";
    }

    private static TableValidationException reject(String column, int rowNumber, String detail) {
        return new TableValidationException(
                "row " + rowNumber + ", timestamp column '" + column + "': " + detail);
    }
}
