package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A literal: {@code value} is <strong>always</strong> a JSON string and {@code
 * value_type} governs how it is parsed into a typed value (by {@code ValueExprToObjectMapper} during
 * translation). For {@code value_type == null}, {@code value} is ignored.
 */
public record ValueExpr(
        @JsonProperty("value_type") ValueType valueType,
        @Schema(description = "The literal as a string, parsed by value_type; null or absent when value_type is null", nullable = true)
        String value) implements Expr {}
