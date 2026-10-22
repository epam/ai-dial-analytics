package com.epam.aidial.analytics.web.dto.query;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Result of executing a structured query. {@code rows} are ordered field-name → value maps, keyed by
 * the resolved output column names — a select entry's {@code as} when it has one, else the
 * PostgreSQL-shaped default for its expression (see {@code OutputColumnNaming}); JSONB-backed columns
 * are returned as nested JSON, not escaped strings. Every projected column appears in every row, carrying a JSON {@code null} when
 * its value is null (e.g. an unmatched enrichment column) — null cells are not dropped. {@code
 * totalCount} is present only when offset paging requested {@code include_total} in row mode.
 */
@Schema(description = "Rows produced by a structured query, plus an optional total row count.")
public record StructuredQueryResultDto(
        @Schema(
                description = "Projected rows as field-name → value maps, in field order.",
                example = "[{\"id\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\",\"name\":\"my-suite\"}]")
        List<Map<String, Object>> rows,

        @Schema(
                description = "Total matching rows ignoring paging; only set for row-mode offset paging"
                        + " with include_total=true.",
                example = "42",
                nullable = true)
        Long totalCount) {}
