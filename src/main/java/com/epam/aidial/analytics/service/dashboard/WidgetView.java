package com.epam.aidial.analytics.service.dashboard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How a widget draws its query's result. Stored as given and never checked against the query: whether a
 * result suits a view is decided when the client runs and renders the widget.
 */
public enum WidgetView {
    QUERY("query"),
    TABLE("table"),
    SINGLE_VALUE("single_value"),
    TIME_SERIES("time_series"),
    SHARE("share"),
    HEATMAP("heatmap");

    private final String token;

    WidgetView(String token) {
        this.token = token;
    }

    @JsonValue
    public String token() {
        return token;
    }

    @JsonCreator
    public static WidgetView of(String value) {
        return TokenEnums.parse(values(), WidgetView::token, value, "view");
    }
}
