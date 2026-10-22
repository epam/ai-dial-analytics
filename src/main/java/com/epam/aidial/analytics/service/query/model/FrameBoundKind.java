package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** The five SQL frame-bound spellings. Wire values are the lowercase snake_case names. */
public enum FrameBoundKind {
    UNBOUNDED_PRECEDING("unbounded_preceding"),
    PRECEDING("preceding"),
    CURRENT_ROW("current_row"),
    FOLLOWING("following"),
    UNBOUNDED_FOLLOWING("unbounded_following");

    private final String code;

    FrameBoundKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
