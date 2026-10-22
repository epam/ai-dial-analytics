package com.epam.aidial.analytics.web.dto.query;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for {@code POST /v1/queries/translate}: the external-dialect SQL rendering of the
 * submitted structured query. An object envelope so fields can be added later without breaking
 * clients.
 */
@Schema(description = "The SQL translation of a structured query DSL document.")
public record StructuredQueryTranslationResponseDto(
        @Schema(
                description = "A single SELECT statement in the SQL subset accepted by POST /v1/queries/execute-sql.",
                example = "SELECT \"deployment\", count(*) AS \"n\" FROM \"dial_usage_log\" GROUP BY \"deployment\" ORDER BY \"n\" DESC LIMIT 10")
        String sql) {}
