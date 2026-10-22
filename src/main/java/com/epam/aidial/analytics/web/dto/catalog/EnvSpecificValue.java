package com.epam.aidial.analytics.web.dto.catalog;

import io.swagger.v3.oas.annotations.media.Schema;

/** A bundle value that is likely to mean something different on the target environment. */
public record EnvSpecificValue(
        @Schema(description = "Object kind: `table` or `pipeline`", example = "pipeline")
        String type,

        @Schema(description = "Object name", example = "usage_sentiment_llm")
        String name,

        @Schema(description = "The field holding the value", example = "transform.model")
        String field,

        @Schema(description = "The value", example = "gemini-2.5-flash-lite")
        String value) {
}
