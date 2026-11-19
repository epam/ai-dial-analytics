package com.epam.aidial.analytics.integration;

import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The committed OpenAPI document's normal form, and the comparison the drift check reports.
 *
 * <p>Normalising makes the same code give the same bytes on every machine. Object keys are sorted, because
 * springdoc's member order follows bean scan order. Array order is kept, because it carries meaning (an enum's
 * first value is what a generated form offers first). The top-level {@code servers} member is dropped, because
 * it names the host and port of whatever served the document. Nothing else is touched, so the file stays
 * semantically equal to what {@code /v3/api-docs} serves. See {@code openspec/specs/openapi-document/spec.md}.
 */
final class OpenApiDocuments {

    /** The command that rewrites the committed file, named in every drift failure. */
    static final String REGENERATE_COMMAND = "./gradlew updateOpenApi";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final DefaultIndenter INDENTER = new DefaultIndenter("  ", "\n");

    private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter(Separators.createDefaultInstance()
            .withObjectNameValueSpacing(Separators.Spacing.AFTER)
            .withObjectEmptySeparator("")
            .withArrayEmptySeparator(""))
            .withObjectIndenter(INDENTER)
            .withArrayIndenter(INDENTER);

    private OpenApiDocuments() {
    }

    static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    /** The normal form of a served document: sorted keys, array order kept, no top-level {@code servers}. */
    static JsonNode normalise(JsonNode document) {
        final JsonNode sorted = sorted(document);
        if (sorted instanceof ObjectNode object) {
            object.remove("servers");
        }
        return sorted;
    }

    /** The committed bytes of a normalised document: two-space pretty JSON ending in one newline. */
    static String render(JsonNode normalised) {
        return MAPPER.writer().with(PRINTER).writeValueAsString(normalised) + "\n";
    }

    /**
     * The JSON pointer of the first place two documents differ, walking object members in sorted key order and
     * array elements in order; empty when they are equal.
     */
    static Optional<String> firstDifference(JsonNode expected, JsonNode actual) {
        return firstDifference(expected, actual, "");
    }

    private static Optional<String> firstDifference(JsonNode expected, JsonNode actual, String pointer) {
        if (expected.isObject() && actual.isObject()) {
            final TreeMap<String, Boolean> names = new TreeMap<>();
            expected.propertyNames().forEach(name -> names.put(name, true));
            actual.propertyNames().forEach(name -> names.put(name, true));
            for (final String name : names.keySet()) {
                final String child = pointer + "/" + escape(name);
                if (!expected.has(name) || !actual.has(name)) {
                    return Optional.of(child);
                }
                final Optional<String> difference = firstDifference(expected.get(name), actual.get(name), child);
                if (difference.isPresent()) {
                    return difference;
                }
            }
            return Optional.empty();
        }
        if (expected.isArray() && actual.isArray()) {
            final int common = Math.min(expected.size(), actual.size());
            for (int i = 0; i < common; i++) {
                final Optional<String> difference = firstDifference(expected.get(i), actual.get(i), pointer + "/" + i);
                if (difference.isPresent()) {
                    return difference;
                }
            }
            return expected.size() == actual.size() ? Optional.empty() : Optional.of(pointer + "/" + common);
        }
        return expected.equals(actual) ? Optional.empty() : Optional.of(pointer.isEmpty() ? "/" : pointer);
    }

    /** RFC 6901 escaping, so a path key such as {@code /v1/queries} reads {@code ~1v1~1queries}. */
    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            final TreeMap<String, JsonNode> members = new TreeMap<>();
            for (final Map.Entry<String, JsonNode> member : node.properties()) {
                members.put(member.getKey(), sorted(member.getValue()));
            }
            final ObjectNode copy = JsonNodeFactory.instance.objectNode();
            members.forEach(copy::set);
            return copy;
        }
        if (node.isArray()) {
            final ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            for (final JsonNode element : node) {
                copy.add(sorted(element));
            }
            return copy;
        }
        return node;
    }
}
