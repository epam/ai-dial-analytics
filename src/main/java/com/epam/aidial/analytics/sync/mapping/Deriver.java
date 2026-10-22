package com.epam.aidial.analytics.sync.mapping;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Computes a single derived column value from one or more source nodes of the parsed record.
 *
 * <p>Unlike a {@code Transform} (which post-processes a single coerced value), a deriver receives the
 * raw {@link JsonNode}s resolved from the column's {@code sources} paths and the column's static
 * {@code args}, and returns the final value (which the engine then runs through the column's
 * absent/blank fallback). Derivers are registered in {@link DeriverRegistry} by name — never
 * user-scriptable.
 */
@FunctionalInterface
public interface Deriver {

    /**
     * @param sources the nodes resolved from the column's {@code sources} paths, in declared order
     * @param args    the column's static {@code args} map (never {@code null}; may be empty)
     * @return the derived value, or {@code null} to let the column's fallback apply
     */
    Object derive(List<JsonNode> sources, Map<String, Object> args);
}
