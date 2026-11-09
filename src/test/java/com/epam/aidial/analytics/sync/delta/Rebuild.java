package com.epam.aidial.analytics.sync.delta;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The rebuild the spec promises, for tests: a thin adapter from stored payload rows (column name → value maps) to the
 * production {@link RequestBodyRebuild}, so the ingest tests check what they store against the same implementation
 * the read surfaces rebuild with. A rebuild that does not complete fails the test rather than returning the stored
 * delta: every row these tests rebuild has its whole chain stored.
 */
public final class Rebuild {

    public static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private Rebuild() {
    }

    /** The full body of {@code row}, rebuilt from {@code stored} (every payload row that exists). */
    public static JsonNode body(Map<String, Object> row, Collection<Map<String, Object>> stored) {
        final Map<UUID, RequestBodyRebuild.Ancestor> byPath = new HashMap<>();
        for (Map<String, Object> candidate : stored) {
            final Object path = candidate.get("request_hash");
            if (path != null) {
                byPath.putIfAbsent(uuid(path), new RequestBodyRebuild.Ancestor(String.valueOf(candidate.get("event_id")),
                        uuid(path), uuid(candidate.get("request_parent_hash")), (String) candidate.get("request_body")));
            }
        }
        final RequestBodyRebuild.Result result = RequestBodyRebuild.rebuild((String) row.get("request_body"),
                uuid(row.get("request_parent_hash")), (String) row.get("request_cache_control"), byPath);
        if (result.outcome() == RequestBodyRebuild.Outcome.PARTIAL) {
            throw new AssertionError("the rebuild did not complete: " + result.reason());
        }
        return result.body() == null ? null : MAPPER.readTree(result.body());
    }

    private static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
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
