package com.epam.aidial.analytics.web.dto.setting;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The body of {@code PUT /v1/settings/app-routes}. {@code updated_at} and {@code updated_by} are server-stamped and
 * deliberately absent here, so the strict request mapper refuses them with {@code 400} like any other unknown member.
 */
@Schema(description = "App-routes setting to store")
public record AppRoutesSettingRequestDto(
        @Schema(description = "The allowed pairs; an empty list allows no route",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("routes") List<AppRouteDto> routes) {
}
