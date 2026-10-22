package com.epam.aidial.analytics.service.query.model;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * Logical combinator: {@code and}/{@code or} over N children, {@code not} over one. The
 * {@code not}-arity-1 rule is a validation concern and is not enforced structurally here.
 */
public record LogicalNode(
        LogicalOp op,

        @JsonDeserialize(contentUsing = FilterNodeDeserializer.class)
        List<FilterNode> args) implements FilterNode {}
