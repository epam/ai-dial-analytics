package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import org.jooq.Field;
import org.jooq.Select;

/**
 * Renders a nested {@link CompositeQuery} into the jOOQ {@link Select} a subquery predicate or a scalar
 * subquery embeds. The composite renderer supplies itself; every other caller of the translators gets
 * {@link #none()}, which refuses — that is what keeps a subquery unreachable from
 * {@code POST /v1/queries/execute} even though the internal node types share the DSL's sealed hierarchies.
 */
@FunctionalInterface
public interface SubqueryRenderer {

    /** A scalar subquery as an expression reads it: its value, and its output's mark flag ({@code null}: none). */
    record ScalarSubquery(Field<?> value, Field<?> flag) {}

    Select<?> render(CompositeQuery subquery);

    /**
     * {@code subquery} as a scalar value with its own mark, or {@code null} when it carries none and is embedded as
     * {@link #render} renders it: a statement that reads no masked field.
     */
    default ScalarSubquery renderScalar(CompositeQuery subquery) {
        return null;
    }

    /** The mark flag {@link #renderScalar} rendered for {@code subquery}, or {@code null}. */
    default Field<?> scalarFlag(CompositeQuery subquery) {
        return null;
    }

    /** The renderer for surfaces that admit no subquery: every call is a validation failure. */
    static SubqueryRenderer none() {
        return subquery -> {
            throw new QueryValidationException("subqueries are not supported on this surface");
        };
    }
}
