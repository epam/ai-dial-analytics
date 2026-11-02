package com.epam.aidial.analytics.service.dashboard.model;

import com.epam.aidial.analytics.service.dashboard.ChangeDirection;
import com.epam.aidial.analytics.service.dashboard.WidgetView;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One widget as stored in the dashboard's {@code widgets} document and returned on read. Exactly one of
 * {@code saved_query_id} and {@code inline_query} is set. {@code view}, {@code value_column} and
 * {@code change_direction} are rendering hints, stored as given and never checked against the query.
 */
public record DashboardWidget(
        String id,
        @JsonProperty("saved_query_id") String savedQueryId,
        @JsonProperty("inline_query") InlineQuery inlineQuery,
        String title,
        String description,
        @JsonProperty("row_limit") int rowLimit,
        WidgetView view,
        @JsonProperty("value_column") String valueColumn,
        @JsonProperty("change_direction") ChangeDirection changeDirection,
        WidgetLayout layout) {}
