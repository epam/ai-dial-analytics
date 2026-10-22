package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A literal: {@code value} is <strong>always</strong> a JSON string and {@code
 * value_type} governs how it is parsed into a typed value (by {@code ValueExprToObjectMapper} during
 * translation). For {@code value_type == null}, {@code value} is ignored.
 */
public record ValueExpr(@JsonProperty("value_type") ValueType valueType, String value) implements Expr {}
