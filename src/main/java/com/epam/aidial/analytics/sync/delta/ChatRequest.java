package com.epam.aidial.analytics.sync.delta;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A request body as delta storage sees it: its <b>history</b> (the elements that repeat from turn to turn), its
 * {@code tools} (which usually repeat too) and the rest. Specified in {@code openspec/specs/data-sync/spec.md}, "A chat
 * request body is stored as its new history and a parent link".
 *
 * <p>A body is a chat request when it parses as a JSON object carrying a message array — an array whose elements are
 * all JSON objects — under the first present of {@link #MESSAGE_ARRAY_KEYS}. Requiring object elements keeps an
 * embeddings request out: its {@code input} array holds strings or token arrays, not messages. Its history is the value
 * of each present {@link #SYSTEM_KEYS top-level system key}, in that order, then the array's elements. Every
 * {@code cache_control} member inside the history is removed and recorded as a {@link #markers() marker}, because
 * clients move that marker to the newest message on each turn: left in, the same earlier message would canonicalize
 * differently on every turn and no turn would find its parent.
 *
 * <p>Each history element and the stored body are rendered as <b>canonical JSON</b>: object members sorted by name, no
 * insignificant whitespace, numbers as parsed (floats kept exact as decimals), and an unpaired surrogate written as its
 * backslash-u escape. That last rule is load-bearing: a client that cuts a string inside an emoji sends a lone
 * surrogate escape, parsing turns it into a lone {@code char}, and written back raw it would reach ClickHouse as an
 * escape the server refuses, failing the insert on every retry, or as bytes that are not UTF-8. The canonical form is
 * what the prefix hashes cover, so it is only required to be stable within a release: a different form after an upgrade
 * makes the first request of each conversation a chain root, and breaks nothing.
 */
public final class ChatRequest {

    /** The keys a message array may sit under, in the order they are tried. */
    static final List<String> MESSAGE_ARRAY_KEYS = List.of("messages", "input", "contents");

    /** The top-level keys whose values open the history, in this order, when present. */
    static final List<String> SYSTEM_KEYS = List.of("system", "instructions", "system_instruction", "systemInstruction");

    /** The top-level key a request inherits from its parent: the prefix hashes cover it, so a parent's is always equal. */
    static final String TOOLS = "tools";

    private static final String CACHE_CONTROL = "cache_control";

    private final List<String> canonicalHistory;
    private final List<Marker> markers;
    private final int systemCount;
    private final String tools;
    /** The body without its history keys, kept only when the request was parsed for storing. */
    private final ObjectNode rest;
    private final String arrayKey;
    private final List<String> systemKeys;

    private ChatRequest(List<String> canonicalHistory, List<Marker> markers, List<String> systemKeys, String tools,
                        ObjectNode rest, String arrayKey) {
        this.canonicalHistory = canonicalHistory;
        this.markers = markers;
        this.systemKeys = systemKeys;
        this.systemCount = systemKeys.size();
        this.tools = tools;
        this.rest = rest;
        this.arrayKey = arrayKey;
    }

    /**
     * Splits a request body, or returns {@code null} when it is not a chat request — not JSON (a body truncated at
     * 4 MiB included), not an object, or carrying no message array — and is therefore stored whole.
     *
     * @param mapper a mapper that reads floats as {@code BigDecimal}, so canonical numbers are exact
     * @param body   the request body as ingest is about to store it
     * @return the split request, ready for {@link #stored}, or {@code null}
     */
    static ChatRequest parse(JsonMapper mapper, String body) {
        return parse(mapper, body, true);
    }

    /**
     * As {@link #parse(JsonMapper, String)}, keeping what {@link #stored} needs only when asked: hashing a request needs
     * its history and its tools alone.
     *
     * @param forStoring whether {@link #stored} will be called
     */
    static ChatRequest parse(JsonMapper mapper, String body, boolean forStoring) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException ex) {
            return null;
        }
        if (!(root instanceof ObjectNode object)) {
            return null;
        }
        String arrayKey = null;
        for (String key : MESSAGE_ARRAY_KEYS) {
            if (object.get(key) instanceof ArrayNode) {
                arrayKey = key;
                break;
            }
        }
        if (arrayKey == null) {
            return null;
        }
        for (JsonNode element : (ArrayNode) object.get(arrayKey)) {
            if (!element.isObject()) {
                return null;
            }
        }
        // A shallow copy: the history keys are taken off it, and no child node is ever modified.
        ObjectNode rest = JsonNodeFactory.instance.objectNode();
        rest.setAll(object);
        List<JsonNode> history = new ArrayList<>();
        List<String> systemKeys = new ArrayList<>();
        for (String key : SYSTEM_KEYS) {
            if (rest.has(key)) {
                history.add(rest.remove(key));
                systemKeys.add(key);
            }
        }
        for (JsonNode element : (ArrayNode) rest.remove(arrayKey)) {
            history.add(element);
        }

        List<Marker> markers = new ArrayList<>();
        List<String> canonical = new ArrayList<>(history.size());
        for (int i = 0; i < history.size(); i++) {
            JsonNode element = stripMarkers(history.get(i), i, "", markers);
            canonical.add(canonical(element));
        }
        String tools = rest.has(TOOLS) ? canonical(rest.get(TOOLS)) : null;
        return new ChatRequest(List.copyOf(canonical), List.copyOf(markers), List.copyOf(systemKeys), tools,
                forStoring ? rest : null, forStoring ? arrayKey : null);
    }

    /** The history elements, each as canonical JSON with its {@code cache_control} members removed. */
    List<String> canonicalHistory() {
        return canonicalHistory;
    }

    /** How many history elements come from top-level system keys; they open the history. */
    int systemCount() {
        return systemCount;
    }

    /** The request's {@code tools} as canonical JSON, or {@code null} when it carries none. */
    String tools() {
        return tools;
    }

    /** The removed {@code cache_control} members, in document order. */
    List<Marker> markers() {
        return markers;
    }

    /**
     * The body to store, as canonical JSON: the request with its history cut to the elements from {@code from} on and
     * its {@code cache_control} markers removed, and, when it has a parent ({@code from > 0}), without the system keys
     * and {@code tools} it inherits. Only for a request parsed for storing.
     *
     * @param from the parent's depth: 0 for a root, else at least {@link #systemCount()}
     */
    String stored(int from) {
        if (from > 0 && from < systemCount) {
            throw new IllegalArgumentException("a parent must cover the whole system part of the history");
        }
        // Member name -> canonical value text. The history elements are canonical text already, so they are spliced
        // in as text rather than parsed again.
        Map<String, String> members = new TreeMap<>();
        rest.properties().forEach(entry -> members.put(entry.getKey(), canonical(entry.getValue())));
        if (from > 0) {
            members.remove(TOOLS);
        } else {
            for (int i = 0; i < systemCount; i++) {
                members.put(systemKeys.get(i), canonicalHistory.get(i));
            }
        }
        int first = Math.max(from, systemCount);
        members.put(arrayKey, "[" + String.join(",", canonicalHistory.subList(first, canonicalHistory.size())) + "]");
        StringBuilder out = new StringBuilder("{");
        members.forEach((name, value) -> out.append(out.length() > 1 ? "," : "")
                .append(canonical(JsonNodeFactory.instance.stringNode(name))).append(':').append(value));
        return out.append('}').toString();
    }

    /**
     * The top-level keys {@link #stored} leaves out for a rebuild to put back: none for a root ({@code from == 0}),
     * else the system keys and {@code tools} the request carries.
     */
    List<String> inherited(int from) {
        List<String> keys = new ArrayList<>(from > 0 ? systemKeys : List.of());
        if (from > 0 && tools != null) {
            keys.add(TOOLS);
        }
        return keys;
    }

    /**
     * Renders a node as canonical JSON: members sorted by name at every depth, no whitespace. Package-private for
     * tests and for the rebuild that reassembles a body.
     */
    static String canonical(JsonNode node) {
        return escapeLoneSurrogates(sorted(node).toString());
    }

    /**
     * Replaces every unpaired surrogate in JSON text with its backslash-u escape. Such a {@code char} can only
     * stand inside a JSON string, where the escape means the same value, so the text stays equal JSON.
     */
    static String escapeLoneSurrogates(String json) {
        StringBuilder out = null;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            boolean paired = Character.isHighSurrogate(c) && i + 1 < json.length() && Character.isLowSurrogate(json.charAt(i + 1));
            if (paired) {
                if (out != null) {
                    out.append(c).append(json.charAt(i + 1));
                }
                i++;
            } else if (Character.isSurrogate(c)) {
                if (out == null) {
                    out = new StringBuilder(json.length() + 16).append(json, 0, i);
                }
                out.append(String.format("\\u%04x", (int) c));
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? json : out.toString();
    }

    private static JsonNode sorted(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Map<String, JsonNode> members = new TreeMap<>();
            object.properties().forEach(entry -> members.put(entry.getKey(), sorted(entry.getValue())));
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            members.forEach(copy::set);
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode(array.size());
            array.forEach(element -> copy.add(sorted(element)));
            return copy;
        }
        return node;
    }

    /** Returns {@code node} without its {@code cache_control} members, recording each one removed. */
    private static JsonNode stripMarkers(JsonNode node, int element, String pointer, List<Marker> markers) {
        if (node instanceof ObjectNode object) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            object.properties().forEach(entry -> {
                if (CACHE_CONTROL.equals(entry.getKey())) {
                    markers.add(new Marker(element, pointer, entry.getValue()));
                } else {
                    copy.set(entry.getKey(), stripMarkers(entry.getValue(), element, pointer + "/" + escape(entry.getKey()), markers));
                }
            });
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode(array.size());
            for (int i = 0; i < array.size(); i++) {
                copy.add(stripMarkers(array.get(i), element, pointer + "/" + i, markers));
            }
            return copy;
        }
        return node;
    }

    /** Escapes a member name as a JSON Pointer (RFC 6901) reference token. */
    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }

    /**
     * A {@code cache_control} member removed from the history.
     *
     * @param element the index of the history element it was in
     * @param at      the JSON Pointer, within that element, of the object that carried it ({@code ""} for the element itself)
     * @param value   the member's value, verbatim
     */
    record Marker(int element, String at, JsonNode value) {

        /** The marker as stored in {@code request_cache_markers}: {@code {"i": element, "at": pointer, "v": value}}. */
        ObjectNode toJson() {
            ObjectNode json = JsonNodeFactory.instance.objectNode();
            json.put("i", element);
            json.put("at", at);
            json.set("v", value);
            return json;
        }
    }
}
