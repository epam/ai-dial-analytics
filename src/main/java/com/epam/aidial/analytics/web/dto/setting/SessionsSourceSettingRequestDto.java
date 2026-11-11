package com.epam.aidial.analytics.web.dto.setting;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code PUT /v1/settings/sessions-source}. {@code updated_at} and {@code updated_by} are
 * server-stamped and deliberately absent here, so the strict request mapper refuses them with {@code 400} like
 * any other unknown member.
 */
@Schema(description = "Sessions-source setting to store")
public record SessionsSourceSettingRequestDto(
        @Schema(description = "An ACTIVE source table of the catalog", example = "sessions_rollup", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("base_table") String baseTable,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("roles") SessionsSourceRolesDto roles) {
}
