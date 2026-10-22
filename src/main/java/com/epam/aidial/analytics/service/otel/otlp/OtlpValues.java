package com.epam.aidial.analytics.service.otel.otlp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * The OTLP/JSON reading primitives shared by the three flatteners: field lookup, timestamp and id
 * normalization, and the {@code AnyValue} → text rendering that lets an attribute bag land in a
 * {@code Map(String, String)} column.
 *
 * <p><b>Both field spellings are accepted.</b> OTLP/JSON is protojson, which emits lowerCamelCase
 * ({@code timeUnixNano}) but accepts the original proto field name ({@code time_unix_nano}) on input.
 * Real payloads carry both — hand-written exporters and some SDK configurations emit the proto spelling
 * — so every lookup here tries the camel name first and falls back to the derived snake_case one.
 *
 * <p><b>Unknown fields are ignored by construction.</b> Nothing here binds to a fixed shape; a field the
 * mapping does not read is simply never asked for, so a payload from a newer OTLP version still lands
 * rather than failing to deserialize.
 *
 * <p><b>Every reader here is total.</b> Jackson 3's plain accessors ({@code asString()},
 * {@code longValue()}, {@code doubleValue()}) <em>throw</em> when the node cannot be coerced — a container
 * where a scalar belongs, a fractional or out-of-range number — and a {@code JsonNodeException} escaping
 * the flattener would surface as a 500 for what is a caller's malformed field. The defaulting variants are
 * used throughout instead, so a wrongly shaped member reads as absent (empty text, null number) and the
 * rest of the record still lands.
 */
public final class OtlpValues {

    /**
     * The smallest magnitude a {@code decimal} column cannot hold. The landing columns are
     * {@code Decimal(38, 12)} — 38 digits of which 12 are fractional, so 26 integer digits — and the row-write
     * path rejects anything at or past {@code 10^26}; reading such a value as absent here keeps one oversized
     * data point from failing the whole export. Mirrors {@code ClickHouseTypeMapper.DECIMAL_PRECISION/SCALE}.
     */
    private static final BigDecimal DECIMAL_LIMIT = BigDecimal.TEN.pow(26);

    /**
     * The longest numeric string parsed into a {@code BigDecimal}. Longer text is read as absent rather
     * than parsed, so an absurd string cannot buy unbounded parsing work; it is the same bound Jackson's
     * {@code StreamReadConstraints} puts on the length of a JSON number.
     */
    private static final int MAX_NUMBER_TEXT_LENGTH = 1000;

    /** {@code 1900-01-01T00:00:00Z}, the earliest instant a {@code DateTime64} landing column holds. */
    private static final long MIN_TIMESTAMP_MILLIS = Instant.parse("1900-01-01T00:00:00Z").toEpochMilli();

    private OtlpValues() {
    }

    // ------------------------------------------------------------------ field access

    /**
     * The member of {@code node} named {@code camelName}, or the same name in proto snake_case; null when
     * {@code node} is null or carries neither (a JSON null counts as absent).
     */
    public static JsonNode field(JsonNode node, String camelName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(camelName);
        if (value == null || value.isNull()) {
            value = node.get(snakeCase(camelName));
        }
        return value == null || value.isNull() ? null : value;
    }

    /**
     * The named member as text, or {@code ""} when absent. Non-string scalars render as their JSON text.
     *
     * <p>A member of the wrong JSON shape (an object or array where a scalar belongs) also renders as
     * {@code ""}: Jackson 3's plain {@code asString()} <em>throws</em> on a container, and letting that
     * escape would turn a client's shape mistake into a 500 instead of landing the rest of the record.
     */
    public static String text(JsonNode node, String camelName) {
        final JsonNode value = field(node, camelName);
        if (value == null) {
            return "";
        }
        return value.isString() ? value.stringValue() : value.asString("");
    }

    /**
     * The named member as an int, or {@code 0} when absent, not numeric, or outside the 32-bit range —
     * narrowing an out-of-range long would silently wrap it (3000000000 → a negative count).
     */
    public static int intValue(JsonNode node, String camelName) {
        final Integer value = nullableInt(node, camelName);
        return value == null ? 0 : value;
    }

