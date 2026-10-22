package com.epam.aidial.analytics.service.query.model;

/** A column reference: {@code { "type": "field", "name": "amount" }}. */
public record FieldExpr(String name) implements Expr {}
