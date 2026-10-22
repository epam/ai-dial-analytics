package com.epam.aidial.analytics.sync.mapping;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransformRegistryTest {

    // Jackson 3, deliberately: MappingEngine hands this transform a tools.jackson node, and fixtures built
    // with the Jackson 2 ObjectMapper would make a wrong-generation import in the registry pass here.
    private static final JsonMapper MAPPER = new JsonMapper();

    private final TransformRegistry registry = new TransformRegistry();

    @Test
    void registersExactlyTheKnownTransforms() {
        assertThat(registry.contains("iso-to-ch-datetime")).isTrue();
        assertThat(registry.contains("string-array")).isTrue();
        assertThat(registry.contains("lowercase-json-keys")).isTrue();
        assertThat(registry.contains("nope")).isFalse();
        assertThat(registry.get("nope")).isNull();
    }

    @Test
    void isoToChDateTimeNormalizesSeparator() {
        Object result = registry.get("iso-to-ch-datetime").apply("2026-06-18T00:00:14.794");

        assertThat(result).isEqualTo("2026-06-18 00:00:14.794");
    }

    @Test
    void isoToChDateTimePassesBlankAndNullThrough() {
        // The configured default (not the transform) supplies the fallback for blank/missing input.
        assertThat(registry.get("iso-to-ch-datetime").apply("")).isEqualTo("");
        assertThat(registry.get("iso-to-ch-datetime").apply(null)).isNull();
    }

    @Test
    void stringArrayNormalizesListsAndEmptyInput() {
        assertThat(registry.get("string-array").apply(List.of("a", "b"))).isEqualTo(List.of("a", "b"));
        assertThat((List<?>) registry.get("string-array").apply(List.of())).isEmpty();
        assertThat((List<?>) registry.get("string-array").apply(null)).isEmpty();
    }

    @Test
    void lowercaseJsonKeysLowersTopLevelKeys() {
        ObjectNode headers = MAPPER.createObjectNode()
                .put("X-Claude-Code-Session-Id", "AbC-123")
                .put("Content-Type", "application/json");

        JsonNode result = (JsonNode) registry.get("lowercase-json-keys").apply(headers);

        assertThat(keys(result)).containsExactly("x-claude-code-session-id", "content-type");
    }

    @Test
    void lowercaseJsonKeysLeavesValuesByteForByte() {
        // The point of folding the KEY is to leave the value alone: a header map carries session ids,
        // e-mail addresses and user agents, all of which are case-sensitive. Lowercasing the document
        // instead of the key would corrupt every one of them, silently.
        ObjectNode headers = MAPPER.createObjectNode()
                .put("User-Agent", "Claude-CLI/2.1.170")
                .put("X-User-Email", "Some.Person@Example.COM")
                .put("X-Session-Id", "AbCdEf");

        JsonNode result = (JsonNode) registry.get("lowercase-json-keys").apply(headers);

        assertThat(result.get("user-agent").asString()).isEqualTo("Claude-CLI/2.1.170");
        assertThat(result.get("x-user-email").asString()).isEqualTo("Some.Person@Example.COM");
        assertThat(result.get("x-session-id").asString()).isEqualTo("AbCdEf");
    }

    @Test
    void lowercaseJsonKeysLeavesNestedObjectsUntouched() {
        // Scoped to header maps: a nested document is not one, and nothing here knows what its member
        // names mean.
        ObjectNode nested = MAPPER.createObjectNode().put("InnerKey", "InnerValue");
        ObjectNode headers = MAPPER.createObjectNode().put("Outer-Key", "v");
        headers.set("Nested-Object", nested);

        JsonNode result = (JsonNode) registry.get("lowercase-json-keys").apply(headers);

        assertThat(keys(result)).containsExactly("outer-key", "nested-object");
        assertThat(keys(result.get("nested-object"))).containsExactly("InnerKey");
    }

    @Test
    void lowercaseJsonKeysPassesNullAndNonObjectsThrough() {
        // MappingEngine hands a null for an absent source and the raw node for anything else; a transform
        // that assumed an object would turn a producer quirk into an ingest failure.
        assertThat(registry.get("lowercase-json-keys").apply(null)).isNull();
        JsonNode text = MAPPER.getNodeFactory().stringNode("Not-An-Object");
        assertThat(registry.get("lowercase-json-keys").apply(text)).isSameAs(text);
        JsonNode empty = MAPPER.createObjectNode();
        assertThat((JsonNode) registry.get("lowercase-json-keys").apply(empty)).isEmpty();
    }

    @Test
    void lowercaseJsonKeysCollapsesKeysDifferingOnlyInCase() {
        // Unspecified which survives, and not worth specifying — a producer sends one spelling of a header
        // per request. Asserted as "one member, one of the two values" so the behaviour is recorded without
        // pinning an order the spec declines to promise.
        ObjectNode headers = MAPPER.createObjectNode().put("X-Id", "first").put("x-id", "second");

        JsonNode result = (JsonNode) registry.get("lowercase-json-keys").apply(headers);

        assertThat(keys(result)).containsExactly("x-id");
        assertThat(result.get("x-id").asString()).isIn("first", "second");
    }

    /** Top-level member names, in document order — the property this transform is about. */
    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }
}
