package com.epam.aidial.analytics.web.dto.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One ordered argument of a supported query function. {@code optional}, {@code variadic}, and
 * {@code constraints} are present only when applicable (the two flags only when true) — never
 * serialized as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryFunctionArgDto(
        @Schema(description = "Argument name as used in the signature", example = "unit")
        String name,

        @Schema(description = "Argument kind: any expression, or a required literal type", example = "string_literal")
        QueryFunctionArgKind kind,

        @Schema(description = "Present (true) when the argument may be omitted", example = "true")
        Boolean optional,

        @Schema(description = "Present (true) when the argument may be repeated, e.g. a JSON path key",
                example = "true")
        Boolean variadic,

        @Schema(description = "Constraints on the argument's literal value, when any apply")
        QueryFunctionArgConstraintsDto constraints) {}
