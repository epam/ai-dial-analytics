package com.epam.aidial.analytics.service.enrichment.model;

/**
 * The kind of transform, and the router between the two execution paths.
 *
 * <ul>
 *   <li>{@link #LLM} — Class-2: a model call renders a request, and JSONata maps the response onto
 *       output variables. Requires {@code model} and {@code preset}.
 *   <li>{@link #SQL} — Class-1: a deterministic derivation computed set-based inside ClickHouse from
 *       each output variable's scalar {@code sql} expression. Carries no model, preset, request
 *       template, input variables, or response schema.
 * </ul>
 */
public enum TransformType {
    LLM,
    SQL
}
