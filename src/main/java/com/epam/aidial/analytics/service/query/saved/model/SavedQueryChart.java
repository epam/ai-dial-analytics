package com.epam.aidial.analytics.service.query.saved.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A saved query's chart configuration: the chart type and which result columns feed its axes. Purely
 * a client rendering preference — ADAS stores and returns it without interpreting it, and never
 * validates the field names against the catalog, because they name <em>result</em> columns (aliases,
 * derived labels like {@code Request time (Date bin)}) rather than catalog columns.
 *
 * <p>Null-tolerant by design: a partially configured chart is a legitimate saved state.
 */
public record SavedQueryChart(
        String type,
        @JsonProperty("x_field") String fieldX,
        @JsonProperty("y_field") String fieldY) {}
