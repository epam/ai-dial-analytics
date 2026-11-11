package com.epam.aidial.analytics.web.dto.setting;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** The stored sessions-source setting, with the time and author of its last write. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Stored sessions-source setting")
public record SessionsSourceSettingDto(
        @Schema(description = "An ACTIVE source table of the catalog", example = "sessions_rollup")
        @JsonProperty("base_table") String baseTable,

        @JsonProperty("roles") SessionsSourceRolesDto roles,

        @Schema(description = "Time of the last write, ISO-8601", example = "2026-01-01T00:00:00Z")
        @JsonProperty("updated_at") Instant updatedAt,

        @Schema(description = "Principal of the last writer; null when security is disabled",
                example = "admin", nullable = true)
        @JsonProperty("updated_by") String updatedBy) {
}
