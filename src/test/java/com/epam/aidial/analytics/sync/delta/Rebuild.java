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

/**
 * The rebuild the spec promises, for tests: a row's history is its parent path's history (read from any stored row
 * carrying that path) followed by the messages of its own {@code request_body}; a row with no parent supplies its whole
 * history from its body. The row's body then takes back its full message array, the system keys and tools of the root
 * its chain ends at (every row on a chain has the same ones; the system values are the first history elements), and its
 * own cache markers. Production code exposes no rebuild yet, so this is the reference the stored rows are checked
 * against.
 */
public final class Rebuild {

    public static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private Rebuild() {
    }

    /** The full body of {@code row}, rebuilt from {@code stored} (every payload row that exists). */
    public static JsonNode body(Map<String, Object> row, Collection<Map<String, Object>> stored) {
        String text = (String) row.get("request_body");
        if (!hasParent(row) && row.get("request_cache_control") == null) {
            return text == null ? null : MAPPER.readTree(text);
        }
        List<JsonNode> history = history(row, stored, 0);
        restoreMarkers(history, (String) row.get("request_cache_control"));
        ObjectNode body = (ObjectNode) MAPPER.readTree(text);
        String arrayKey = arrayKey(body);
        // A row with a parent left out its system keys and tools; its chain's root stores the same ones.
        ObjectNode keys = hasParent(row) ? (ObjectNode) MAPPER.readTree((String) root(row, stored, 0).get("request_body")) : body;
        int next = 0;
        for (String key : ChatRequest.SYSTEM_KEYS) {
            if (keys.has(key)) {
                body.set(key, history.get(next++));
            }
        }
        ArrayNode array = body.putArray(arrayKey);
        for (int i = next; i < history.size(); i++) {
            array.add(history.get(i));
        }
        if (hasParent(row) && keys.has(ChatRequest.TOOLS)) {
            body.set(ChatRequest.TOOLS, keys.get(ChatRequest.TOOLS));
        }
        return body;
    }

    /** The canonical history of {@code row}, following {@code request_parent_hash} through whichever row carries each path. */
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
        history.addAll(history(parentRow(row, stored), stored, hops + 1));
        ObjectNode body = (ObjectNode) MAPPER.readTree((String) row.get("request_body"));
        body.get(arrayKey(body)).forEach(history::add);
        return history;
    }

    /** The root the row's {@code request_parent_hash} chain ends at: a row stored whole, whose body is the whole request. */
    private static Map<String, Object> root(Map<String, Object> row, Collection<Map<String, Object>> stored, int hops) {
        if (hops > 100_000) {
            throw new AssertionError("parent walk does not terminate");
        }
        return hasParent(row) ? root(parentRow(row, stored), stored, hops + 1) : row;
    }

    /** The message array's key, chosen as the classifier chooses it: the first key whose value is an array. */
    private static String arrayKey(ObjectNode body) {
        return ChatRequest.MESSAGE_ARRAY_KEYS.stream().filter(key -> body.get(key) instanceof ArrayNode).findFirst().orElseThrow();
    }

    /** Any stored row carrying the row's parent path. */
    private static Map<String, Object> parentRow(Map<String, Object> row, Collection<Map<String, Object>> stored) {
        String parent = (String) row.get("request_parent_hash");
        return stored.stream().filter(r -> parent.equals(r.get("request_hash"))).findAny()
                .orElseThrow(() -> new AssertionError("no stored row carries parent path " + parent));
    }

    private static boolean hasParent(Map<String, Object> row) {
        Object parent = row.get("request_parent_hash");
        return parent != null && !RequestDeltaEncoder.NO_PARENT.toString().equals(parent);
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