    /** The named member as a boxed int, or null when absent, not numeric, or outside the 32-bit range. */
    public static Integer nullableInt(JsonNode node, String camelName) {
        final Long value = longValue(node, camelName);
        return value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE ? null : value.intValue();
    }

    /**
     * The named member as a boxed long, or null when absent or unparseable. OTLP/JSON encodes 64-bit
     * integers as <em>strings</em> (protojson's rule, so a JavaScript client cannot silently lose
     * precision), but plenty of producers emit them as numbers, so both are accepted.
     */
    public static Long longValue(JsonNode node, String camelName) {
        final JsonNode value = field(node, camelName);
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            // longValue() throws on a fractional or out-of-range number; the Opt form reports it as absent,
            // which is what every caller here already handles.
            final OptionalLong exact = value.longValueOpt();
            return exact.isPresent() ? exact.getAsLong() : null;
        }
        if (value.isString()) {
            try {
                return Long.parseLong(value.stringValue().trim());
            } catch (NumberFormatException notNumeric) {
                return null;
            }
        }
        return null;
    }

    /** The named member as a boolean, or {@code false} when absent. */
    public static boolean booleanValue(JsonNode node, String camelName) {
        final JsonNode value = field(node, camelName);
        if (value == null) {
            return false;
        }
        return value.isBoolean() ? value.booleanValue() : Boolean.parseBoolean(value.asString(""));
    }

    /**
     * The named member as an OTLP enum <em>name</em>, or {@code ""} when absent.
     *
     * <p>proto3 JSON — which OTLP/HTTP JSON is — lets a producer encode an enum either as its name
     * ({@code "SPAN_KIND_SERVER"}) or as its integer value ({@code 2}), and real exporters emit both. The
     * landing schemas specify the name, so a numeric encoding is resolved through {@code names}, indexed
     * by the enum's proto number. A value that is neither a known number nor an empty one is stored as
     * received, so an enum member added by a newer OTLP version still lands rather than being dropped.
     */
    public static String enumName(JsonNode node, String camelName, String... names) {
        final String text = text(node, camelName);
        if (text.isEmpty()) {
            return "";
        }
        final int number = protoNumber(text);
        return number >= 0 && number < names.length ? names[number] : text;
    }

    /**
     * The named member as an OTLP enum <em>number</em>, or {@code 0} when absent or unrecognised.
     *
     * <p>The inverse of {@link #enumName} for the enums whose landing column is numeric: proto3 JSON
     * encodes an enum as its name by default ({@code "SEVERITY_NUMBER_INFO"}) and as its integer value
     * when the producer asks for it ({@code 9}), and a receiver must accept both. A name is resolved
     * through {@code names}, indexed by the enum's proto number. An unknown member — a name from a newer
     * OTLP version, or a value of the wrong shape — reads as {@code 0}, the enums' "unspecified" member,
     * because the column cannot hold anything else.
     */
    public static int enumNumber(JsonNode node, String camelName, String... names) {
        final String text = text(node, camelName).trim();
        if (text.isEmpty()) {
            return 0;
        }
        final int number = protoNumber(text);
        if (number >= 0) {
            return number;
        }
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(text)) {
                return i;
            }
        }
        return 0;
    }

    /** {@code text} as a proto enum number, or -1 when it is not one (i.e. it is already a name). */
    private static int protoNumber(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException notNumeric) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ scalars

    /**
     * An OTLP nanosecond timestamp as epoch milliseconds, or null when absent, zero, or before
     * {@code 1900-01-01T00:00:00Z}. OTLP treats 0 as "unset" for every timestamp field, so it is reported as
     * absent rather than as 1970.
     */
    public static Long epochMillis(JsonNode node, String camelName) {
        final Long nanos = longValue(node, camelName);
        return nanos == null || nanos == 0L ? null : timestampMillis(nanos);
    }

    /**
     * Epoch nanoseconds as the epoch milliseconds of a timestamp column, or null when absent or before
     * {@code 1900-01-01T00:00:00Z}. A negative int64 reaches back to 1677, but ClickHouse's
     * {@code DateTime64} clamps below 1900 and the row-write path rejects such an instant, so it is read as
     * absent rather than failing the whole export. The int64 upper end (2262) is already in range.
     */
    public static Long timestampMillis(Long nanos) {
        if (nanos == null) {
            return null;
        }
        final long millis = Math.floorDiv(nanos, 1_000_000L);
        return millis < MIN_TIMESTAMP_MILLIS ? null : millis;
    }

    /** An OTLP nanosecond timestamp as-is, or null when absent or zero. */
    public static Long nanos(JsonNode node, String camelName) {
        final Long value = longValue(node, camelName);
        return value == null || value == 0L ? null : value;
    }

    /**
     * A trace/span id as lowercase hex, or {@code ""} when absent. OTLP/JSON hex-encodes these two byte
     * fields rather than base64-encoding them like every other {@code bytes} field, so the value arrives
     * as hex already and only its case needs normalizing.
     */
    public static String hexId(JsonNode node, String camelName) {
        return text(node, camelName).toLowerCase(Locale.ROOT);
    }

    /**
     * An OTLP double as a {@code BigDecimal}, or null when absent, non-finite, or too large for the column.
     * {@code NaN} and the infinities have no {@code Decimal(38, 12)} representation, and OTLP/JSON spells
     * them as the strings {@code "NaN"} / {@code "Infinity"} / {@code "-Infinity"}; a finite value at or past
     * {@code 10^26} has none either. Storing null is what lets the surrounding data point still land instead
     * of failing the whole export.
     */
    public static BigDecimal decimal(JsonNode node, String camelName) {
        final JsonNode value = field(node, camelName);
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            return finite(value.doubleValue(Double.NaN)) ? representable(value.decimalValue()) : null;
        }
        if (value.isString()) {
            return parseDecimalOrNull(value.stringValue());
        }
        return null;
    }

    /** The named member as a list of longs (an absent or non-array member is the empty list). */
    public static List<Long> longArray(JsonNode node, String camelName) {
        final JsonNode value = field(node, camelName);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        final Long[] items = new Long[value.size()];
        for (int i = 0; i < value.size(); i++) {
            final JsonNode item = value.get(i);
            items[i] = item.isNumber() ? item.longValue(0L) : parseLongOrZero(item.asString(""));
        }
        return List.of(items);
    }

    /**
     * The named member as a list of decimals (an absent or non-array member is the empty list). A
     * non-finite or unrepresentably large element becomes {@code 0}: the column is a non-nullable {@code Array(Decimal)}, which
     * cannot hold a null element, and dropping the element instead would silently misalign a histogram's
     * bounds with its counts.
     */
    public static List<BigDecimal> decimalArray(JsonNode node, String camelName) {
        final JsonNode value = field(node, camelName);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        final BigDecimal[] items = new BigDecimal[value.size()];
        for (int i = 0; i < value.size(); i++) {
            final JsonNode item = value.get(i);
            final BigDecimal decoded = item.isNumber() && finite(item.doubleValue(Double.NaN))
                    ? representable(item.decimalValue())
                    : parseDecimalOrNull(item.asString(""));
            items[i] = decoded == null ? BigDecimal.ZERO : decoded;
        }
        return List.of(items);
    }

    // ------------------------------------------------------------------ attributes

    /**
     * An OTLP {@code KeyValue[]} as a string-keyed string map, ready for a {@code Map(String, String)}
     * column. A key repeated within one list keeps its last occurrence, matching how a map literal
     * behaves and leaving no ambiguity for the reader.
     */
    public static Map<String, String> attributes(JsonNode owner) {
        final JsonNode list = field(owner, "attributes");
        if (list == null || !list.isArray()) {
            return Map.of();
        }
        final Map<String, String> attributes = new LinkedHashMap<>();
        for (final JsonNode entry : list) {
            final JsonNode key = field(entry, "key");
            if (key == null || !key.isString()) {
                continue;
            }
            attributes.put(key.stringValue(), renderAnyValue(field(entry, "value")));
        }
        return attributes;
    }

    /**
     * Renders an OTLP {@code AnyValue} to text.
     *
     * <p>Every attribute value and a log record's body go through here, so the same structure renders
     * identically wherever it appears. Scalars render as their natural text; a {@code bytesValue} keeps
     * the base64 text it arrived as (re-encoding it would only lose information); and the two container
     * shapes render as compact JSON of their <em>rendered</em> contents, so a reader sees
     * {@code ["a","2"]} rather than the OTLP wrapper objects.
     */
    public static String renderAnyValue(JsonNode value) {
        if (value == null || !value.isObject()) {
            return "";
        }
        final JsonNode string = field(value, "stringValue");
        if (string != null) {
            return string.isString() ? string.stringValue() : string.asString("");
        }
        final JsonNode bool = field(value, "boolValue");
        if (bool != null) {
            return bool.isBoolean() ? Boolean.toString(bool.booleanValue()) : bool.asString("");
        }
        final JsonNode integer = field(value, "intValue");
        if (integer != null) {
            return integer.isString() ? integer.stringValue() : integer.asString("");
        }
        final JsonNode number = field(value, "doubleValue");
        if (number != null) {
            return number.isNumber() ? shortestDouble(number.doubleValue()) : number.asString("");
        }
        final JsonNode bytes = field(value, "bytesValue");
        if (bytes != null) {
            return bytes.isString() ? bytes.stringValue() : bytes.asString("");
        }
        final JsonNode array = field(value, "arrayValue");
        if (array != null) {
            return renderArrayValue(array);
        }
        final JsonNode kvlist = field(value, "kvlistValue");
        if (kvlist != null) {
            return renderKvlistValue(kvlist);
        }
        return "";
    }

    private static String renderArrayValue(JsonNode arrayValue) {
        final JsonNode values = field(arrayValue, "values");
        final ArrayNode rendered = JsonNodeFactory.instance.arrayNode();
        if (values != null && values.isArray()) {
            for (final JsonNode item : values) {
                rendered.add(renderAnyValue(item));
            }
        }
        return rendered.toString();
    }

    private static String renderKvlistValue(JsonNode kvlistValue) {
        final JsonNode values = field(kvlistValue, "values");
        final ObjectNode rendered = JsonNodeFactory.instance.objectNode();
        if (values != null && values.isArray()) {
            for (final JsonNode entry : values) {
                final JsonNode key = field(entry, "key");
                if (key != null && key.isString()) {
                    rendered.put(key.stringValue(), renderAnyValue(field(entry, "value")));
                }
            }
        }
        return rendered.toString();
    }

    // ------------------------------------------------------------------ helpers

    /** Iterates a JSON array member, yielding nothing when it is absent or not an array. */
    public static Iterable<JsonNode> elements(JsonNode node, String camelName) {
        final JsonNode value = field(node, camelName);
        return value == null || !value.isArray() ? List.of() : value;
    }

    /** The compact JSON text of a member, or {@code "[]"} when it is absent or not an array. */
    public static String jsonArrayText(JsonNode node, String camelName) {
        final JsonNode value = field(node, camelName);
        return value == null || !value.isArray() ? "[]" : value.toString();
    }

    /**
     * The shortest decimal form that round-trips back to {@code value}, or the OTLP spelling of a
     * non-finite double. {@link Double#toString} is already the shortest round-tripping form, and its
     * {@code NaN}/{@code Infinity}/{@code -Infinity} spellings are exactly the ones protojson uses.
     */
    public static String shortestDouble(double value) {
        return Double.toString(value);
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static long parseLongOrZero(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException | NullPointerException notNumeric) {
            return 0L;
        }
    }

    /** The text as a representable decimal, or null when absent, overlong, unparseable or out of range. */
    private static BigDecimal parseDecimalOrNull(String text) {
        if (text == null || text.length() > MAX_NUMBER_TEXT_LENGTH) {
            return null;
        }
        try {
            return representable(new BigDecimal(text.trim()));
        } catch (NumberFormatException notNumeric) {
            return null;
        }
    }

    /** {@code value} when a {@code Decimal(38, 12)} column can hold it, otherwise null. */
    private static BigDecimal representable(BigDecimal value) {
        return value == null || value.abs().compareTo(DECIMAL_LIMIT) >= 0 ? null : value;
    }

    /** {@code timeUnixNano} → {@code time_unix_nano}; ASCII-only, which every proto field name is. */
    private static String snakeCase(String camelName) {
        final StringBuilder snake = new StringBuilder(camelName.length() + 4);
        for (int i = 0; i < camelName.length(); i++) {
            final char ch = camelName.charAt(i);
            if (Character.isUpperCase(ch)) {
                snake.append('_').append(Character.toLowerCase(ch));
            } else {
                snake.append(ch);
            }
        }
        return snake.toString();
    }
}
