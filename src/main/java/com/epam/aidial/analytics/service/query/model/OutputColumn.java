package com.epam.aidial.analytics.service.query.model;

/**
 * A single output column in {@code select}: an expression and an optional alias. {@code as} is
 * optional for <em>every</em> expression kind and in both query modes — when it is absent (or blank)
 * the output name is derived PostgreSQL-style by {@link OutputColumnNaming} (field name for a plain
 * {@link FieldExpr}, lowercase function name for a call, {@code ?column?} for a literal), with
 * colliding names de-duplicated by a positional {@code _<n>} suffix. An explicit alias only overrides
 * that default; it is never a requirement.
 */
public record OutputColumn(Expr expr, String as) {}
