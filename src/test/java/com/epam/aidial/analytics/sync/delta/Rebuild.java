package com.epam.aidial.analytics.sync.delta;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The rebuild the spec promises, for tests: a row's history is its parent path's history (read from any stored row
 * carrying that path) followed by the messages of its own {@code request_body}; a row with no parent supplies its whole
 * history from its body. The row's body then takes back its full message array, its inherited system keys and tools,
 * and its own cache markers. Production code exposes no rebuild yet, so this is the reference the stored rows are
 * checked against.
 */
public final class Rebuild {

    public static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private Rebuild() {
    }

    /** The full body of {@code row}, rebuilt from {@code stored} (every payload row that exists). */
    public static JsonNode body(Map<String, Object> row, Collection<Map<String, Object>> stored) {
        String text = (String) row.get("request_body");
        if (!hasParent(row) && row.get("request_cache_markers") == null) {
            return text == null ? null : MAPPER.readTree(text);
        }
        List<JsonNode> history = history(row, stored, 0);
        restoreMarkers(history, (String) row.get("request_cache_markers"));
        ObjectNode body = (ObjectNode) MAPPER.readTree(text);
        String arrayKey = arrayKey(body);
        List<String> inherited = inherited(row);
        int next = 0;
        if (hasParent(row)) {
            for (String key : inherited) {
                if (ChatRequest.SYSTEM_KEYS.contains(key)) {
                    body.set(key, history.get(next++));
                }
            }
        } else {
            for (String key : ChatRequest.SYSTEM_KEYS) {
                if (body.has(key)) {
                    body.set(key, history.get(next++));
                }
            }
        }
        ArrayNode array = body.putArray(arrayKey);
        for (int i = next; i < history.size(); i++) {
            array.add(history.get(i));
        }
        if (inherited.contains(ChatRequest.TOOLS)) {
            body.set(ChatRequest.TOOLS, tools(row, stored));
        }
        return body;
    }

    /** The canonical history of {@code row}, following {@code request_parent} through whichever row carries each path. */
    static List<JsonNode> history(Map<String, Object> row, Collection<Map<String, Object>> stored, int hops) {
        if (hops > 100_000) {
            throw new AssertionError("parent walk does not terminate");
        }
        List<JsonNode> history = new ArrayList<>();
        if (!hasParent(row)) {
            ChatRequest.parse(MAPPER, (String) row.get("request_body")).canonicalHistory()
                    .forEach(element -> history.add(MAPPER.readTree(element)));
            return history;
        }
        String parent = (String) row.get("request_parent");
        Map<String, Object> parentRow = stored.stream().filter(r -> parent.equals(r.get("request_path"))).findAny()
                .orElseThrow(() -> new AssertionError("no stored row carries parent path " + parent));
        history.addAll(history(parentRow, stored, hops + 1));
        ObjectNode body = (ObjectNode) MAPPER.readTree((String) row.get("request_body"));
        body.get(arrayKey(body)).forEach(history::add);
        return history;
    }

    /**
     * The inherited tools: those of a row in the same scope and day whose tools hash matches and whose body carries
     * them.
     */
    private static JsonNode tools(Map<String, Object> row, Collection<Map<String, Object>> stored) {
        Object hash = row.get("request_tools_hash");
        return stored.stream()
                .filter(r -> Objects.equals(hash, r.get("request_tools_hash")))
                .filter(r -> Objects.equals(row.get("project_id"), r.get("project_id")) && Objects.equals(row.get("user_hash"), r.get("user_hash")))
                .filter(r -> day(row).equals(day(r)))
                .filter(r -> r.get("request_body") instanceof String text && MAPPER.readTree(text).has(ChatRequest.TOOLS))
                .map(r -> MAPPER.readTree((String) r.get("request_body")).get(ChatRequest.TOOLS))
                .findAny()
                .orElseThrow(() -> new AssertionError("no stored row carries tools hash " + hash));
    }

    /** The message array's key, chosen as the classifier chooses it: the first key whose value is an array. */
    private static String arrayKey(ObjectNode body) {
        return ChatRequest.MESSAGE_ARRAY_KEYS.stream().filter(key -> body.get(key) instanceof ArrayNode).findFirst().orElseThrow();
    }

    private static String day(Map<String, Object> row) {
        return ((String) row.get("request_time")).substring(0, 10);
    }

    private static boolean hasParent(Map<String, Object> row) {
        Object parent = row.get("request_parent");
        return parent != null && !RequestDeltaEncoder.NO_PARENT.toString().equals(parent);
    }

    @SuppressWarnings("unchecked")
    private static List<String> inherited(Map<String, Object> row) {
        Object keys = row.get("request_inherited");
        return keys == null ? List.of() : (List<String>) keys;
    }

    private static void restoreMarkers(List<JsonNode> history, String markers) {
        if (markers == null) {
            return;
        }
        for (JsonNode marker : MAPPER.readTree(markers)) {
            int element = marker.get("i").asInt();
            String at = marker.get("at").asString();
            JsonNode target = at.isEmpty() ? history.get(element) : history.get(element).at(at);
            ((ObjectNode) target).set("cache_control", marker.get("v"));
        }
    }

    /** Canonical text of a node, so two bodies compare as JSON values rather than as bytes. */
    public static String canonical(JsonNode node) {
        return ChatRequest.canonical(node);
    }

    /** Canonical text of a body as sent. */
    public static String canonical(String body) {
        return ChatRequest.canonical(MAPPER.readTree(body));
    }
}
