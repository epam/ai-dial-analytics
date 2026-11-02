package com.epam.aidial.analytics.service.dashboard.model;

import com.epam.aidial.analytics.service.dashboard.InlineResultView;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryChart;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A query a widget holds itself, as stored: exactly one of {@code query} and {@code sql}, a
 * {@code result_view} filled in when it was absent, and an optional chart in the saved-query model. A
 * snapshot — nothing links it to a saved query it may have been copied from.
 */
public record InlineQuery(
        String name,
        StructuredQuery query,
        String sql,
        @JsonProperty("result_view") InlineResultView resultView,
        SavedQueryChart chart) {}
