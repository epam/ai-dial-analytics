package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

/** A difference between the bundle and an existing object that an {@code OVERWRITE} import keeps as the target has it. */
public record NotApplied(
        @Schema(description = "`extra_column` (never dropped), `column_order` (never reordered), `sensitive_loosening` "
                + "(sensitivity is never cleared) or `target_owned` (an environment-specific field)", example = "extra_column")
        Kind kind,

        @Schema(description = "The column or field concerned", example = "local_note")
        String name,

        @Schema(description = "Why it is kept")
        String reason) {

    /** Why a difference is kept. */
    public enum Kind {
        EXTRA_COLUMN("extra_column"),
        COLUMN_ORDER("column_order"),
        SENSITIVE_LOOSENING("sensitive_loosening"),
        TARGET_OWNED("target_owned");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        @JsonValue
        public String code() {
            return code;
        }
    }
}
