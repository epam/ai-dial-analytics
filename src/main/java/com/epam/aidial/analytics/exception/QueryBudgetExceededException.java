package com.epam.aidial.analytics.exception;

/**
 * An executed query exceeded the resource budget configured for the query surface ({@code query.budget.*}).
 *
 * <p>Mapped to HTTP 422 with the stable code {@code query_budget_exceeded}, deliberately <em>not</em> 503.
 * The refusal is deterministic: the identical query is refused identically until either the query or the
 * budget changes. A 503 promises a transient condition that retrying clears, so a client doing the correct
 * thing for a 503 — back off and retry — would loop forever. The actionable fix is on the caller's side
 * (narrow the time range, drop a heavy column from the projection, coarsen the grouping, or have the budget
 * raised), which is what 422 already means across this service. The distinct code keeps it separable from a
 * malformed query (400 {@code bad_request}) and from an outage (503
 * {@code clickhouse_unavailable}).
 *
 * <p>This mirrors {@link PipelinePreviewBudgetExceededException} exactly, for the same reasons; the two stay
 * separate codes so an operator can tell "this query is too expensive" from "this pipeline declaration is
 * too expensive to preview".
 */
public class QueryBudgetExceededException extends RuntimeException {
    public QueryBudgetExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
