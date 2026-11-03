package com.epam.aidial.analytics.web.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * A widget's layout as sent. Each coordinate is a {@link JsonNode} for the reason {@link WidgetRequestDto}
 * gives for {@code row_limit}: {@code DashboardValidator} decides whether it is an integer in range.
 */
public record LayoutRequestDto(
        @Schema(description = "Column, 0 to 11", type = "integer") JsonNode x,
        @Schema(description = "Row, 0 or more", type = "integer") JsonNode y,
        @Schema(description = "Width in columns, 1 to 12; x + w must not exceed 12", type = "integer") JsonNode w,
        @Schema(description = "Height in rows, 1 or more", type = "integer") JsonNode h) {}
