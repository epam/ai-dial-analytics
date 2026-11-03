package com.epam.aidial.analytics.web.dto.dashboard;

import com.epam.aidial.analytics.service.dashboard.DashboardCompare;
import com.epam.aidial.analytics.service.dashboard.DashboardVisibility;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryTime;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Create/replace body for a dashboard: the dashboard as read, without its server-assigned members, and
 * with {@code generation} kept on a replace.
 *
 * <p>Follows the saved-query request's rejection pattern. The trailing components are <strong>not
 * inputs</strong>: they are declared so that supplying one is a {@code 422} from {@code DashboardValidator}
 * rather than a silent drop. They, {@code generation} and {@code time} are {@link JsonNode}s so that any
 * shape of them reaches the validator instead of failing inside Jackson as a {@code 400}. {@code @Valid}
 * cascades into each widget's inline structured body, which is held to the query surface's own contract.
 */
public record DashboardRequestDto(
        @Schema(description = "Display name; required and non-blank", example = "Weekly adoption")
        String name,

        @Schema(description = "Optional free-text description")
        String description,

        @Schema(description = "private (owner only) or public; changing it requires SAVED_QUERY_PUBLISH",
                example = "private")
        DashboardVisibility visibility,

        @Schema(implementation = SavedQueryTime.class,
                description = "The dashboard's time intent, in the saved-query time model")
        JsonNode time,

        @Schema(description = "Period comparison: off, previous-period, previous-month or previous-year")
        DashboardCompare compare,

        @Schema(description = "Any JSON object the client keeps with the dashboard; stored and returned as sent",
                type = "object")
        JsonNode settings,

        @Schema(description = "The ordered widgets; required, possibly empty, at most 100")
        @Valid List<WidgetRequestDto> widgets,

        @Schema(description = "Required on a replace: the generation the client last read; rejected on a create",
                type = "integer", example = "3")
        JsonNode generation,

        @Schema(hidden = true) JsonNode id,
        @Schema(hidden = true) @JsonProperty("owner_id") JsonNode ownerId,
        @Schema(hidden = true) @JsonProperty("owner_email") JsonNode ownerEmail,
        @Schema(hidden = true) @JsonProperty("created_at") JsonNode createdAt,
        @Schema(hidden = true) @JsonProperty("updated_at") JsonNode updatedAt,
        @Schema(hidden = true) JsonNode permissions) {}
