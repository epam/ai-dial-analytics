package com.epam.aidial.analytics.configuration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The resource ceiling every caller-supplied query executes under ({@code query.budget.*}). Values are
 * env-driven via {@code application.yml}; descriptions live in {@code docs/configuration.md}.
 *
 * <p>The budget exists because a query's cost is not bounded by anything the caller supplies.
 * {@code limit} bounds the rows <em>returned</em>, not the rows <em>read</em>: a {@code heavy} column is
 * unbounded in size per row, and a filter on a column that is not a prefix of the source's ordering key
 * prunes no partitions, so a single small-result query can read every payload column in the table. Without
 * a ceiling that read continues after the caller's HTTP client has given up — the cost is paid in full and
 * the caller learns nothing about why.
 *
 * <p><b>Cost, never result.</b> The ceilings refuse an over-expensive query; they never sample, narrow, or
 * truncate one. A partial answer returned as though it were complete is worse than a refusal, because the
 * caller cannot tell the difference.
 *
 * <p><b>Memory and time are bounded; rows read are not.</b> {@code max_rows_to_read} is rejected as the
 * primary control for the same reason {@link AggregateProperties.Preview} rejects it: it bounds the scan,
 * whereas what a costly query exhausts is memory (aggregation state, or a wide projection's buffers) or
 * patience. Capping the resource that actually runs out keeps a bounded-but-wide query working, which is
 * the common case.
 *
 * <p>The three are validated together by {@link ResourceBudget#validate}, shared with
 * {@link AggregateProperties.Preview}: the ways a ceiling can be set so that it silently bounds nothing do
 * not differ between the two budgets.
 *
 * @param timeout wall-clock ceiling for the caller's request, covering every statement it runs — a row
 *     query and its {@code count(*)} total share it rather than each getting one. Defaulted <em>below</em>
 *     a typical client read timeout on purpose (adas-mcp reads with 30s; browsers and BI tools are in the
 *     same range): a ceiling above the client's own timeout would leave the caller seeing the same empty
 *     transport failure this budget exists to remove, so the server has to give up first and say why
 * @param maxMemory hard memory ceiling per query, per node. Must sit <em>below</em> the ClickHouse
 *     container's own limit to mean anything: a ceiling above what the container can absorb bounds
 *     nothing, because the container dies first
 * @param spillThreshold aggregation-state size at which the query spills to disk instead of growing.
 *     Strictly below {@code maxMemory}, so a large-but-legitimate aggregate degrades to slow rather than
 *     being refused, and the refusal is reserved for the pathological case. Needs scratch disk: set
 *     generously on a node with little of it, an OOM is traded for a disk-full
 */
@Validated
@ConfigurationProperties(prefix = "query.budget")
public record QueryBudgetProperties(
        @NotNull Duration timeout,
        @NotNull DataSize maxMemory,
        @NotNull DataSize spillThreshold) implements ResourceBudget {

    public QueryBudgetProperties {
        ResourceBudget.validate("query.budget", timeout, maxMemory, spillThreshold);
    }
}
