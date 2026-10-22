package com.epam.aidial.analytics.service.otel;

import com.epam.aidial.analytics.service.otel.otlp.OtlpValues;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the OTLP/JSON reading primitives: the dual field spelling, the encodings protojson
 * uses for 64-bit integers and byte fields, and the {@code AnyValue} → text rendering that lets an
 * attribute bag land in a {@code Map(String, String)} column.
 */
class OtlpValuesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    @Test
    void readsBothTheCamelCaseAndProtoFieldSpelling() {
        assertThat(OtlpValues.epochMillis(json("{\"timeUnixNano\":\"1700000000000000000\"}"), "timeUnixNano"))
                .isEqualTo(1_700_000_000_000L);
        assertThat(OtlpValues.epochMillis(json("{\"time_unix_nano\":\"1700000000000000000\"}"), "timeUnixNano"))
                .isEqualTo(1_700_000_000_000L);
    }

    @Test
    void acceptsSixtyFourBitIntegersAsStringOrNumber() {
        // protojson mandates the string form so a JavaScript client cannot lose precision, but plenty of
        // producers emit the number form; both have to land.
        assertThat(OtlpValues.longValue(json("{\"count\":\"42\"}"), "count")).isEqualTo(42L);
        assertThat(OtlpValues.longValue(json("{\"count\":42}"), "count")).isEqualTo(42L);
        assertThat(OtlpValues.longValue(json("{}"), "count")).isNull();
    }

    @Test
    void treatsZeroTimestampAsUnsetRatherThanNineteenSeventy() {
        assertThat(OtlpValues.epochMillis(json("{\"timeUnixNano\":\"0\"}"), "timeUnixNano")).isNull();
        assertThat(OtlpValues.nanos(json("{\"timeUnixNano\":0}"), "timeUnixNano")).isNull();
    }

    @Test
    void timestampBeforeNineteenHundredIsAbsentRatherThanFailingTheExport() {
        // A negative int64 reaches back to 1677; DateTime64 clamps below 1900-01-01.
        assertThat(OtlpValues.epochMillis(json("{\"timeUnixNano\":\"-9000000000000000000\"}"), "timeUnixNano")).isNull();
        assertThat(OtlpValues.epochMillis(json("{\"timeUnixNano\":\"-2208988800000000001\"}"), "timeUnixNano")).isNull();
        assertThat(OtlpValues.epochMillis(json("{\"timeUnixNano\":\"-2208988800000000000\"}"), "timeUnixNano"))
                .isEqualTo(-2_208_988_800_000L);
        assertThat(OtlpValues.timestampMillis(-9_000_000_000_000_000_000L)).isNull();
        assertThat(OtlpValues.timestampMillis(null)).isNull();
    }

    @Test
    void normalizesTraceAndSpanIdsToLowercaseHex() {
        assertThat(OtlpValues.hexId(json("{\"traceId\":\"5B8EFFF798038103D269B633813FC60C\"}"), "traceId"))
                .isEqualTo("5b8efff798038103d269b633813fc60c");
        assertThat(OtlpValues.hexId(json("{}"), "spanId")).isEmpty();
    }

    @Test
    void rendersEveryScalarAnyValueShape() {
        assertThat(render("{\"stringValue\":\"hello\"}")).isEqualTo("hello");
        assertThat(render("{\"boolValue\":true}")).isEqualTo("true");
        assertThat(render("{\"intValue\":\"7\"}")).isEqualTo("7");
        assertThat(render("{\"doubleValue\":1.5}")).isEqualTo("1.5");
        assertThat(render("{\"bytesValue\":\"YWJj\"}")).isEqualTo("YWJj");
        assertThat(render("{}")).isEmpty();
    }

    @Test
    void rendersContainerAnyValuesAsCompactJsonOfTheirRenderedContents() {
        // The reader should see ["a","2"], not the OTLP wrapper objects that carried those values.
        assertThat(render("{\"arrayValue\":{\"values\":[{\"stringValue\":\"a\"},{\"intValue\":\"2\"}]}}"))
                .isEqualTo("[\"a\",\"2\"]");
        assertThat(render("{\"kvlistValue\":{\"values\":[{\"key\":\"k\",\"value\":{\"stringValue\":\"v\"}}]}}"))
                .isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void collectsAttributesIntoStringKeyedMapKeepingLastDuplicate() {
        final var attributes = OtlpValues.attributes(json("""
                {"attributes":[
                  {"key":"http.method","value":{"stringValue":"GET"}},
                  {"key":"host.id","value":{"intValue":"7"}},
                  {"key":"http.method","value":{"stringValue":"POST"}}]}"""));
        assertThat(attributes).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("http.method", "POST", "host.id", "7"));
    }

    @Test
    void absentAttributeListIsEmptyMapRatherThanNull() {
        assertThat(OtlpValues.attributes(json("{}"))).isEmpty();
        assertThat(OtlpValues.attributes(null)).isEmpty();
    }

    @Test
    void nonFiniteDoublesBecomeNullRatherThanFailingTheExport() {
        // Decimal(38, 12) cannot hold NaN or an infinity, and protojson spells them as these strings.
        assertThat(OtlpValues.decimal(json("{\"asDouble\":\"NaN\"}"), "asDouble")).isNull();
        assertThat(OtlpValues.decimal(json("{\"asDouble\":\"Infinity\"}"), "asDouble")).isNull();
        assertThat(OtlpValues.decimal(json("{\"asDouble\":\"-Infinity\"}"), "asDouble")).isNull();
        assertThat(OtlpValues.decimal(json("{\"asDouble\":2.5}"), "asDouble"))
                .isEqualByComparingTo(BigDecimal.valueOf(2.5));
    }

    @Test
    void intOutsideThirtyTwoBitRangeIsAbsentRatherThanWrapped() {
        // Long.intValue() would wrap 3000000000 to a negative count.
        assertThat(OtlpValues.intValue(json("{\"droppedAttributesCount\":3000000000}"), "droppedAttributesCount")).isZero();
        assertThat(OtlpValues.intValue(json("{\"droppedAttributesCount\":\"-3000000000\"}"), "droppedAttributesCount")).isZero();
        assertThat(OtlpValues.nullableInt(json("{\"severityNumber\":3000000000}"), "severityNumber")).isNull();
        assertThat(OtlpValues.nullableInt(json("{\"severityNumber\":2147483647}"), "severityNumber")).isEqualTo(Integer.MAX_VALUE);
        assertThat(OtlpValues.intValue(json("{\"flags\":-2147483648}"), "flags")).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void decimalTooLargeForTheColumnBecomesNullRatherThanFailingTheExport() {
        // Decimal(38, 12) holds 26 integer digits; 10^26 and beyond would get the whole export rejected.
        assertThat(OtlpValues.decimal(json("{\"asDouble\":1e30}"), "asDouble")).isNull();
        assertThat(OtlpValues.decimal(json("{\"asDouble\":-1e26}"), "asDouble")).isNull();
        assertThat(OtlpValues.decimal(json("{\"asDouble\":\"1e26\"}"), "asDouble")).isNull();
        assertThat(OtlpValues.decimal(json("{\"asDouble\":\"99999999999999999999999999.5\"}"), "asDouble"))
                .isEqualByComparingTo(new BigDecimal("99999999999999999999999999.5"));
    }

    @Test
    void overlongDecimalStringIsAbsentRatherThanParsed() {
        final String digits = "1".repeat(1001);
        assertThat(OtlpValues.decimal(json("{\"asDouble\":\"" + digits + "\"}"), "asDouble")).isNull();
        assertThat(OtlpValues.decimalArray(json("{\"explicitBounds\":[\"" + digits + "\"]}"), "explicitBounds"))
                .containsExactly(BigDecimal.ZERO);
    }

    @Test
    void decimalArrayElementTooLargeForTheColumnBecomesZeroKeepingAlignment() {
        assertThat(OtlpValues.decimalArray(json("{\"explicitBounds\":[0.5,1e30,\"-1e27\",\"NaN\",2]}"), "explicitBounds"))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("0.5"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TWO);
    }

    @Test
    void readsNumericArrays() {
        assertThat(OtlpValues.longArray(json("{\"bucketCounts\":[\"1\",2,\"3\"]}"), "bucketCounts"))
                .containsExactly(1L, 2L, 3L);
        assertThat(OtlpValues.decimalArray(json("{\"explicitBounds\":[0.5,1]}"), "explicitBounds"))
                .hasSize(2);
        assertThat(OtlpValues.longArray(json("{}"), "bucketCounts")).isEmpty();
    }

    @Test
    void jsonArrayTextFallsBackToAnEmptyArrayLiteral() {
        assertThat(OtlpValues.jsonArrayText(json("{\"events\":[{\"name\":\"e\"}]}"), "events"))
                .isEqualTo("[{\"name\":\"e\"}]");
        assertThat(OtlpValues.jsonArrayText(json("{}"), "events")).isEqualTo("[]");
    }

    @Test
    void resolvesAnEnumEncodedAsItsProtoNumberToItsName() {
        final String[] kinds = {"SPAN_KIND_UNSPECIFIED", "SPAN_KIND_INTERNAL", "SPAN_KIND_SERVER"};
        assertThat(OtlpValues.enumName(json("{\"kind\":\"SPAN_KIND_SERVER\"}"), "kind", kinds))
                .isEqualTo("SPAN_KIND_SERVER");
        assertThat(OtlpValues.enumName(json("{\"kind\":2}"), "kind", kinds)).isEqualTo("SPAN_KIND_SERVER");
        assertThat(OtlpValues.enumName(json("{\"kind\":\"2\"}"), "kind", kinds)).isEqualTo("SPAN_KIND_SERVER");
        // A number from a newer OTLP version is kept as received rather than dropped.
        assertThat(OtlpValues.enumName(json("{\"kind\":9}"), "kind", kinds)).isEqualTo("9");
        assertThat(OtlpValues.enumName(json("{}"), "kind", kinds)).isEmpty();
    }

    @Test
    void readsWronglyShapedMemberAsAbsentRatherThanThrowing() {
        // Jackson 3's plain asString()/longValue() throw on these; a JsonNodeException escaping here
        // would surface as a 500 for what is only a producer's malformed field.
        assertThat(OtlpValues.text(json("{\"severityText\":{}}"), "severityText")).isEmpty();
        assertThat(OtlpValues.text(json("{\"severityText\":[1,2]}"), "severityText")).isEmpty();
        assertThat(OtlpValues.hexId(json("{\"traceId\":[]}"), "traceId")).isEmpty();
        assertThat(OtlpValues.longValue(json("{\"count\":{}}"), "count")).isNull();
        assertThat(OtlpValues.longValue(json("{\"count\":1.5}"), "count")).isNull();
        assertThat(OtlpValues.booleanValue(json("{\"isMonotonic\":{}}"), "isMonotonic")).isFalse();
        assertThat(OtlpValues.decimal(json("{\"asDouble\":[]}"), "asDouble")).isNull();
        assertThat(OtlpValues.longArray(json("{\"bucketCounts\":[{},1.5]}"), "bucketCounts"))
                .containsExactly(0L, 0L);
        assertThat(OtlpValues.decimalArray(json("{\"explicitBounds\":[{}]}"), "explicitBounds"))
                .containsExactly(BigDecimal.ZERO);
        assertThat(render("{\"stringValue\":{\"nested\":true}}")).isEmpty();
        assertThat(render("{\"doubleValue\":[]}")).isEmpty();
        assertThat(render("{\"bytesValue\":{}}")).isEmpty();
    }

    private static String render(String anyValueJson) {
        return OtlpValues.renderAnyValue(json(anyValueJson));
    }
}
