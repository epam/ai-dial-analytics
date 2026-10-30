package com.epam.aidial.analytics.sync.delta;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The rebuild equivalence of {@code openspec/specs/data-sync/spec.md} ("A chat request body is stored as its new
 * history and a parent link"), over rows already in memory: a row's history is its parent path's history followed by
 * the message elements of its own {@code request_body}, where a path's history is that of any stored row carrying it,
 * and a row with no parent supplies its whole history from its body. The row's body then takes back its full message
 * array, the system keys and {@code tools} of the root its chain ends at (every row on a chain has the same ones; the
 * system values are the first history elements), and its own cache markers.
 *
 * <p>No I/O: the reader that found the ancestors ({@code ComputedFieldCompleter}) owns how they were read. The walk is
 * iterative and bounded by the number of ancestors it was handed, so a cycle — impossible for rows ingest wrote, since
 * a parent's history is strictly shorter than its child's — ends as {@link Reason#INVALID} rather than as a loop.
 *
 * <p>A rebuilt body is written with {@link ChatRequest#canonical}, the writer the stored body uses, so it is always
 * valid JSON text with sorted keys and exact decimals. A body returned {@link Outcome#STORED} or
 * {@link Outcome#PARTIAL} is the stored text itself, never re-serialized.
 */
public final class RequestBodyRebuild {

    /** What a rebuilt value holds. */
    public enum Outcome {
        /** The stored body, which is already the whole request. */
        STORED,
        /** The body rebuilt from its chain. */
        REBUILT,
        /** The stored body, because the rebuild could not be completed. */
        PARTIAL
    }

    /** Why a value is {@link Outcome#PARTIAL}. */
    public enum Reason {
        /** A path on the chain is carried by no row that was found. */
        MISSING,
        /** A bound on the read was reached before the chain was complete. */
        LIMIT,
        /** A rebuild statement failed. */
        FAILED,
        /** A body on the chain does not have the shape the rebuild expects. */
        INVALID
    }

    /**
     * One stored row on a chain.
     *
     * @param eventId the row's {@code event_id}
     * @param path    its {@code request_hash}
     * @param parent  its {@code request_parent_hash}, {@link RequestDeltaEncoder#NO_PARENT} for a root
     * @param body    its stored {@code request_body}
     */
    public record Ancestor(String eventId, UUID path, UUID parent, String body) {}

    /**
     * The result of one rebuild.
     *
     * @param outcome what {@code body} holds
     * @param reason  why it is partial; {@code null} unless {@code outcome} is {@link Outcome#PARTIAL}
     * @param body    the body text
     */
    public record Result(Outcome outcome, Reason reason, String body) {

        static Result stored(String body) {
            return new Result(Outcome.STORED, null, body);
        }

        static Result partial(Reason reason, String body) {
            return new Result(Outcome.PARTIAL, reason, body);
        }
    }

    /** The {@code request_parent_hash} of a root: all-zero. */
    public static final UUID NO_PARENT = RequestDeltaEncoder.NO_PARENT;

    /** Exact floats, as the encoder reads them, so a rebuilt number is the one the request carried. */
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private static final String CACHE_CONTROL = "cache_control";

    private RequestBodyRebuild() {
    }

    /**
     * Whether a row needs no rebuild at all: it has no parent and carries no cache markers, so its stored body is the
     * whole request.
     */
    public static boolean storedWhole(UUID parent, boolean hasMarkers) {
        return !hasParent(parent) && !hasMarkers;
    }

    /**
     * Rebuilds one row.
     *
     * @param body    the row's stored {@code request_body}; {@code null} is returned as {@code null}, stored
     * @param parent  its {@code request_parent_hash}
     * @param markers its {@code request_cache_control}, or {@code null}
     * @param byPath  every ancestor the reader found, by the path it carries; any row carrying a path will do
     * @return the rebuilt body, or the stored one marked {@link Outcome#STORED} or {@link Outcome#PARTIAL}
     */
    public static Result rebuild(String body, UUID parent, String markers, Map<UUID, Ancestor> byPath) {
        if (body == null || storedWhole(parent, markers != null)) {
            return Result.stored(body);
        }
        final List<Ancestor> chain = new ArrayList<>();
        UUID next = parent;
        while (hasParent(next)) {
            if (chain.size() > byPath.size()) {
                return Result.partial(Reason.INVALID, body);
            }
            final Ancestor ancestor = byPath.get(next);
            if (ancestor == null) {
                return Result.partial(Reason.MISSING, body);
            }
            chain.add(ancestor);
            next = ancestor.parent();
        }
        try {
            return new Result(Outcome.REBUILT, null, assemble(body, markers, chain));
        } catch (RuntimeException ex) {
            return Result.partial(Reason.INVALID, body);
        }
    }

    /**
     * The rebuilt body text.
     *
     * @param chain the ancestors from the parent up to the root, nearest first; empty for a root with markers
     */
    private static String assemble(String text, String markers, List<Ancestor> chain) {
        final ObjectNode body = (ObjectNode) MAPPER.readTree(text);
        final String arrayKey = arrayKey(body);
        // The root supplies the history's start; a row with no parent is its own root.
        final String rootText = chain.isEmpty() ? text : chain.getLast().body();
        final ChatRequest root = ChatRequest.parse(MAPPER, rootText, false);
        if (root == null) {
            throw new IllegalArgumentException("the chain's root is not a chat request");
        }
        final List<JsonNode> history = new ArrayList<>();
        root.canonicalHistory().forEach(element -> history.add(MAPPER.readTree(element)));
        // Every descendant of the root down to the parent, then the row itself, adds its own message elements.
        for (int i = chain.size() - 2; i >= 0; i--) {
            appendMessages(history, (ObjectNode) MAPPER.readTree(chain.get(i).body()));
        }
        if (!chain.isEmpty()) {
            appendMessages(history, body);
        }
        restoreMarkers(history, markers);

        // A row with a parent left out its system keys and tools; its chain's root stores the same ones.
        final ObjectNode keys = chain.isEmpty() ? body : (ObjectNode) MAPPER.readTree(rootText);
        int element = 0;
        for (String key : ChatRequest.SYSTEM_KEYS) {
            if (keys.has(key)) {
                body.set(key, history.get(element++));
            }
        }
        final ArrayNode array = body.putArray(arrayKey);
        for (int i = element; i < history.size(); i++) {
            array.add(history.get(i));
        }
        if (!chain.isEmpty() && keys.has(ChatRequest.TOOLS)) {
            body.set(ChatRequest.TOOLS, keys.get(ChatRequest.TOOLS));
        }
        return ChatRequest.canonical(body);
    }

    private static void appendMessages(List<JsonNode> history, ObjectNode body) {
        body.get(arrayKey(body)).forEach(history::add);
    }

    /** The message array's key, chosen as the classifier chooses it: the first key whose value is an array. */
    private static String arrayKey(ObjectNode body) {
        for (String key : ChatRequest.MESSAGE_ARRAY_KEYS) {
            if (body.get(key) instanceof ArrayNode) {
                return key;
            }
        }
        throw new IllegalArgumentException("a stored delta body carries no message array");
    }

    private static void restoreMarkers(List<JsonNode> history, String markers) {
        if (markers == null) {
            return;
        }
        for (JsonNode marker : MAPPER.readTree(markers)) {
            final int element = marker.get("i").asInt();
            final String at = marker.get("at").asString();
            if (element < 0 || element >= history.size()) {
                throw new IllegalArgumentException("a cache marker points outside the history");
            }
            final JsonNode target = at.isEmpty() ? history.get(element) : history.get(element).at(at);
            if (!(target instanceof ObjectNode object)) {
                throw new IllegalArgumentException("a cache marker points at no object");
            }
            object.set(CACHE_CONTROL, marker.get("v"));
        }
    }

    private static boolean hasParent(UUID parent) {
        return parent != null && !RequestDeltaEncoder.NO_PARENT.equals(parent);
    }
}
