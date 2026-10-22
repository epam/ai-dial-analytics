package com.epam.aidial.analytics.web.dto.query;

import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for {@code POST /v1/queries/translate-sql}: the structured query DSL document the
 * submitted SQL translates to. An object envelope (not the bare document) so fields such as result
 * columns or warnings can be added later without breaking clients.
 */
@Schema(description = "The structured query DSL translation of a SQL statement.")
public record SqlTranslationResponseDto(
        @Schema(description = "The DSL document, accepted verbatim by POST /v1/queries/execute.")
        StructuredQuery query) {}
