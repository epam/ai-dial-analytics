package com.epam.aidial.analytics.web.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * A declared transform variable. Input vars name values the request template references; output vars
 * additionally carry the value's producer — {@code jsonata} over the model response for an
 * {@code llm} transform, or {@code sql} (a scalar expression over the source's declared columns) for a
 * {@code sql} transform.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransformVarDto(
        @Schema(description = "Variable name", example = "verdict")
        @NotBlank String name,

        @Schema(description = "Logical type (string, long, double, boolean, datetime)", example = "string")
        String type,

        @Schema(description = "JSONata over the model response (llm output vars only)")
        String jsonata,

        @Schema(description = "Scalar expression over the source's declared columns (sql output vars only)",
                example = "json_extract_string(\"dial_usage_log_payload.request_body\", 'model')")
        String sql) {
}
