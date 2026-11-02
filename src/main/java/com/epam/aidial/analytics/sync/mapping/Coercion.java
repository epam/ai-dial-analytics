package com.epam.aidial.analytics.sync.mapping;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Strict readers of a present JSON node as an extraction kind (see the {@code data-sync} spec, "A value that fails to
 * coerce to its column's kind is counted and falls back like an absent one").
 *
 * <p>Each reader returns the value, {@code null} for blank text (absent, so the column's fallback applies uncounted), or
 * {@link #FAILED} for a value the kind cannot represent. They replace Jackson's lenient {@code asLong(0)} and
 * {@code asBoolean(false)}, which invent a {@code 0} or {@code false} for anything unreadable — a fractional number, a
 * boolean, an object, an out-of-range number, unparseable text — that the fallback then cannot tell from a producer
 * value. The derivers that read a number return {@link #FAILED} the same way, so the engine counts every failure
 * against the column it fills.
 */
public final class Coercion {

    /** The result of reading a present value its kind cannot represent. Compared by identity. */
    public static final Object FAILED = new Object() {
        @Override
        public String toString() {
            return "Coercion.FAILED";
        }
    };

    /**
     * The longest trimmed text read as a number. Every value a {@code long} or a ClickHouse {@code Decimal} holds is far
     * shorter, and parsing an arbitrarily long digit string costs time that grows faster than its length — on a field
     * such as a request body's {@code rate}, which a caller controls.
     */
    static final int MAX_NUMBER_TEXT = 100;

    /**
     * The widest decimal exponent read, in either direction: ClickHouse's widest {@code Decimal} holds 76 digits, so a
     * value beyond this is one the column cannot store and would be refused at insert.
     */
    static final int MAX_DECIMAL_SCALE = 76;

    private Coercion() {
    }

    /**
     * Reads an integral number that fits a signed 64-bit integer, or text whose trimmed form parses as one. A
     * floating-point number or decimal text is accepted only when its value is integral: {@code 1e3} is {@code 1000},
     * {@code 12.7} is a failure rather than {@code 12}.
     *
     * @return a {@link Long}, {@code null} for blank text, or {@link #FAILED}
     */
    public static Object toLong(JsonNode node) {
        if (node.isNumber()) {
            if (node.isIntegralNumber()) {
                return node.canConvertToLong() ? node.longValue() : FAILED;
            }
            // A literal such as 1e400 parses to an infinite double, which has no decimal value: Jackson throws rather
            // than answer, and that must be this field's failure, not the whole record's.
            if (!Double.isFinite(node.doubleValue())) {
                return FAILED;
            }
            try {
                return integralLong(node.decimalValue());
            } catch (RuntimeException ex) {
                return FAILED;
            }
        }
        if (node.isString()) {
            String text = node.asString("").trim();
            if (text.isEmpty()) {
                return null;
            }
            BigDecimal value = parseDecimal(text);
            return value == null ? FAILED : integralLong(value);
        }
        return FAILED;
    }

    /**
     * Reads a number, or text whose trimmed form parses as a decimal, as an exact {@link BigDecimal} via its textual
     * form, so the producer's decimal precision is preserved with no binary floating-point round-trip.
     *
     * @return a {@link BigDecimal}, {@code null} for blank text, or {@link #FAILED}
     */
    public static Object toDecimal(JsonNode node) {
        if (!node.isNumber() && !node.isString()) {
            return FAILED;
        }
        String text = node.asString("").trim();
        if (text.isEmpty()) {
            return null;
        }
        BigDecimal value = parseDecimal(text);
        return value == null || Math.abs((long) value.scale()) > MAX_DECIMAL_SCALE ? FAILED : value;
    }

    /**
     * Reads {@code true} or {@code false}, the number {@code 0} or {@code 1}, or the text {@code true}, {@code false},
     * {@code 0} or {@code 1} in any case.
     *
     * @return a {@link Boolean}, {@code null} for blank text, or {@link #FAILED}
     */
    public static Object toBool(JsonNode node) {
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            Object value = toLong(node);
            if (Long.valueOf(0L).equals(value)) {
                return Boolean.FALSE;
            }
            return Long.valueOf(1L).equals(value) ? Boolean.TRUE : FAILED;
        }
        if (node.isString()) {
            String text = node.asString("").trim().toLowerCase(Locale.ROOT);
            return switch (text) {
                case "" -> null;
                case "true", "1" -> Boolean.TRUE;
                case "false", "0" -> Boolean.FALSE;
                default -> FAILED;
            };
        }
        return FAILED;
    }

    /**
     * Reads an array as a list of strings, each element as the {@code string} kind reads it: a scalar as its text, an
     * object or array as its JSON text (where a lenient read would store an empty string and lose the content).
     *
     * @return a list, or {@link #FAILED} for a value that is not an array
     */
    public static Object toStringArray(JsonNode node) {
        if (!node.isArray()) {
            return FAILED;
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            values.add(asText(element));
        }
        return values;
    }

    /** The {@code string} kind's reading: a container as its JSON text, a scalar as its text. */
    static String asText(JsonNode node) {
        return node.isContainer() ? node.toString() : node.asString("");
    }

    /** The decimal {@code text} spells, or {@code null} when it is too long or not a number. */
    private static BigDecimal parseDecimal(String text) {
        if (text.length() > MAX_NUMBER_TEXT) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Object integralLong(BigDecimal value) {
        if (Math.abs((long) value.scale()) > MAX_DECIMAL_SCALE) {
            return FAILED;
        }
        try {
            return value.stripTrailingZeros().longValueExact();
        } catch (ArithmeticException ex) {
            return FAILED;
        }
    }
}
