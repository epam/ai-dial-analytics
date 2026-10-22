package com.epam.aidial.analytics.sync.mapping;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises each registered {@link Deriver} in isolation: deterministic UUID hashing, event-kind
 * classification, JSON sub-document extraction (string/uint/array-length, with source fallback and
 * both string-encoded and structured inputs), byte length, and the HTTP-success predicate.
 */
class DeriverRegistryTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final DeriverRegistry registry = new DeriverRegistry(json);

    private Object derive(String name, List<JsonNode> sources, Map<String, Object> args) {
        return registry.get(name).derive(sources, args);
    }

    private JsonNode str(String value) {
        return json.valueToTree(value);
    }

    private JsonNode num(long value) {
        return json.valueToTree(value);
    }

    private JsonNode obj(String rawJson) {
        return json.readTree(rawJson);
    }

    private JsonNode missing() {
        return json.readTree("{}").path("absent");
    }

    @Test
    void registryExposesTheClosedSet() {
        assertThat(registry.contains("hash-uuid")).isTrue();
        assertThat(registry.contains("no-such-deriver")).isFalse();
        assertThat(registry.names()).contains(
                "hash-uuid", "classify-event-kind", "json-extract-string", "json-extract-uint",
                "json-extract-rate", "json-array-length", "byte-length", "success-from-status");
    }

    @Test
    void jsonExtractRateNormalizesNumbersBooleansAndAbsence() {
        // Numbers pass through as their integer value (including negatives).
        assertThat(derive("json-extract-rate", List.of(str("{\"rate\":1}")), Map.of("path", "rate")))
                .isEqualTo(1L);
        assertThat(derive("json-extract-rate", List.of(str("{\"rate\":-1}")), Map.of("path", "rate")))
                .isEqualTo(-1L);
        // Booleans normalize: true -> 1, false -> 0.
        assertThat(derive("json-extract-rate", List.of(str("{\"rate\":true}")), Map.of("path", "rate")))
                .isEqualTo(1L);
        assertThat(derive("json-extract-rate", List.of(str("{\"rate\":false}")), Map.of("path", "rate")))
                .isEqualTo(0L);
        // Absent, blank, or non-numeric -> null (so the column's nullability fallback applies).
        assertThat(derive("json-extract-rate", List.of(str("{\"other\":1}")), Map.of("path", "rate")))
                .isNull();
        assertThat(derive("json-extract-rate", List.of(missing()), Map.of("path", "rate")))
                .isNull();
        assertThat(derive("json-extract-rate", List.of(str("{\"rate\":\"nope\"}")), Map.of("path", "rate")))
                .isNull();
    }

    @Test
    void hashUuidIsDeterministicVersion5AndKeyed() {
        Object first = derive("hash-uuid", List.of(str("t1"), str("s1")), Map.of());
        Object again = derive("hash-uuid", List.of(str("t1"), str("s1")), Map.of());
        assertThat(first).isEqualTo(again);
        assertThat(UUID.fromString((String) first).version()).isEqualTo(5);

        Object other = derive("hash-uuid", List.of(str("t1"), str("s2")), Map.of());
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void classifyEventKindMatchesUriPatterns() {
        assertThat(derive("classify-event-kind", List.of(str("/openai/deployments/x/chat/completions")), Map.of()))
                .isEqualTo("llm_call");
        assertThat(derive("classify-event-kind", List.of(str("/openai/deployments/x/embeddings")), Map.of()))
                .isEqualTo("embedding");
        assertThat(derive("classify-event-kind", List.of(str("/v1/deployments/srv/mcp")), Map.of()))
                .isEqualTo("mcp");
        assertThat(derive("classify-event-kind", List.of(str("/v1/route/foo")), Map.of()))
                .isEqualTo("route");
        assertThat(derive("classify-event-kind", List.of(str("/v1/health")), Map.of()))
                .isEqualTo("");
    }

    @Test
    void jsonExtractStringHandlesStringEncodedAndStructuredSourcesAndFallback() {
        assertThat(derive("json-extract-string", List.of(str("{\"id\":\"chatcmpl-1\"}")), Map.of("path", "id")))
                .isEqualTo("chatcmpl-1");
        assertThat(derive("json-extract-string", List.of(obj("{\"id\":\"x\"}")), Map.of("path", "id")))
                .isEqualTo("x");
        // First source absent, second carries the value.
        assertThat(derive("json-extract-string", List.of(missing(), str("{\"id\":\"y\"}")), Map.of("path", "id")))
                .isEqualTo("y");
        // Path absent everywhere -> null (so the column's fallback applies).
        assertThat(derive("json-extract-string", List.of(str("{\"nope\":1}")), Map.of("path", "id")))
                .isNull();
    }

    /**
     * The string extractor reads ANY scalar's verbatim text, not only a JSON string. Incidental to most
     * callers, load-bearing for {@code rate_analytics.rate_raw} — see the pairing test below.
     */
    @Test
    void jsonExtractStringReadsAnyScalarAsItsVerbatimText() {
        // Booleans read as their literal spelling, which is what keeps a boolean submission
        // distinguishable from the integer the rate normalizer maps it to.
        assertThat(derive("json-extract-string", List.of(str("{\"rate\":true}")), Map.of("path", "rate")))
                .isEqualTo("true");
        assertThat(derive("json-extract-string", List.of(str("{\"rate\":false}")), Map.of("path", "rate")))
                .isEqualTo("false");
        // Numbers read as their literal digits, negatives and fractions included.
        assertThat(derive("json-extract-string", List.of(str("{\"rate\":0}")), Map.of("path", "rate")))
                .isEqualTo("0");
        assertThat(derive("json-extract-string", List.of(str("{\"rate\":-1}")), Map.of("path", "rate")))
                .isEqualTo("-1");
        assertThat(derive("json-extract-string", List.of(str("{\"rate\":1.5}")), Map.of("path", "rate")))
                .isEqualTo("1.5");
        // A JSON string reads as its unquoted content.
        assertThat(derive("json-extract-string", List.of(str("{\"rate\":\"1\"}")), Map.of("path", "rate")))
                .isEqualTo("1");
        // Absent -> null; blank -> "" (the engine's absent/blank fallback then nulls it on a nullable
        // column, which MappingEngineTest covers end to end).
        assertThat(derive("json-extract-string", List.of(str("{}")), Map.of("path", "rate")))
                .isNull();
        assertThat(derive("json-extract-string", List.of(str("{\"rate\":\"\"}")), Map.of("path", "rate")))
                .isEqualTo("");
    }

    /**
     * A non-scalar yields the empty default rather than the node's JSON text, so a nullable column takes
     * its blank fallback instead of storing a serialized document.
     */
    @Test
    void jsonExtractStringYieldsBlankForObjectAndArrayMembers() {
        assertThat(derive("json-extract-string", List.of(str("{\"rate\":{\"a\":1}}")), Map.of("path", "rate")))
                .isEqualTo("");
        assertThat(derive("json-extract-string", List.of(str("{\"rate\":[1,2]}")), Map.of("path", "rate")))
                .isEqualTo("");
    }

    /**
     * The pairing the {@code rate}/{@code rate_raw} column pair exists for: both derivers read the same
     * node, the normalizer collapses boolean {@code false} and numeric {@code 0} onto the same value, and
     * only the raw text separates them. Asserted here because neither deriver's own test states the
     * relationship, and it is the relationship — not either column — that answers "how much negative
     * feedback is there?".
     */
    @Test
    void rateAndRawRateTogetherSeparateBooleanFalseFromNumericZero() {
        final Map<String, Object> path = Map.of("path", "rate");
        final List<JsonNode> booleanFalse = List.of(str("{\"rate\":false}"));
        final List<JsonNode> numericZero = List.of(str("{\"rate\":0}"));

        // Indistinguishable through the normalized column...
        assertThat(derive("json-extract-rate", booleanFalse, path)).isEqualTo(0L);
        assertThat(derive("json-extract-rate", numericZero, path)).isEqualTo(0L);
        // ...and separated by the raw one.
        assertThat(derive("json-extract-string", booleanFalse, path)).isEqualTo("false");
        assertThat(derive("json-extract-string", numericZero, path)).isEqualTo("0");
    }

    @Test
    void jsonExtractUintReadsNestedPath() {
        assertThat(derive("json-extract-uint",
                List.of(str("{\"usage\":{\"cache_creation_input_tokens\":7}}")),
                Map.of("path", "usage.cache_creation_input_tokens"))).isEqualTo(7L);
        assertThat(derive("json-extract-uint", List.of(str("{\"usage\":{}}")), Map.of("path", "usage.x")))
                .isNull();
    }

    @Test
    void jsonArrayLengthCountsElements() {
        assertThat(derive("json-array-length", List.of(str("{\"messages\":[{},{},{}]}")), Map.of("path", "messages")))
                .isEqualTo(3L);
        assertThat(derive("json-array-length", List.of(str("{\"messages\":\"notarray\"}")), Map.of("path", "messages")))
                .isEqualTo(0L);
        assertThat(derive("json-array-length", List.of(missing()), Map.of("path", "messages")))
                .isEqualTo(0L);
    }

    @Test
    void byteLengthUsesUtf8() {
        assertThat(derive("byte-length", List.of(str("RAWRESP")), Map.of())).isEqualTo(7L);
        assertThat(derive("byte-length", List.of(str("é")), Map.of())).isEqualTo(2L); // 2 UTF-8 bytes
        assertThat(derive("byte-length", List.of(missing()), Map.of())).isEqualTo(0L);
    }

    @Test
    void successFromStatusCoversBoundaries() {
        assertThat(derive("success-from-status", List.of(num(200)), Map.of())).isEqualTo(true);
        assertThat(derive("success-from-status", List.of(num(299)), Map.of())).isEqualTo(true);
        assertThat(derive("success-from-status", List.of(str("200")), Map.of())).isEqualTo(true);
        assertThat(derive("success-from-status", List.of(num(199)), Map.of())).isEqualTo(false);
        assertThat(derive("success-from-status", List.of(num(500)), Map.of())).isEqualTo(false);
        assertThat(derive("success-from-status", List.of(missing()), Map.of())).isEqualTo(false);
    }
}
