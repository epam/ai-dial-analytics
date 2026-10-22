package com.epam.aidial.analytics.service.pipeline;

import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseBudget;
import com.epam.aidial.analytics.configuration.AggregateProperties;
import com.epam.aidial.analytics.exception.PipelinePreviewBudgetExceededException;

/**
 * The resource ceiling the dry-run preview's sample runs under, and the translation of ClickHouse's
 * refusal into this capability's own error. The mechanics are {@link ClickHouseBudget}'s, shared with the
 * query surface's budget; what this class owns is the exception the preview reports and the remedy it names.
 *
 * <p>The budget is applied as per-query settings on the aggregate preview's sample statement <em>only</em>;
 * an {@code enrich} preview's reads run under {@code pipeline.budget.*} and report the same
 * {@code pipeline_preview_budget_exceeded} code.
 * The refresh write is untouched: it is bounded by the window its probe already fixed, and a ceiling there
 * would make a legitimate large window fail as though it were malformed. The refresh <em>probe</em> and the
 * enrichment scan paths carry {@code pipeline.budget.*} instead (see {@link PipelineReadBudget}), which is a
 * blast-radius bound on reads the runner drives rather than a bound on what an operator's dry run may cost.
 *
 * <p><b>Memory and time are bounded; rows read are not.</b> {@code max_rows_to_read} is rejected as the
 * primary control: it bounds the scan, whereas the resource a costly preview exhausts is aggregation
 * state. {@code group by deployment} over a very large input is cheap in memory and a scan cap would
 * refuse it, while {@code group by "dial_usage_log_payload.request_body"} over a small one is ruinous and a scan cap would let it
 * through.
 *
 * <p><b>Spill before refusing.</b> The spill threshold sits strictly below the hard ceiling, so an
 * aggregation whose state outgrows it goes to disk and finishes slowly; only one that keeps growing past
 * the ceiling is refused. That gives the large-but-legitimate declaration a way to succeed and reserves
 * the error for the pathological one — which matters because the operator running a preview usually
 * cannot tell in advance which they have.
 */
final class PreviewBudget {

    private PreviewBudget() {
    }

    /** The budget as ClickHouse query settings, built from {@code aggregate.preview.*}. */
    static QuerySettings settings(AggregateProperties.Preview budget) {
        return ClickHouseBudget.settings(budget);
    }

    /**
     * Translates a ClickHouse refusal into {@link PipelinePreviewBudgetExceededException} when it is one of
     * the budget's, and rethrows everything else untouched.
     *
     * <p>The message names the ceiling and its configured value, so an operator can tell a preview that is
     * too expensive from one that failed for an unrelated reason without a log dive.
     */
    static RuntimeException translate(RuntimeException ex, AggregateProperties.Preview budget) {
        return ClickHouseBudget.translate(ex, budget, (exceeded, cause) ->
                new PipelinePreviewBudgetExceededException("this pipeline's preview exceeded "
                        + exceeded + " (aggregate.preview.*). The sample aggregates the whole input by "
                        + "design and counts every group it produces, holding them all in memory, so its "
                        + "cost follows the declaration's grain. The likely cause is a group key of far "
                        + "higher cardinality than meant: coarsen the group keys, add a membership filter, "
                        + "or have the budget raised.", cause));
    }
}
