package com.epam.aidial.analytics.sync.mapping;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The strict readers, over the producer shapes Jackson's lenient readers turned into an invented value. */
class CoercionTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode node(String raw) {
        return json.readTree(raw);
    }

    @Test
    void longReadsIntegralNumbersAndNumericText() {
        assertThat(Coercion.toLong(node("123"))).isEqualTo(123L);
        assertThat(Coercion.toLong(node("-5"))).isEqualTo(-5L);
        assertThat(Coercion.toLong(node("\"123\""))).isEqualTo(123L);
        assertThat(Coercion.toLong(node("\" 42 \""))).isEqualTo(42L);
        assertThat(Coercion.toLong(node("1e3"))).isEqualTo(1000L);
        assertThat(Coercion.toLong(node("\"1e3\""))).isEqualTo(1000L);
        assertThat(Coercion.toLong(node("\"1000.0\""))).isEqualTo(1000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12.7", "\"12.7\"", "\"abc\"", "true", "{\"a\":1}", "[1]", "99999999999999999999", "\"99999999999999999999\""})
    void longRefusesWhatItCannotRepresent(String raw) {
        assertThat(Coercion.toLong(node(raw))).isSameAs(Coercion.FAILED);
    }

    /** A non-finite double has no decimal value; it fails this field instead of throwing out of the record. */
    @ParameterizedTest
    @ValueSource(strings = {"1e400", "-1e400"})
    void nonFiniteNumbersAreFailuresNotExceptions(String raw) {
        assertThat(Coercion.toLong(node(raw))).isSameAs(Coercion.FAILED);
        assertThat(Coercion.toBool(node(raw))).isSameAs(Coercion.FAILED);
        assertThat(Coercion.toDecimal(node(raw))).isSameAs(Coercion.FAILED);
    }

    /** Text too long to be any number a column stores is refused without being parsed. */
    @Test
    void overlongNumericTextIsRefusedQuickly() {
        String digits = "1".repeat(300_000);
        long start = System.nanoTime();

        assertThat(Coercion.toLong(node("\"" + digits + "\""))).isSameAs(Coercion.FAILED);
        assertThat(Coercion.toDecimal(node("\"" + digits + "\""))).isSameAs(Coercion.FAILED);

        assertThat(System.nanoTime() - start).isLessThan(1_000_000_000L);
        assertThat(Coercion.toLong(node("\"" + "9".repeat(Coercion.MAX_NUMBER_TEXT + 1) + "\""))).isSameAs(Coercion.FAILED);
    }

    @Test
    void decimalsBeyondTheWidestColumnAreRefused() {
        assertThat(Coercion.toDecimal(node("\"1e999999999\""))).isSameAs(Coercion.FAILED);
        assertThat(Coercion.toDecimal(node("\"1e-77\""))).isSameAs(Coercion.FAILED);
        assertThat(Coercion.toLong(node("\"1e999999999\""))).isSameAs(Coercion.FAILED);
        assertThat(Coercion.toDecimal(node("\"1e-76\""))).isEqualTo(new BigDecimal("1e-76"));
    }

    @Test
    void blankTextIsAbsentForEveryNonStringKind() {
        assertThat(Coercion.toLong(node("\"\""))).isNull();
        assertThat(Coercion.toLong(node("\"  \""))).isNull();
        assertThat(Coercion.toDecimal(node("\"\""))).isNull();
        assertThat(Coercion.toBool(node("\" \""))).isNull();
    }

    @Test
    void decimalKeepsTheProducersPrecision() {
        assertThat(Coercion.toDecimal(node("\"0.000123\""))).isEqualTo(new BigDecimal("0.000123"));
        assertThat(Coercion.toDecimal(node("0.0012"))).isEqualTo(new BigDecimal("0.0012"));
        assertThat(Coercion.toDecimal(node("7"))).isEqualTo(new BigDecimal("7"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "\"abc\"", "{\"a\":1}", "[1]"})
    void decimalRefusesWhatItCannotRepresent(String raw) {
        assertThat(Coercion.toDecimal(node(raw))).isSameAs(Coercion.FAILED);
    }

    @Test
    void boolReadsBooleansZeroOneAndTheirText() {
        assertThat(Coercion.toBool(node("true"))).isEqualTo(true);
        assertThat(Coercion.toBool(node("false"))).isEqualTo(false);
        assertThat(Coercion.toBool(node("1"))).isEqualTo(true);
        assertThat(Coercion.toBool(node("0"))).isEqualTo(false);
        assertThat(Coercion.toBool(node("\"TRUE\""))).isEqualTo(true);
        assertThat(Coercion.toBool(node("\" false \""))).isEqualTo(false);
        assertThat(Coercion.toBool(node("\"1\""))).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "12.7", "\"yes\"", "{\"a\":1}", "[true]"})
    void boolRefusesWhatItCannotRepresent(String raw) {
        assertThat(Coercion.toBool(node(raw))).isSameAs(Coercion.FAILED);
    }

    @Test
    void stringArrayKeepsStructuredElementsAsJsonText() {
        assertThat(Coercion.toStringArray(node("[\"a\", {\"k\":1}, [2], 3, null]"))).isEqualTo(List.of("a", "{\"k\":1}", "[2]", "3", ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"a\"", "{\"a\":1}", "3"})
    void stringArrayRefusesNonArray(String raw) {
        assertThat(Coercion.toStringArray(node(raw))).isSameAs(Coercion.FAILED);
    }
}
