package com.epam.aidial.analytics.service.pipeline.preview;

import com.epam.aidial.analytics.service.table.WriteDateValidator;
import com.epam.aidial.analytics.service.table.WriteNumericRangeValidator;
import com.epam.aidial.analytics.service.table.WriteTimestampNormalizer;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The preview's two notes about what the target column will make of a sampled value: a binding whose sampled
 * type is wider than its target ({@code target_type_narrowing}), and a sampled value the target's physical type
 * cannot hold ({@code value_out_of_target_range}).
 *
 * <p>A pipeline writes through {@code INSERT … SELECT}, which the row-write path's range checks never see. What
 * ClickHouse then does with a value its target cannot hold depends on the target: it wraps an out-of-range
 * integer and clamps an out-of-range date or timestamp silently, but refuses a value past {@code Decimal(38,12)}
 * with {@code DECIMAL_OVERFLOW}, failing the refresh. The ranges are the row-write path's own, through the predicates it calls
 * ({@link WriteNumericRangeValidator}, {@link WriteDateValidator}, {@link WriteTimestampNormalizer}), so the
 * two paths cannot disagree about what a column holds.
 *
 * <p>Runs over the full values, before the value cap.
 */
public final class TargetCoercion {

    private TargetCoercion() {
    }

    /**
     * The coercion notes for every column that has a target type.
     *
     * @param temporal the columns for which a {@code timestamp} into a {@code date} target counts as narrowing:
     *                 a {@code min} or {@code max} measure, whose value is the input's own. A time bucket is
     *                 never in it — its unit decides which temporal type holds it, and the gate checks that.
     */
    public static List<PreviewNote> notes(List<PreviewColumn> columns, List<Map<String, Object>> rows, Set<String> temporal) {
        final List<PreviewNote> notes = new ArrayList<>();
        for (final PreviewColumn column : columns) {
            if (column.targetType() == null || column.type() == null) {
                continue;
            }
            if (narrows(column.type(), column.targetType(), temporal.contains(column.name()))) {
                notes.add(PreviewNote.about(PreviewNoteCode.TARGET_TYPE_NARROWING, column.name(), "column '"
                        + column.name() + "' yields " + column.type().code() + " but its target column is declared "
                        + column.targetType().code() + "; a value outside " + column.targetType().code()
                        + "'s range would be coerced silently on insert. Declare the target column "
                        + column.type().code() + " if such values can occur"));
            }
            long outside = rows.stream().map(row -> row.get(column.name()))
                    .filter(value -> value != null && !holds(column.targetType(), value))
                    .count();
            if (outside > 0) {
                notes.add(PreviewNote.about(PreviewNoteCode.VALUE_OUT_OF_TARGET_RANGE, column.name(), outside
                        + " sampled value(s) of column '" + column.name() + "' cannot be held by its target column's "
                        + column.targetType().code() + " type, so " + consequence(column.targetType())));
            }
        }
        return notes;
    }

    /** What the refresh's {@code INSERT … SELECT} does with a value {@code target} cannot hold. */
    private static String consequence(QueryFieldType target) {
        return switch (target) {
            case INTEGER, LONG -> "the insert would store a wrapped value without an error";
            case DATE, TIMESTAMP -> "the insert would store a clamped value without an error";
            case DECIMAL -> "the refresh would fail with a decimal overflow (DECIMAL_OVERFLOW)";
            default -> "the insert would store a different value";
        };
    }

    /** Whether {@code type} ranks above {@code target} on {@code integer < long < decimal}, or on {@code date < timestamp}. */
    static boolean narrows(QueryFieldType type, QueryFieldType target, boolean temporal) {
        final int from = numericRank(type);
        final int to = numericRank(target);
        if (from >= 0 && to >= 0) {
            return from > to;
        }
        return temporal && type == QueryFieldType.TIMESTAMP && target == QueryFieldType.DATE;
    }

    private static int numericRank(QueryFieldType type) {
        return switch (type) {
            case INTEGER -> 0;
            case LONG -> 1;
            case DECIMAL -> 2;
            default -> -1;
        };
    }

    /**
     * Whether a target column of {@code target} holds the sampled {@code value}. A value of a shape the target's
     * check does not apply to is taken to fit: the gate has already matched the types, and this note is about
     * range, not about a mismatch the gate would have refused. A non-finite floating-point value (NaN, ±Infinity)
     * is never held by a numeric target.
     */
    static boolean holds(QueryFieldType target, Object value) {
        if (numericRank(target) >= 0 && nonFinite(value)) {
            return false;
        }
        return switch (target) {
            case INTEGER, LONG -> {
                final BigInteger whole = wholeNumber(value);
                yield whole == null || WriteNumericRangeValidator.holds(target, whole);
            }
            case DECIMAL -> {
                final BigDecimal decimal = decimal(value);
                yield decimal == null || WriteNumericRangeValidator.holds(decimal);
            }
            case DATE -> {
                final LocalDate date = date(value);
                yield date == null || WriteDateValidator.holds(date);
            }
            case TIMESTAMP -> {
                final Instant instant = instant(value);
                yield instant == null || WriteTimestampNormalizer.holds(instant);
            }
            default -> true;
        };
    }

    private static boolean nonFinite(Object value) {
        return switch (value) {
            case Double d -> !Double.isFinite(d);
            case Float f -> !Float.isFinite(f);
            default -> false;
        };
    }

    /** The integer part of a sampled number, which is what an integer column keeps; null for a non-number. */
    private static BigInteger wholeNumber(Object value) {
        final BigDecimal decimal = decimal(value);
        return decimal == null ? null : decimal.toBigInteger();
    }

    private static BigDecimal decimal(Object value) {
        return switch (value) {
            case BigDecimal decimal -> decimal;
            case BigInteger big -> new BigDecimal(big);
            case Double d when Double.isFinite(d) -> BigDecimal.valueOf(d);
            case Float f when Float.isFinite(f) -> BigDecimal.valueOf(f.doubleValue());
            case Double d -> null; // non-finite: holds() refuses it before reaching here
            case Float f -> null;
            case Number number -> BigDecimal.valueOf(number.longValue());
            default -> null;
        };
    }

    private static LocalDate date(Object value) {
        return switch (value) {
            case LocalDate date -> date;
            case LocalDateTime dateTime -> dateTime.toLocalDate();
            case Object other -> {
                final Instant instant = instant(other);
                yield instant == null ? null : LocalDate.ofInstant(instant, ZoneOffset.UTC);
            }
        };
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case OffsetDateTime offset -> offset.toInstant();
            case ZonedDateTime zoned -> zoned.toInstant();
            case LocalDateTime local -> local.toInstant(ZoneOffset.UTC);
            case LocalDate date -> date.atStartOfDay(ZoneOffset.UTC).toInstant();
            default -> null;
        };
    }
}
