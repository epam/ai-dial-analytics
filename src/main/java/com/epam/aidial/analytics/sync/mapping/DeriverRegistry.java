package com.epam.aidial.analytics.sync.mapping;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The closed, name-keyed set of derivers a mapping may reference. Like {@link TransformRegistry},
 * derivers are added in code, by name — never user-scriptable. A derived column referencing a name
 * absent from this registry is a fatal configuration error at load time.
 *
 * <p>A deriver computes a column value from the {@link JsonNode}s resolved at the column's
 * {@code sources} paths plus its static {@code args}. The registered set is exactly what the events
 * column structure needs:
 * <ul>
 *   <li>{@code hash-uuid} — deterministic UUIDv5 over the concatenated source texts (e.g. {@code event_id}).</li>
 *   <li>{@code classify-event-kind} — regex-classify a request URI into {@code llm_call}/{@code embedding}/{@code mcp}/{@code route}.</li>
 *   <li>{@code json-extract-string} / {@code json-extract-uint} — re-parse a source value that is itself a JSON
 *       string (or read it directly when already structured) and read {@code args.path} as the scalar's verbatim
 *       text (a boolean reads as {@code true}/{@code false}) / as an unsigned int.</li>
 *   <li>{@code json-extract-rate} — read {@code args.path} as a normalized signed rating (number as-is, {@code true}→1, {@code false}→0, else null).</li>
 *   <li>{@code json-array-length} — length of the array at {@code args.path} within a (possibly string-encoded) JSON source.</li>
 *   <li>{@code byte-length} — UTF-8 byte length of a source string.</li>
 *   <li>{@code success-from-status} — whether an HTTP status is in {@code 200..299}.</li>
 * </ul>
 */
@Component
public class DeriverRegistry {

    /**
     * Fixed namespace for the deterministic {@code hash-uuid} deriver (UUIDv5 name hashing).
     *
     * <p>DO NOT CHANGE — fixed forever. This value seeds every historical {@code event_id}; changing it
     * silently re-keys all existing events, so re-ingested records would no longer collapse with the rows
     * already stored under the old namespace. It is an arbitrary-but-permanent constant.
     */
    private static final UUID NAMESPACE = UUID.fromString("2a8b6f4e-1c3d-4e5f-8a9b-0c1d2e3f4a5b");

    /** Separator joining multiple sources before hashing (matches the events design's {@code ‖}). */
    private static final String HASH_SEPARATOR = "‖";

    private final Map<String, Deriver> derivers = new LinkedHashMap<>();
    private final JsonMapper jsonMapper;

    public DeriverRegistry(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        derivers.put("hash-uuid", DeriverRegistry::hashUuid);
        derivers.put("classify-event-kind", DeriverRegistry::classifyEventKind);
        derivers.put("json-extract-string", this::jsonExtractString);
        derivers.put("json-extract-uint", this::jsonExtractUint);
        derivers.put("json-extract-rate", this::jsonExtractRate);
        derivers.put("json-array-length", this::jsonArrayLength);
        derivers.put("byte-length", DeriverRegistry::byteLength);
        derivers.put("success-from-status", DeriverRegistry::successFromStatus);
    }

    /**
     * Resolves a deriver by name.
     *
     * @param name the deriver name from the mapping
     * @return the deriver, or {@code null} if no deriver is registered under that name
     */
    public Deriver get(String name) {
        return derivers.get(name);
    }

    /**
     * Whether a deriver is registered under the given name.
     *
     * @param name the deriver name from the mapping
     * @return {@code true} if the name is known
     */
    public boolean contains(String name) {
        return derivers.containsKey(name);
    }

    /** The known deriver names (for diagnostics). */
    public List<String> names() {
        return List.copyOf(derivers.keySet());
    }

    // --- derivers -------------------------------------------------------------------------------

