package com.epam.aidial.analytics.sync.mapping;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;

/**
 * A compiled predicate over a parsed log record, declared by a mapping's optional {@code match:} block
 * and evaluated at ingest to decide whether the mapping's target emits a row for that record.
 *
 * <p>A matcher is an ordered list of {@link Condition}s that are ANDed together: the record matches
 * only when every condition holds. A matcher with no conditions ({@link #matchAll()}) matches every
 * record, so a mapping without a {@code match:} block emits unconditionally (preserving the
 * single-target default behavior). Each condition resolves a dotted source path (via the shared
 * {@link JsonPaths}) to a scalar text and compares it to the condition's value with the given
 * {@link Op}; a path that does not resolve to a present scalar never matches.
 */
public record RecordMatcher(List<Condition> conditions) {

    public RecordMatcher {
        conditions = List.copyOf(conditions);
    }

    /** A matcher that accepts every record (used when a mapping declares no {@code match:} block). */
    public static RecordMatcher matchAll() {
        return new RecordMatcher(List.of());
    }

    /** Whether every declared condition holds for {@code record} (vacuously true when there are none). */
    public boolean matches(JsonNode record) {
        for (Condition condition : conditions) {
            if (!condition.matches(record)) {
                return false;
            }
        }
        return true;
    }

    /** The comparison operators a {@code match:} condition may use. */
    public enum Op {
        ENDS_WITH("ends-with"),
        EQUALS("equals"),
        CONTAINS("contains");

        private final String token;

        Op(String token) {
            this.token = token;
        }

        /** The lower-case YAML token that selects this operator. */
        public String token() {
            return token;
        }

        /** Resolves a YAML token to an operator, or {@code null} when unknown (loader fails fast on null). */
        public static Op fromToken(String raw) {
            if (raw == null) {
                return null;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            for (Op op : values()) {
                if (op.token.equals(normalized)) {
                    return op;
                }
            }
            return null;
        }
    }

    /**
     * A single condition: the scalar text at {@code path} within the record, compared to {@code value}
     * via {@code op}. The path is pre-compiled once at load so evaluation does no per-record splitting.
     * When {@code ignoreCase} is set, both sides are folded to lower case before comparison — useful for
     * case-insensitive tokens such as the HTTP method (e.g. {@code POST} vs {@code post}).
     *
     * @param path       the pre-compiled dotted source path to read
     * @param op         the comparison operator
     * @param value      the literal value to compare against
     * @param ignoreCase whether the comparison folds case on both sides
     */
    public record Condition(JsonPaths.CompiledPath path, Op op, String value, boolean ignoreCase) {

        /** Convenience for a case-sensitive condition (the default when no {@code ignore-case} is declared). */
        public Condition(JsonPaths.CompiledPath path, Op op, String value) {
            this(path, op, value, false);
        }

        boolean matches(JsonNode record) {
            JsonNode node = JsonPaths.walk(record, path);
            if (node == null || node.isMissingNode() || node.isNull()) {
                return false;
            }
            String text = node.asString("");
            String needle = value;
            if (ignoreCase) {
                text = text.toLowerCase(Locale.ROOT);
                needle = needle.toLowerCase(Locale.ROOT);
            }
            return switch (op) {
                case ENDS_WITH -> text.endsWith(needle);
                case EQUALS -> text.equals(needle);
                case CONTAINS -> text.contains(needle);
            };
        }
    }
}
