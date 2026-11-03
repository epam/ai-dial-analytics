package com.epam.aidial.analytics.service.dashboard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * An inline query's {@code result_view}: the saved-query vocabulary ({@code table} / {@code chart}) behind a
 * token binding. {@code SavedQueryResultView} itself is a plain enum and would bind a number by position,
 * which a dashboard must refuse.
 */
public enum InlineResultView {
    TABLE("table"),
    CHART("chart");

    private final String token;

    InlineResultView(String token) {
        this.token = token;
    }

    @JsonValue
    public String token() {
        return token;
    }

    @JsonCreator
    public static InlineResultView of(String value) {
        return TokenEnums.parse(values(), InlineResultView::token, value, "result_view");
    }
}
