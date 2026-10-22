package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** One object selected for export, by kind and name. */
public record ExportComponent(
        @Schema(description = "Object kind", example = "table")
        @NotNull Type type,

        @Schema(description = "Table or pipeline name", example = "usage_sentiment")
        @NotBlank String name) {

    /** The kinds of object a bundle carries. */
    public enum Type {
        TABLE("table"),
        PIPELINE("pipeline");

        private final String code;

        Type(String code) {
            this.code = code;
        }

        @JsonValue
        public String code() {
            return code;
        }
    }
}
