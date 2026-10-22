package com.epam.aidial.analytics.service.pipeline.preview;

import com.fasterxml.jackson.annotation.JsonValue;

/** How much a preview note matters before arming: worth knowing ({@code info}) or worth acting on ({@code warning}). */
public enum PreviewNoteSeverity {
    INFO("info"),
    WARNING("warning");

    private final String code;

    PreviewNoteSeverity(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
