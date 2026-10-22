package com.epam.aidial.analytics.web.dto.table;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response for {@code POST /v1/tables/{name}/rows}: how many rows were inserted. */
public record WriteRowsResponse(
        @Schema(description = "Number of rows inserted", example = "2")
        int inserted) {
}
