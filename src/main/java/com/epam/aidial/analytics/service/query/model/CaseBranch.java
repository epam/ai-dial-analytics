package com.epam.aidial.analytics.service.query.model;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * One {@code WHEN … THEN …} branch of a {@link CaseExpr}: a filter-grammar condition and the value the
 * branch yields. The condition is routed through {@link FilterNodeDeserializer} exactly as a
 * {@code filter}/{@code having} tree is.
 */
public record CaseBranch(
        @JsonDeserialize(using = FilterNodeDeserializer.class) FilterNode when,
        Expr then) {}
