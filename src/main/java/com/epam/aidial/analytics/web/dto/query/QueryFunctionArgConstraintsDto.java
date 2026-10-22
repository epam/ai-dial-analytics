package com.epam.aidial.analytics.web.dto.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Machine-readable constraints on a supported function's argument. Only the applicable members are
 * present — inapplicable ones are omitted from the JSON rather than serialized as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryFunctionArgConstraintsDto(
        @Schema(description = "Closed set of accepted literal values (case-insensitive)", example = "[\"day\", \"hour\", \"minute\", \"second\", \"week\"]")
        @JsonProperty("allowed_values") List<String> allowedValues,

        @Schema(description = "Inclusive lower bound for a numeric literal", example = "1")
        BigDecimal min,

        @Schema(description = "Inclusive upper bound for a numeric literal", example = "1")
        BigDecimal max) {}
