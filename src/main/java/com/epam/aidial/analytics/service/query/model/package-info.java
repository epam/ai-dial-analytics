/**
 * Record-based object model for the experimental structured query DSL (request side).
 *
 * <p>Mirrors the wire grammar of the {@code structured-query} OpenSpec capability
 * ({@code openspec/specs/structured-query/spec.md}): a single structured JSON query (filter / sort /
 * pagination / projection / aggregation) that clients POST and the server validates against a
 * per-entity allowlist and translates to parameterized SQL. This package contains
 * <strong>only</strong> the immutable request shape; validation, SQL translation, and the
 * response envelopes live in the sibling {@code service}, {@code service.translate},
 * {@code service.repository}, and {@code web} packages.
 *
 * <p>{@link com.epam.aidial.analytics.service.query.model.Expr} (by {@code type}) and {@link
 * com.epam.aidial.analytics.service.query.model.PageSpec} (by {@code type}) use declarative
 * Jackson NAME discriminators. {@link
 * com.epam.aidial.analytics.service.query.model.FilterNode} is routed by {@link
 * com.epam.aidial.analytics.service.query.model.FilterNodeDeserializer} because its {@code op}
 * key is both discriminator and operator data. A comparison's operands are general {@code Expr}s on
 * both sides (a column is a {@code FieldExpr}); there is no dedicated property-reference type.
 */
package com.epam.aidial.analytics.service.query.model;
