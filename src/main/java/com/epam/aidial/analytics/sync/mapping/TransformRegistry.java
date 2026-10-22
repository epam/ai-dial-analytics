package com.epam.aidial.analytics.sync.mapping;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The closed, name-keyed set of transforms a mapping may reference. Transforms are added in code, by
 * name — never user-scriptable. A column referencing a name absent from this registry is a fatal
 * configuration error at load time.
 *
 * <p>The registry holds:
 * <ul>
 *   <li>{@code iso-to-ch-datetime} — render an ISO-like timestamp ({@code 2026-06-18T00:00:14.794})
 *       as a ClickHouse {@code DateTime64} literal ({@code 2026-06-18 00:00:14.794}).</li>
 *   <li>{@code string-array} — coerce a JSON array (or already-coerced list) to a {@code List<String>}.</li>
 *   <li>{@code lowercase-json-keys} — lower-case an object's top-level member names.</li>
 * </ul>
 *
 * <p>The first two were ported from the original hard-coded mapper; the third is the first transform to
 * operate on an {@code object} column, which is why it works on a {@link JsonNode} rather than on a
 * {@code String} or a {@code List}.
 */
@Component
public class TransformRegistry {

    private final Map<String, Transform> transforms = new LinkedHashMap<>();

    public TransformRegistry() {
        transforms.put("iso-to-ch-datetime", TransformRegistry::isoToChDateTime);
        transforms.put("string-array", TransformRegistry::stringArray);
        transforms.put("lowercase-json-keys", TransformRegistry::lowercaseJsonKeys);
    }

    /**
     * Resolves a transform by name.
     *
     * @param name the transform name from the mapping
     * @return the transform, or {@code null} if no transform is registered under that name
     */
    public Transform get(String name) {
        return transforms.get(name);
    }

    /**
     * Whether a transform is registered under the given name.
     *
     * @param name the transform name from the mapping
     * @return {@code true} if the name is known
     */
    public boolean contains(String name) {
        return transforms.containsKey(name);
    }

    /** The known transform names (for diagnostics). */
    public List<String> names() {
        return List.copyOf(transforms.keySet());
    }

    private static Object isoToChDateTime(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString().replace('T', ' ');
    }

    /**
     * Lower-cases an object's <b>top-level member names</b>, leaving every value byte-for-byte and every
     * nested object untouched.
     *
     * <p>This exists for header maps: HTTP header names are case-insensitive and producers disagree on how
     * to spell them, so the same header arrives as {@code X-Claude-Code-Session-Id} from one client and
     * {@code x-claude-code-session-id} from the next, and a reader has to guess. Folding the key at ingest
     * makes rows written from here on carry one spelling.
     *
     * <p><b>Keys only, never values.</b> A header map holds session ids, e-mail addresses and user agents,
     * all of which are case-sensitive; the whole point of folding the name is to leave the value alone.
     * Nested objects are left as they are for the same reason it is scoped to headers — a nested document is
     * not a header map, and nothing here knows what its member names mean.
     *
     * <p>Where two source members differ only in case, the later one wins. Which that is, is unspecified and
     * not worth specifying: a producer sends one spelling of a header per request, so the collision does not
     * arise in the data this is for.
     *
     * <p>Applies at ingest, so it says nothing about rows already stored — {@code request_tags} carries mixed
     * key case across the cutover, and a reader spanning both sides uses the case-insensitive extraction
     * function rather than assuming a spelling.
     *
     * <p><b>The {@link ObjectNode} here must be Jackson 3's ({@code tools.jackson}), which is what
     * {@code MappingEngine} works in.</b> Both Jackson generations are on the classpath — the web DTOs use
     * Jackson 2 annotations — and the two {@code ObjectNode} types share no hierarchy, so an import from the
     * wrong one compiles, passes a unit test written against the same wrong one, and then silently returns
     * every document unchanged at runtime because the {@code instanceof} never matches. {@code MappingEngineTest}
     * asserts the folding through the real engine for that reason — the registry alone cannot see which
     * generation it was handed.
     */
    private static Object lowercaseJsonKeys(Object value) {
        if (!(value instanceof ObjectNode object)) {
            return value;
        }
        ObjectNode lowered = JsonNodeFactory.instance.objectNode();
        object.properties().forEach(entry ->
                lowered.set(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
        return lowered;
    }

    private static Object stringArray(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object element : list) {
                result.add(element == null ? "" : element.toString());
            }
            return result;
        }
        return value == null ? List.<String>of() : List.of(value.toString());
    }
}
