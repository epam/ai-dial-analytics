package com.epam.aidial.analytics.web.dto.setting;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** One allowed pair of the app-routes setting. */
@Schema(description = "A DIAL application and one of its routes")
public record AppRouteDto(
        @Schema(description = "DIAL application deployment id", example = "insights-app",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("deployment") String deployment,

        @Schema(description = "Route under /v1/deployments/{deployment}/route/", example = "insights",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("route") String route) {
}
