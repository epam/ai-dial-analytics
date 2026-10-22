package com.epam.aidial.analytics.service.pipeline.preview;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What one column of a preview sample is to the declaration: a group {@code key} or a {@code measure} of an
 * {@code aggregate}, the {@code grain_key} and derived {@code output}s of a Class-1 {@code sql} pipeline, or
 * the grain key and bound {@code input}s an {@code llm} transform would receive.
 *
 * <p>The wire code is exposed through {@link JsonValue}, as {@code QueryFieldType}'s is, so the published
 * OpenAPI enumerates the values the response actually carries.
 */
public enum PreviewColumnRole {
    KEY("key"),
    MEASURE("measure"),
    GRAIN_KEY("grain_key"),
    OUTPUT("output"),
    INPUT("input");

    private final String code;

    PreviewColumnRole(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
