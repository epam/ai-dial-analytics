package com.epam.aidial.analytics.web.mapper.page;

import com.epam.aidial.analytics.exception.InvalidFilterValueException;

import java.util.UUID;

/**
 * One entry of a {@link PageEntityMapper}'s allow-list: a wire column name bound to the entity attribute
 * it reads and the type its values must be coerced to.
 *
 * <p>An allow-list rather than "whatever the entity maps" is deliberate. Without one, an unknown filter
 * column leaks a raw Hibernate message, an unknown sort column is a 500, and every mapped attribute
 * becomes filterable — which on a table holding large text would make {@code co} an exfiltration
 * primitive. It also makes the coupling between the wire vocabulary and JPA attribute names explicit in
 * one reviewable place, since renaming an audited field is a breaking API change.
 *
 * @param attribute the JPA attribute path on the root entity
 * @param type      how a filter value is interpreted before the query is built
 * @param enumType  the enum class when {@link Type#ENUM}, otherwise null
 */
public record FilterableColumn(String attribute, FilterableColumn.Type type, Class<? extends Enum<?>> enumType) {

    /** How a filter value is read. */
    public enum Type {
        STRING,
        /** A 64-bit whole number. */
        NUMBER,
        /** A 32-bit whole number — separate from {@link #NUMBER} because the width is load-bearing. */
        INTEGER,
        ENUM,
        UUID
    }

    public static FilterableColumn string(String attribute) {
        return new FilterableColumn(attribute, Type.STRING, null);
    }

    public static FilterableColumn number(String attribute) {
        return new FilterableColumn(attribute, Type.NUMBER, null);
    }

    /**
     * A column whose attribute is an {@code int}. Distinct from {@link #number} because coercing to
     * {@code Long} regardless would let a value outside the 32-bit range through this layer entirely: it
     * parses fine, and then fails deep inside Hibernate when the bound parameter is narrowed to the
     * path's type — surfacing as a 5xx that blames the database for a value the caller typed, which is
     * precisely what coercing here is supposed to prevent.
     */
    public static FilterableColumn integer(String attribute) {
        return new FilterableColumn(attribute, Type.INTEGER, null);
    }

    public static FilterableColumn uuid(String attribute) {
        return new FilterableColumn(attribute, Type.UUID, null);
    }

    public static FilterableColumn enumerated(String attribute, Class<? extends Enum<?>> enumType) {
        return new FilterableColumn(attribute, Type.ENUM, enumType);
    }

    /** Whether {@code co}/{@code nc} apply — a LIKE over a non-text column is not a meaningful question. */
    public boolean isTextual() {
        return type == Type.STRING;
    }

    /**
     * Whether {@code lt}/{@code gt}/{@code le}/{@code ge} apply — that is, whether this column has an
     * order a caller could mean.
     *
     * <p>Numbers and text do; an enum and a UUID do not. Comparing them would silently answer a
     * different question than the one asked: {@code activityType gt "Create"} would order the stored
     * <em>names</em> alphabetically and return {@code Delete} and {@code Update} rows, which is an
     * artefact of spelling rather than of anything about the activities. Rejecting is the same treatment
     * {@code co} already gets on a non-text column.
     */
    public boolean isOrdered() {
        return type == Type.NUMBER || type == Type.INTEGER || type == Type.STRING;
    }

    /**
     * Reads {@code raw} as this column's type, rejecting anything that does not parse.
     *
     * <p>The coercion happens here, before the query is built, precisely so a bad value is a 400 naming
     * the column rather than a 500 from deep inside Hibernate. The message names the column and the
     * expected shape and never echoes an underlying exception.
     */
    @SuppressWarnings("unchecked")
    public Comparable<?> coerce(String column, String raw) {
        if (raw == null) {
            throw new InvalidFilterValueException("filter on column '" + column + "' requires a value");
        }
        return switch (type) {
            // Deliberately NOT trimmed, unlike the members of an `in` list. Trimming there is part of
            // splitting a delimited list — `"a, b"` has to mean two members — whereas here the string IS
            // the value, and a filter layer that silently edits what the caller asked for answers a
            // different question than the one asked. It would also make a stored value with surrounding
            // whitespace unreachable by any operator, with no escape hatch. An honest empty result beats
            // a helpful wrong one.
            case STRING -> raw;
            case NUMBER -> parseNumber(column, raw);
            case INTEGER -> parseInteger(column, raw);
            case UUID -> parseUuid(column, raw);
            case ENUM -> parseEnum(column, raw, (Class<Enum<?>>) enumType);
        };
    }

    private static Comparable<?> parseNumber(String column, String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidFilterValueException("filter on column '" + column + "' expects a whole number");
        }
    }

    private static Comparable<?> parseInteger(String column, String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            // Covers both "not a number at all" and "a number too large for this column", which are the
            // same answer to the caller: this value cannot be one of these.
            throw new InvalidFilterValueException("filter on column '" + column
                    + "' expects a whole number within the 32-bit range");
        }
    }

    private static Comparable<?> parseUuid(String column, String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidFilterValueException("filter on column '" + column + "' expects a UUID");
        }
    }

    private static Comparable<?> parseEnum(String column, String raw, Class<Enum<?>> type) {
        for (final Enum<?> constant : type.getEnumConstants()) {
            // Case-insensitive, matching the mapper-wide ACCEPT_CASE_INSENSITIVE_ENUMS: a filter value is
            // a string in a body Jackson does not deserialize as an enum, and it would be a poor joke for
            // the same spelling to be accepted in one member of a request and rejected in another.
            if (constant.name().equalsIgnoreCase(raw.trim())) {
                return constant;
            }
        }
        throw new InvalidFilterValueException("filter on column '" + column + "' expects one of "
                + java.util.Arrays.toString(type.getEnumConstants()));
    }
}
