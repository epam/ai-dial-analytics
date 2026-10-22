package com.epam.aidial.analytics.web.dto.table;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * Request body for {@code POST /v1/tables/{name}/rows}. {@code rows} is a non-empty list of rows; each
 * row is a JSON object keyed by <em>physical</em> ClickHouse column name (the {@code source_name}, and
 * for an enrichment its grain key) with a JSON value compatible with the column's type.
 */
public record WriteRowsRequest(
        @Schema(description = "Rows to insert, each keyed by physical column name")
        @NotEmpty List<Map<String, Object>> rows) {
}
