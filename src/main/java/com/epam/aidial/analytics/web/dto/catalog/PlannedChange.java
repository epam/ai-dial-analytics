package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** One change an {@code OVERWRITE} import makes to an existing object, through the service its endpoint uses. */
public record PlannedChange(
        @Schema(description = "`update_table` (PUT /v1/tables/{name}), `add_column` or `update_column` (PATCH "
                + "/v1/tables/{name}/schema), `disable` or `redeclare` (PATCH /v1/pipelines/{name})", example = "add_column")
        Op op,

        @Schema(description = "The column, for a column change", example = "client_version")
        @JsonInclude(JsonInclude.Include.NON_NULL) String name,

        @Schema(description = "The members that change", example = "[\"description\"]")
        List<String> fields) {

    /** What kind of change. */
    public enum Op {
        UPDATE_TABLE("update_table"),
        ADD_COLUMN("add_column"),
        UPDATE_COLUMN("update_column"),
        DISABLE("disable"),
        REDECLARE("redeclare");

        private final String code;

        Op(String code) {
            this.code = code;
        }

        @JsonValue
        public String code() {
            return code;
        }
    }
}
