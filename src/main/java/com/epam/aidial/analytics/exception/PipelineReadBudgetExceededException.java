package com.epam.aidial.analytics.exception;

/**
 * A read ADAS issued on a pipeline's behalf exceeded the resource budget configured for that machinery
 * ({@code pipeline.budget.*}) — an enrichment scan page, a group fetch, or a materialize or refresh window
 * probe. A read the {@code enrich} dry-run preview issued on these same paths is reported as
 * {@link PipelinePreviewBudgetExceededException} instead: the preview translates this refusal at its boundary,
 * since its remedy is the author's rather than the runner's.
 *
 * <p>Mapped to HTTP 422 with the stable code {@code pipeline_read_budget_exceeded}, deliberately
 * <em>not</em> 503, for the reason {@link QueryBudgetExceededException} gives: the refusal is
 * deterministic. The cost of a group fetch's payload read follows the physical layout of the tables it
 * reads, so the identical request is refused identically until the declaration, the layout or the budget
 * changes. A 503 promises a transient condition, and a caller doing the correct thing for a 503 — back off
 * and retry — would loop forever, which on the runner's side means a pipeline that never advances and never
 * says why.
 *
 * <p><b>What the runner should do with it.</b> Dead-letter the item. This is the one resource failure on
 * these endpoints that retrying cannot clear, which is exactly why it carries its own code rather than
 * arriving as a bare {@code bad_request}: a stage whose items all fail this way names a declaration that
 * cannot be served as written, not an outage. Contrast a ClickHouse resource failure with <em>no</em> budget
 * attached, which surfaces as 503 {@code clickhouse_unavailable} and is worth retrying.
 */
public class PipelineReadBudgetExceededException extends RuntimeException {

    /** The ceiling refused, with its configured value (e.g. {@code "the 2GB memory ceiling"}); null when unknown. */
    private final String exceeded;

    /** The read refused, in the runner's vocabulary (e.g. {@code "preview clamp probe"}); null when unknown. */
    private final String what;

    public PipelineReadBudgetExceededException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    public PipelineReadBudgetExceededException(String message, String exceeded, Throwable cause) {
        this(message, exceeded, null, cause);
    }

    public PipelineReadBudgetExceededException(String message, String exceeded, String what, Throwable cause) {
        super(message, cause);
        this.exceeded = exceeded;
        this.what = what;
    }

    /**
     * The ceiling this read exceeded and its configured value, apart from the runner-oriented message, so a
     * surface that reports the refusal to someone else — the dry-run preview, to an author — can name it in its
     * own words without parsing this message. Null when the refusal was raised before any statement ran.
     */
    public String exceeded() {
        return exceeded;
    }

    /**
     * The read that was refused, as the stage named it ({@code "preview sample"}, {@code "group member
     * selection"}, …), apart from the message for the reason {@link #exceeded} gives: a surface issuing several
     * reads — the dry-run preview — can say which of them did not fit. Null when the refusal was not raised by
     * {@code PipelineReadBudget}.
     */
    public String what() {
        return what;
    }
}
