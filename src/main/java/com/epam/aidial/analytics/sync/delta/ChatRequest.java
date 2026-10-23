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
 * A request body split into the parts delta storage keeps apart: its <b>history</b> (the elements that repeat from
 * turn to turn) and its <b>envelope</b> (everything else). Specified in {@code openspec/specs/data-sync/spec.md},
 * "A chat request body is stored as an envelope, a delta and a parent link".
 *
 * <p>A body is a chat request when it parses as a JSON object carrying a message array — an array whose elements are
 * all JSON objects — under the first present of {@link #MESSAGE_ARRAY_KEYS}, and does not already carry a member named
 * {@link #HISTORY_KEY}. Requiring object elements keeps an embeddings request out: its {@code input} array holds
 * strings or token arrays, not messages. Its history is the value of each present {@link #SYSTEM_KEYS top-level system key},
 * in that order, then the array's elements. Every {@code cache_control} member inside the history is removed and
 * recorded as a {@link #markers() marker}, because clients move that marker to the newest message on each turn: left
 * in, the same earlier message would canonicalize differently on every turn and no turn would find its parent.
 *
 * <p>Each history element and the envelope are rendered as <b>canonical JSON</b>: object members sorted by name, no
 * insignificant whitespace, numbers as parsed (floats kept exact as decimals), and an unpaired surrogate written as its
 * backslash-u escape. That last rule is load-bearing: a client that cuts a string inside an emoji sends a lone
 * surrogate escape, parsing turns it into a lone {@code char}, and written back raw it would reach ClickHouse as an
 * escape the server refuses, failing the insert on every retry, or as bytes that are not UTF-8. The canonical form is what the prefix
 * hashes cover, so it is only required to be stable within a release: a different form after an upgrade makes the
 * first request of each conversation a chain root, and breaks nothing.
 *
 * <p>The envelope records under {@link #HISTORY_KEY} which keys the history came from, so the body can be reassembled
 * with each part under its original key.
 */
public final class ChatRequest {

    /** The keys a message array may sit under, in the order they are tried. */
    static final List<String> MESSAGE_ARRAY_KEYS = List.of("messages", "input", "contents");

    /** The top-level keys whose values open the history, in this order, when present. */
    static final List<String> SYSTEM_KEYS = List.of("system", "instructions", "system_instruction", "systemInstruction");

    /** The envelope member recording where the history came from. */
    static final String HISTORY_KEY = "__adas_history";

    private static final String CACHE_CONTROL = "cache_control";

    private final List<String> canonicalHistory;
    private final String canonicalEnvelope;
    private final List<Marker> markers;

    private ChatRequest(List<String> canonicalHistory, String canonicalEnvelope, List<Marker> markers) {
        this.canonicalHistory = canonicalHistory;
        this.canonicalEnvelope = canonicalEnvelope;
        this.markers = markers;
    }

    /**
     * Splits a request body, or returns {@code null} when it is not a chat request — not JSON (a body truncated at
     * 4 MiB included), not an object, carrying no message array, or already carrying {@link #HISTORY_KEY} — and is
     * therefore stored whole.
     *
     * @param mapper a mapper that reads floats as {@code BigDecimal}, so canonical numbers are exact
     * @param body   the request body as ingest is about to store it
     * @return the split request, envelope rendered, or {@code null}
     */
    static ChatRequest parse(JsonMapper mapper, String body) {
        return parse(mapper, body, true);
    }

    /**
     * As {@link #parse(JsonMapper, String)}, rendering the envelope only when asked: hashing a request needs its
     * history alone. The envelope is rendered to its canonical string here, never kept as a tree, so a split request
     * holds text only.
     *
     * @param renderEnvelope whether {@link #canonicalEnvelope()} is wanted; when not, it returns {@code null}
     */
    static ChatRequest parse(JsonMapper mapper, String body, boolean renderEnvelope) {
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
        if (arrayKey == null || object.has(HISTORY_KEY)) {
            return null;
        }
        for (JsonNode element : (ArrayNode) object.get(arrayKey)) {
            if (!element.isObject()) {
                return null;
            }
        }
        // A shallow copy: the history keys are taken off it, and no child node is ever modified.
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.setAll(object);
        List<JsonNode> history = new ArrayList<>();
        ArrayNode systemKeys = JsonNodeFactory.instance.arrayNode();
        for (String key : SYSTEM_KEYS) {
            if (envelope.has(key)) {
                history.add(envelope.remove(key));
                systemKeys.add(key);
            }
        }
        for (JsonNode element : (ArrayNode) envelope.remove(arrayKey)) {
            history.add(element);
        }
        ObjectNode source = JsonNodeFactory.instance.objectNode();
        source.put("array", arrayKey);
        source.set("system", systemKeys);
        envelope.set(HISTORY_KEY, source);

        List<Marker> markers = new ArrayList<>();
        List<String> canonical = new ArrayList<>(history.size());
        for (int i = 0; i < history.size(); i++) {
            JsonNode element = stripMarkers(history.get(i), i, "", markers);
            canonical.add(canonical(element));
        }
        return new ChatRequest(List.copyOf(canonical), renderEnvelope ? canonical(envelope) : null, List.copyOf(markers));
    }

    /** The history elements, each as canonical JSON with its {@code cache_control} members removed. */
    List<String> canonicalHistory() {
        return canonicalHistory;
    }

    /**
     * The envelope as canonical JSON: the body without its history keys, plus {@link #HISTORY_KEY}; {@code null} when
     * the request was parsed without it.
     */
    String canonicalEnvelope() {
        return canonicalEnvelope;
    }

    /** The removed {@code cache_control} members, in document order. */
    List<Marker> markers() {
        return markers;
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
