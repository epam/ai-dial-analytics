package com.epam.aidial.analytics.service.query;

import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseBudget;
import com.epam.aidial.analytics.configuration.QueryBudgetProperties;
import com.epam.aidial.analytics.exception.QueryBudgetExceededException;

/**
 * The resource ceiling a caller-supplied query runs under, and the translation of ClickHouse's refusal into
 * the query surface's own error. The mechanics are {@link ClickHouseBudget}'s, shared with the dry-run
 * preview's budget; what this class owns is the exception the query surface reports and the remedy it names.
 *
 * <p>The budget is applied as per-query settings at the {@link StructuredQueryService} boundary <em>only</em>,
 * through the executor's existing per-call settings seam. The enrichment scan, group-fetch and materialize
 * paths and the aggregate refresh probe share {@code StructuredQueryExecutor} but carry their own ceiling —
 * {@code pipeline.budget.*}, via
 * {@link com.epam.aidial.analytics.service.pipeline.PipelineReadBudget} — because the caller and the remedy
 * differ: this message tells a caller to narrow their query, which is not something the runner can do. They
 * were once left unbudgeted on the reasoning that their own windows and limits bounded them; a group fetch
 * that read one member and consumed the whole server budget is what retired that reasoning.
 *
 * <p><b>Spill before refusing.</b> The spill threshold sits strictly below the hard ceiling, so an
 * aggregate-mode query whose state outgrows it goes to disk and finishes slowly; only one that keeps growing
 * past the ceiling is refused.
 */
final class QueryBudget {

    private QueryBudget() {
    }

    /** The budget as ClickHouse query settings, built from {@code query.budget.*}. */
    static QuerySettings settings(QueryBudgetProperties budget) {
        return ClickHouseBudget.settings(budget);
    }

    /**
     * Translates a ClickHouse refusal into {@link QueryBudgetExceededException} when it is one of the
     * budget's, and rethrows everything else untouched.
     *
     * <p>The message names the ceiling and its configured value so "this query is too expensive" is legible
     * in the response rather than only in a log dive — which matters because "this query got slower" and
     * "this query is refused" are otherwise the same event.
     */
    static RuntimeException translate(RuntimeException ex, QueryBudgetProperties budget) {
        return ClickHouseBudget.translate(ex, budget, (exceeded, cause) ->
                new QueryBudgetExceededException("this query exceeded " + exceeded
                        + " (query.budget.*). A limit bounds the rows returned, not the rows read: "
                        + "narrow the time range so partitions can be pruned, drop heavy columns from "
                        + "the projection, coarsen the grouping, or have the budget raised.", cause));
    }
}
