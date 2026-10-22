package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import org.jooq.Select;

/**
 * Renders a nested {@link CompositeQuery} into the jOOQ {@link Select} a subquery predicate or a scalar
 * subquery embeds. The composite renderer supplies itself; every other caller of the translators gets
 * {@link #none()}, which refuses — that is what keeps a subquery unreachable from
 * {@code POST /v1/queries/execute} even though the internal node types share the DSL's sealed hierarchies.
 */
@FunctionalInterface
public interface SubqueryRenderer {

    Select<?> render(CompositeQuery subquery);

    /** The renderer for surfaces that admit no subquery: every call is a validation failure. */
    static SubqueryRenderer none() {
        return subquery -> {
            throw new QueryValidationException("subqueries are not supported on this surface");
        };
    }
}
