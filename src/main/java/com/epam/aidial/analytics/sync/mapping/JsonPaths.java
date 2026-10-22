package com.epam.aidial.analytics.sync.mapping;

import tools.jackson.databind.JsonNode;

/**
 * Shared dotted-path navigation for the mapping pipeline. Used by both {@link MappingEngine} (mapped
 * sources) and {@link DeriverRegistry} (derived sources) so the two resolve paths identically.
 */
final class JsonPaths {

    private JsonPaths() {
    }

    /**
     * A dotted path whose segments are split once (at mapping-load time) and reused for every record,
     * so the ingest hot path walks without re-splitting the path on each row.
     *
     * @param segments the path split on {@code .}, in order
     */
    record CompiledPath(String[] segments) {
    }

    /** Pre-splits a dotted path so it can be walked repeatedly without re-splitting. */
    static CompiledPath compile(String path) {
        return new CompiledPath(path.split("\\."));
    }

    /**
     * Resolves a dotted path (e.g. {@code request.body} or {@code messages.0.role}) against a node. A
     * numeric segment is read as an array index when the current node is an array; otherwise it is read
     * as an object field name. A segment that does not resolve yields a missing node, so the caller's
     * absent/blank fallback applies.
     */
    static JsonNode walk(JsonNode node, String path) {
        return walk(node, path.split("\\."));
    }

    /** Resolves a pre-{@link #compile(String) compiled} path against a node (no per-call splitting). */
    static JsonNode walk(JsonNode node, CompiledPath path) {
        return walk(node, path.segments());
    }

    private static JsonNode walk(JsonNode node, String[] segments) {
        JsonNode current = node;
        for (String segment : segments) {
            if (current.isArray() && isIndex(segment)) {
                current = current.path(Integer.parseInt(segment));
            } else {
                current = current.path(segment);
            }
        }
        return current;
    }

    private static boolean isIndex(String segment) {
        if (segment.isEmpty() || segment.length() > 9) {
            // length cap keeps Integer.parseInt safe from overflow without try/catch.
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
