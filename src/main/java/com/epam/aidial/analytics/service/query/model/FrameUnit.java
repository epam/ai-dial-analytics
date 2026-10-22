package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** The unit a {@link WindowFrame} counts in. Wire values {@code rows}/{@code range}. */
public enum FrameUnit {
    ROWS("rows"),
    RANGE("range");

    private final String code;

    FrameUnit(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
