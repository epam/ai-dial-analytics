package com.epam.aidial.analytics.service.pipeline.model;

/**
 * The result of one refresh run.
 *
 * @param rowsWritten group-key rows ClickHouse reports written — one per recomputed key, not one per
 *                    input row, since the statement aggregates
 * @param hasMore     whether the probe filled its window limit, meaning input remains for the next run.
 *                    An operator draining a backlog refreshes until this is false
 * @param lagSeconds  how far behind the input the rollup now is, derived from the advanced cursor
 */
public record RefreshOutcome(long rowsWritten, boolean hasMore, long lagSeconds) {}
