package com.epam.aidial.analytics.web.dto.dashboard;

import com.epam.aidial.analytics.service.dashboard.ChangeDirection;
import com.epam.aidial.analytics.service.dashboard.WidgetView;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import tools.jackson.databind.JsonNode;

/**
 * One widget of a dashboard create/replace body. {@code row_limit} is a {@link JsonNode}, as the layout
 * coordinates are: neither request mapper disables Jackson's scalar coercion, so binding it to an
 * {@code int} would store {@code 2.5} as {@code 2} and {@code "5"} as {@code 5} rather than refuse them.
 * {@code DashboardValidator} requires an integral number and owns the {@code 422}.
 */
public record WidgetRequestDto(
        @Schema(description = "Client-assigned id, unique within the dashboard", example = "w1")
        String id,

        @Schema(description = "A saved query this widget runs; exactly one of saved_query_id and inline_query")
        @JsonProperty("saved_query_id") String savedQueryId,

        @Schema(description = "A query this widget holds itself; exactly one of saved_query_id and inline_query")
        @JsonProperty("inline_query") @Valid InlineQueryRequestDto inlineQuery,

        @Schema(description = "Author-written title, shown to every viewer")
        String title,

        @Schema(description = "Author-written description, shown to every viewer")
        String description,

        @Schema(description = "Rows the widget fetches, from 1 to 1000", type = "integer", example = "100")
        @JsonProperty("row_limit") JsonNode rowLimit,

        @Schema(description = "How the widget draws its result; absent means the query's own view")
        WidgetView view,

        @Schema(description = "The aggregate a single-value widget shows")
        @JsonProperty("value_column") String valueColumn,

        @Schema(description = "Whether a rise in the figure is good news")
        @JsonProperty("change_direction") ChangeDirection changeDirection,

        @Schema(description = "Any JSON object the client keeps with the widget; stored and returned as sent",
                type = "object")
        JsonNode settings,

        @Schema(description = "Position on the 12-column grid")
        LayoutRequestDto layout) {}
