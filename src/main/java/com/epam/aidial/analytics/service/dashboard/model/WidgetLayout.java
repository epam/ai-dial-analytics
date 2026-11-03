package com.epam.aidial.analytics.service.dashboard.model;

/**
 * A widget's place on the 12-column grid, as validated: {@code x + w <= 12}. {@code y} and {@code h} have
 * no upper bound, so they are {@code long}s rather than a range the spec does not impose.
 */
public record WidgetLayout(int x, long y, int w, long h) {}
