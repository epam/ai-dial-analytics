package com.epam.aidial.analytics.web.dto.query;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /v1/queries/execute-sql}: a single read-only SQL SELECT statement.
 * An object rather than a bare string so future fields (e.g. a total-count opt-in) can be added
 * without breaking clients.
 */
@Schema(description = "A single ad-hoc SQL SELECT statement to execute.")
public record SqlQueryRequestDto(
        @NotBlank
        @Schema(
                description = "One read-only SQL SELECT statement over a queryable entity.",
                example = "SELECT deployment, count(*) AS n FROM dial_usage_log GROUP BY deployment ORDER BY n DESC LIMIT 10")
        String sql) {}
