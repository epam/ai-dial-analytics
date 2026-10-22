package com.epam.aidial.analytics.service.query.model;

/**
 * A runtime parameter: {@code { "type": "param", "name": "min_threshold" }}, resolved at
 * execution from a trusted server-side context and emitted as a bind parameter.
 */
public record ParamExpr(String name) implements Expr {}
