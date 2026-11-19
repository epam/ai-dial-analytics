package com.epam.aidial.analytics.service.query.model;

/**
 * A runtime parameter: {@code { "type": "param", "name": "caller.user_hash" }}. Only the caller values
 * ({@code caller.user_hash}, {@code caller.project}) are ever bound — resolved at execution from the identity of
 * the request and inlined as an escaped string literal (see {@code CallerValues}); every other name is refused.
 */
public record ParamExpr(String name) implements Expr {}
