package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** A bundle value that is likely to mean something different on the target environment. */
public record EnvSpecificValue(
        @Schema(description = "Object kind: `table` or `pipeline`", example = "pipeline")
        String type,

        @Schema(description = "Object name", example = "usage_sentiment_llm")
        String name,

        @Schema(description = "The field holding the value", example = "transform.model")
        String field,

        @Schema(description = "The bundle's value; absent when only the target sets the field", example = "gemini-2.5-flash-lite")
        String value,

        @Schema(description = "The target's value of the field on an existing object; absent on a new object or when the "
                + "target leaves it unset", example = "gemini-2.5-pro")
        @JsonProperty("target_value") String targetValue,

        @Schema(description = "Whether the target's value stays in place of the bundle's: true for a target-owned field of an "
                + "existing object, false for a new object, a date literal, and a deployment binding whose preset changed "
                + "under OVERWRITE")
        boolean kept) {
}
