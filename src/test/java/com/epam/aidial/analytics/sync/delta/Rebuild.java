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
 * carrying that path) followed by its own delta; the envelope then takes back the history keys and the row's own
 * cache markers. A row stored whole supplies its path's history from its body. Production code exposes no rebuild yet,
 * so this is the reference the stored rows are checked against.
 */
public final class Rebuild {

    public static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private Rebuild() {
    }

    /** The full body of {@code row}, rebuilt from {@code stored} (every payload row that exists). */
    public static JsonNode body(Map<String, Object> row, Collection<Map<String, Object>> stored) {
        if (row.get("request_body") != null) {
            return MAPPER.readTree((String) row.get("request_body"));
        }
        List<JsonNode> history = history(row, stored, 0);
        restoreMarkers(history, (String) row.get("request_cache_markers"));
        ObjectNode body = (ObjectNode) MAPPER.readTree((String) row.get("request_envelope"));
        JsonNode source = body.remove(ChatRequest.HISTORY_KEY);
        int next = 0;
        for (JsonNode key : source.get("system")) {
            body.set(key.asString(), history.get(next++));
        }
        ArrayNode array = body.putArray(source.get("array").asString());
        for (int i = next; i < history.size(); i++) {
            array.add(history.get(i));
        }
        return body;
    }

    /** The canonical history of {@code row}, following {@code request_parent} through whichever row carries each path. */
    static List<JsonNode> history(Map<String, Object> row, Collection<Map<String, Object>> stored, int hops) {
        if (hops > 100_000) {
            throw new AssertionError("parent walk does not terminate");
        }
        if (row.get("request_body") instanceof String whole) {
            List<JsonNode> history = new ArrayList<>();
            ChatRequest.parse(MAPPER, whole).canonicalHistory().forEach(element -> history.add(MAPPER.readTree(element)));
            return history;
        }
        List<JsonNode> history = new ArrayList<>();
        String parent = (String) row.get("request_parent");
        if (parent != null && !RequestDeltaEncoder.NO_PARENT.toString().equals(parent)) {
            Map<String, Object> parentRow = stored.stream().filter(r -> parent.equals(r.get("request_path"))).findAny()
                    .orElseThrow(() -> new AssertionError("no stored row carries parent path " + parent));
            history.addAll(history(parentRow, stored, hops + 1));
        }
        for (JsonNode element : MAPPER.readTree((String) row.get("request_delta"))) {
            history.add(element);
        }
        return history;
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
