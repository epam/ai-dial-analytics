package com.epam.aidial.analytics.web.dto.setting;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** The stored app-routes setting, with the time and author of its last write. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Stored app-routes setting")
public record AppRoutesSettingDto(
        @Schema(description = "The allowed pairs, in the order written")
        @JsonProperty("routes") List<AppRouteDto> routes,

        @Schema(description = "Time of the last write, ISO-8601", example = "2026-01-01T00:00:00Z")
        @JsonProperty("updated_at") Instant updatedAt,

        @Schema(description = "Principal of the last writer; null when security is disabled",
                example = "admin", nullable = true)
        @JsonProperty("updated_by") String updatedBy) {
}
