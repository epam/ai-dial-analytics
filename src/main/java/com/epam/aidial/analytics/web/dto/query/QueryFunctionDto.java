package com.epam.aidial.analytics.web.dto.query;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One supported query function as listed by {@code GET /v1/queries/functions} — the discovery view
 * of the closed function catalog accepted in structured query {@code fn} expressions.
 */
public record QueryFunctionDto(
        @Schema(description = "Function wire name as accepted in a fn expression", example = "date_bin")
        String name,

        @Schema(description = "Function group", example = "scalar")
        QueryFunctionGroup group,

        @Schema(description = "Human-readable signature", example = "date_bin(amount, unit, timestamp)")
        String signature,

        @Schema(description = "Declared return type", example = "timestamp")
        QueryFunctionReturnType returns,

        @Schema(description = "Whether the distinct flag is meaningful for this function", example = "false")
        @JsonProperty("distinct_supported") boolean distinctSupported,

        // Serialized on EVERY entry rather than only where it is false: a client reading a catalog that
        // omits the member cannot tell "deterministic" from "a service too old to say". It is the property
        // the derivation paths refuse a function by (ExprSqlValidator, FilterSqlValidator), so publishing it
        // lets a client predict a pipeline rejection from discovery alone.
        @Schema(description = "Whether two evaluations over the same rows are guaranteed to agree; false for now()",
                example = "true")
        boolean deterministic,

        @Schema(description = "Short human-readable description",
                example = "Truncates timestamp to the start of the fixed-width interval amount × unit.")
        String description,

        @Schema(description = "Ordered argument list")
        List<QueryFunctionArgDto> args) {}
