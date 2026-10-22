package com.epam.aidial.analytics.service.enrichment.model;

/**
 * The outcome of one Class-1 materialize window: how many rows ClickHouse reported written, the cursor
 * the caller persists on success, whether more of the backlog remains, and the freshness of the window
 * just processed. Carries no row payload by design — the derived data never leaves ClickHouse.
 *
 * @param rowsWritten rows the {@code INSERT … SELECT} wrote; {@code 0} for an empty window
 * @param nextCursor  the probed window's end tuple, encoded; the input cursor echoed for an empty window
 * @param hasMore     true when the probe filled its limit, i.e. more of the backlog remains
 * @param lagSeconds  {@code server_now − window end}, or null for an empty window
 */
public record MaterializePage(long rowsWritten, String nextCursor, boolean hasMore, Long lagSeconds) {}
