package com.epam.aidial.analytics.web.dto.dashboard;

import com.epam.aidial.analytics.service.dashboard.InlineResultView;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryChart;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

/**
 * A widget's inline query as sent. It declares no {@code time}, {@code tag}, {@code scope}, {@code source},
 * owner or timestamp: the widget runs under its dashboard's time intent and belongs to its dashboard, so a
 * saved query copied here whole carries members the strict request reader refuses, naming the first.
 */
public record InlineQueryRequestDto(
        @Schema(description = "Display name; required and non-blank")
        String name,

        @Schema(description = "Structured query body. Exactly one of 'query' or 'sql' must be set.")
        @Valid StructuredQuery query,

        @Schema(description = "SQL body. Exactly one of 'query' or 'sql' must be set.")
        String sql,

        @Schema(description = "table (default) or chart")
        @JsonProperty("result_view") InlineResultView resultView,

        @Schema(description = "Chart configuration in the saved-query model; stored verbatim")
        SavedQueryChart chart) {}
