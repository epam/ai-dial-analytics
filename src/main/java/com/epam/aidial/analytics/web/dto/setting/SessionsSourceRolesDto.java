package com.epam.aidial.analytics.web.dto.setting;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The session roles, each a flat field name as {@code GET /v1/queries/entities/schema/{base_table}} reports it
 * (bare for a source column, {@code <enrichment_table>.<name>} for an enrichment column), or {@code null} when
 * the role is not set. An unset role is serialized as {@code null}, not omitted.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Session role bindings: flat field names of the base table's entity schema; null means the role is not set")
public record SessionsSourceRolesDto(
        @Schema(description = "Session identifier; required; a string field", example = "session_id", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("session_id") String sessionId,

        @Schema(description = "Session time; required; a timestamp field", example = "session_start", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("time") String time,

        @Schema(description = "Project; a string field", example = "project_id", nullable = true)
        @JsonProperty("project") String project,

        @Schema(description = "Title; a string field", example = "session_insights.title", nullable = true)
        @JsonProperty("title") String title,

        @Schema(description = "Cost; a decimal, integer or long field", example = "total_price", nullable = true)
        @JsonProperty("cost") String cost,

        @Schema(description = "Turn count; an integer or long field", example = "turns", nullable = true)
        @JsonProperty("turns") String turns) {
}
