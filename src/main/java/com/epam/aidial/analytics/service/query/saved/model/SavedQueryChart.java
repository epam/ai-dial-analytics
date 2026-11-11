package com.epam.aidial.analytics.service.query.saved.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A saved query's chart configuration: the chart type and which result columns feed its axes. Purely
 * a client rendering preference — ADAS stores and returns it without interpreting it, and never
 * validates the field names against the catalog, because they name <em>result</em> columns (aliases,
 * derived labels like {@code Request time (Date bin)}) rather than catalog columns.
 *
 * <p>{@code y_field} is one element per series, so a one-element list is a single-series chart. A single
 * string is read as a one-element list: older clients send one, and charts stored before {@code y_field}
 * became a list hold one — in saved queries, their revision snapshots and dashboard widgets alike, all of
 * which are read through this record, so no stored row needs rewriting.
 *
 * <p>Null-tolerant by design: a partially configured chart is a legitimate saved state.
 */
public record SavedQueryChart(
        String type,
        @JsonProperty("x_field") String fieldX,
        @JsonProperty("y_field") @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> fieldY) {}
