package com.epam.aidial.analytics.exception;

/**
 * A read a dry-run preview issued exceeded the resource budget it runs under: {@code aggregate.preview.*} for an
 * {@code aggregate} sample, {@code pipeline.budget.*} for every read an {@code enrich} preview issues (its sample,
 * the clamp probe, the group choice, the member count and the member read).
 *
 * <p>One code for both kinds, because the condition a client switches on is the same — "this preview was too
 * expensive" — and its remedy is the author's. The {@code enrich} reads share their read paths with the
 * runner, whose refusal is {@link PipelineReadBudgetExceededException}; the preview translates that refusal
 * into this one at its own boundary, so the code follows the surface that issued the read and not the path it
 * shares.
 *
 * <p>Mapped to HTTP 422 with the stable code {@code pipeline_preview_budget_exceeded}, deliberately
 * <em>not</em> 503. The refusal is deterministic: the identical request fails identically until either the
 * declaration or the budget changes. A 503 promises a transient condition that retrying clears, so a
 * client doing the correct thing for a 503 — back off and retry — would loop forever. The actionable fix
 * is on the caller's side of the contract (coarsen the grain, add a membership filter, or have the budget
 * raised), which is what 422 already means across this capability. The distinct code is what keeps it
 * separable from a genuine spec violation and from an outage.
 */
public class PipelinePreviewBudgetExceededException extends RuntimeException {
    public PipelinePreviewBudgetExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
