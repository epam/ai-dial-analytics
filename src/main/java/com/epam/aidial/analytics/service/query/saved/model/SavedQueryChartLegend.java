package com.epam.aidial.analytics.service.query.saved.model;

/**
 * How the client shows a chart's legend. A rendering preference like the rest of {@link SavedQueryChart}:
 * stored and returned as sent, apart from Jackson's default scalar coercion ({@code "false"} is stored as {@code false},
 * a number {@code position} as its string), with no member required.
 *
 * <p>{@code position} is a free string. The client knows {@code above}, {@code right}, {@code below} and
 * {@code left} and renders anything else as {@code below}, so ADAS never checks it against that list, the same
 * way {@code type} is never checked. {@code visible} is boxed so an absent value is omitted rather than written
 * as {@code false}.
 */
public record SavedQueryChartLegend(Boolean visible, String position) {}
