package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

/** A consequence of an {@code OVERWRITE} change that the import reports and leaves to the operator. */
public record ImportEffect(
        @Schema(description = "`generation_bump` (the pipeline's generation moves, so dependent rollups wait for its next "
                + "write), `rebuild_from_zero` (an aggregate rescans its input), `rearm_required` (the import disabled the "
                + "pipeline) or `existing_rows_not_rederived` (rows already written keep their values until a backfill)",
                example = "generation_bump")
        Kind kind,

        @Schema(description = "The pipeline affected", example = "usage-identity-live")
        String pipeline) {

    /** What the change sets off. */
    public enum Kind {
        GENERATION_BUMP("generation_bump"),
        REBUILD_FROM_ZERO("rebuild_from_zero"),
        REARM_REQUIRED("rearm_required"),
        EXISTING_ROWS_NOT_REDERIVED("existing_rows_not_rederived");

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