    /** Deterministic UUIDv5 over the joined source texts; stable across re-ingests, never null. */
    private static Object hashUuid(List<JsonNode> sources, Map<String, Object> args) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) {
                name.append(HASH_SEPARATOR);
            }
            String text = text(sources.get(i));
            name.append(text == null ? "" : text);
        }
        return uuidV5(NAMESPACE, name.toString()).toString();
    }

    /** Classifies a request URI (first source) into the event kind; {@code ""} when none match. */
    private static Object classifyEventKind(List<JsonNode> sources, Map<String, Object> args) {
        String uri = sources.isEmpty() ? null : text(sources.getFirst());
        if (uri == null) {
            return "";
        }
        String lower = uri.toLowerCase(Locale.ROOT);
        if (lower.contains("/chat/completions")) {
            return "llm_call";
        }
        if (lower.contains("/embeddings")) {
            return "embedding";
        }
        if (lower.contains("/mcp")) {
            return "mcp";
        }
        if (lower.contains("/route/")) {
            return "route";
        }
        return "";
    }

    /**
     * Reads {@code args.path} as a string from the first source that parses and has it; null otherwise.
     *
     * <p><b>It reads any scalar, not only a JSON string.</b> {@code asString("")} yields {@code "true"} /
     * {@code "false"} for a boolean and the literal digits for a number, so the deriver returns the
     * member's <em>submitted form</em> verbatim. That is incidental to most callers ({@code id},
     * {@code method}, {@code params.name}, {@code responseId}, {@code comment} all resolve to strings) but
     * load-bearing for one: {@code rate_analytics.rate_raw} exists precisely to keep a boolean
     * {@code false} distinguishable from a numeric {@code 0}, both of which {@link #jsonExtractRate}
     * normalizes to {@code 0}. Changing the coercion here would silently re-introduce that ambiguity, so
     * the behaviour is pinned by the data-sync spec and by {@code DeriverRegistryTest}.
     *
     * <p>If the resolved node is itself an object or array (not a scalar), {@code asString("")} returns
     * {@code ""} (not the node's JSON text), which the engine's absent/blank fallback then turns into null
     * for a nullable column. A future path needing the node's JSON text would need a different deriver
     * that serializes it.
     */
    private Object jsonExtractString(List<JsonNode> sources, Map<String, Object> args) {
        JsonNode node = extract(sources, path(args));
        return node == null ? null : node.asString("");
    }

    /** Reads {@code args.path} as an unsigned integer (as a long); null when absent (falls back to 0). */
    private Object jsonExtractUint(List<JsonNode> sources, Map<String, Object> args) {
        JsonNode node = extract(sources, path(args));
        return node == null ? null : node.asLong(0);
    }

    /**
     * Reads {@code args.path} as a normalized signed rating: a numeric value is returned as its integer
     * value; boolean {@code true} maps to 1 and {@code false} to 0; an absent, blank, or non-numeric
     * value yields null (so the column's nullability fallback applies). Used for {@code rate_analytics.rate},
     * whose source `rate` field is polymorphic across boolean and integer forms in real DIAL logs.
     */
    private Object jsonExtractRate(List<JsonNode> sources, Map<String, Object> args) {
        JsonNode node = extract(sources, path(args));
        if (node == null) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean(false) ? 1L : 0L;
        }
        if (node.isNumber()) {
            return node.asLong(0);
        }
        String text = node.asString("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Length of the array at {@code args.path} within the first parseable source; 0 otherwise. */
    private Object jsonArrayLength(List<JsonNode> sources, Map<String, Object> args) {
        JsonNode node = extract(sources, path(args));
        return (node != null && node.isArray()) ? (long) node.size() : 0L;
    }

    /** UTF-8 byte length of the (first) source string; 0 when absent or blank. */
    private static Object byteLength(List<JsonNode> sources, Map<String, Object> args) {
        String text = sources.isEmpty() ? null : text(sources.getFirst());
        return text == null ? 0L : (long) text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Whether {@code response.status} (first source) is an HTTP success (200..299). */
    private static Object successFromStatus(List<JsonNode> sources, Map<String, Object> args) {
        JsonNode node = sources.isEmpty() ? null : sources.getFirst();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        long status = node.asLong(0);
        return status >= 200 && status <= 299;
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * Resolves {@code path} within the first source that yields a JSON document and has a present,
     * non-null value there. A source may already be a structured node or a JSON-encoded string.
     */
    private JsonNode extract(List<JsonNode> sources, String path) {
        for (JsonNode source : sources) {
            JsonNode document = asDocument(source);
            if (document == null) {
                continue;
            }
            JsonNode found = JsonPaths.walk(document, path);
            if (found != null && !found.isMissingNode() && !found.isNull()) {
                return found;
            }
        }
        return null;
    }

    /** Returns a source as a JSON document: structured nodes as-is, textual nodes parsed; null otherwise. */
    private JsonNode asDocument(JsonNode source) {
        if (source == null || source.isMissingNode() || source.isNull()) {
            return null;
        }
        if (source.isObject() || source.isArray()) {
            return source;
        }
        String text = source.asString("");
        if (text.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readTree(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String path(Map<String, Object> args) {
        Object value = args == null ? null : args.get("path");
        if (value == null) {
            throw new IllegalArgumentException("JSON-extraction deriver requires 'args.path'");
        }
        return value.toString();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asString("");
    }

    /** Name-based UUIDv5 (SHA-1) over namespace + name, per RFC 4122. */
    private static UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(toBytes(namespace));
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // variant RFC 4122
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (hash[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (hash[i] & 0xff);
            }
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is required for deterministic event ids but is unavailable", ex);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] out = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (msb >>> (8 * (7 - i)));
        }
        for (int i = 8; i < 16; i++) {
            out[i] = (byte) (lsb >>> (8 * (15 - i)));
        }
        return out;
    }
}
