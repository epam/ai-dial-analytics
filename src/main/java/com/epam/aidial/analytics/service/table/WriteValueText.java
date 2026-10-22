package com.epam.aidial.analytics.service.table;

import java.util.Map;

/**
 * Wording shared by the row-write value checks, so every rejection echoes and names a value the same way.
 *
 * <p>The value is echoed because it is the caller's own input from the request they just sent, so it
 * discloses nothing they do not already hold; it is length-capped so a pathological value cannot inflate
 * the error body.
 */
final class WriteValueText {

    /** Longest echoed value; beyond this the message is truncated so a huge value cannot bloat the body. */
    private static final int MAX_ECHOED_LENGTH = 64;

    private WriteValueText() {
    }

    /** {@code value} as the caller wrote it — a string quoted, anything else as its JSON text — length-capped. */
    static String echo(Object value) {
        final String text = value instanceof String ? "'" + value + "'" : String.valueOf(value);
        return text.length() <= MAX_ECHOED_LENGTH ? text : text.substring(0, MAX_ECHOED_LENGTH) + "…";
    }

    /** Names the JSON type of {@code value} in the caller's vocabulary, not Java's. */
    static String describeJsonType(Object value) {
        if (value instanceof String) {
            return "a string";
        }
        if (value instanceof Number) {
            return "a number";
        }
        if (value instanceof Boolean) {
            return "a boolean";
        }
        if (value instanceof Iterable<?> || value.getClass().isArray()) {
            return "an array";
        }
        if (value instanceof Map<?, ?>) {
            return "an object";
        }
        return "a " + value.getClass().getSimpleName();
    }
}
