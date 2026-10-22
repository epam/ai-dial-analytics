package com.epam.aidial.analytics.web.dto.query;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** The response of {@code GET /v1/queries/sql-functions}: the SQL surface's function catalog. */
public record SqlFunctionCatalogDto(
        @Schema(description = "Every accepted spelling, ordered by name compared case-insensitively")
        List<SqlFunctionDto> functions) {}
