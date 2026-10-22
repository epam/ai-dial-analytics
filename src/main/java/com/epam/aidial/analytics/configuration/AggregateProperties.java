package com.epam.aidial.analytics.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for the aggregate pipeline refresh ({@code aggregate.*}). Values are env-driven via
 * {@code application.yml}.
 *
 * @param enabled     master switch for the scheduler tick; when false, registered pipelines are still
 *                    readable and refreshable on demand but nothing runs on a schedule
 * @param tickCron    how often the scheduler looks for due pipelines. This is the granularity of every
 *                    pipeline's own cron, not a refresh interval — a pipeline asking for a cadence
 *                    finer than the tick simply runs at the tick's rate
 * @param safetyLag   rows newer than {@code now64(3) - safetyLag} are excluded from a refresh window, so
 *                    a refresh never reads the still-mutating recent window and then treats the keys it
 *                    saw there as fully computed
 * @param schedulerPoolSize threads on the aggregate tick's <em>own</em> scheduler, which is separate from
 *                    the pool the blob poller and the data-sync job share. A refresh's duration is
 *                    unbounded by design — it recomputes every dirty key — and on a shared pool that
 *                    occupies a thread ingestion also needs; a late rollup serves a stale number, while
 *                    late ingestion loses ground against its source, so the two do not share a budget.
 *                    The default of 1 reproduces the previous behaviour exactly: refreshes stay serial
 *                    and ticks cannot overlap. Raising it permits overlapping <em>ticks</em> — it does
 *                    <em>not</em> parallelize the refreshes within one tick, which are still run in
 *                    sequence
 * @param windowLimit maximum input rows one refresh probes; a run that fills it reports that more work
 *                    remains and the next tick continues from the advanced cursor
 * @param previewRows rows returned by the dry-run preview sample
 * @param preview     the resource budget the preview sample executes under; see {@link Preview}
 */
@Validated
@ConfigurationProperties(prefix = "aggregate")
public record AggregateProperties(
        boolean enabled,
        @NotNull String tickCron,
        @Min(1) int schedulerPoolSize,
        @NotNull Duration safetyLag,
        @Min(1) int windowLimit,
        @Min(1) int previewRows,
        @NotNull @Valid Preview preview) {

    /**
     * The resource ceiling the dry-run preview's sample query runs under.
     *
     * <p>Preview is deliberately not window-restricted — the question it answers is "are these the right
     * numbers", which a window would obscure — so the sample aggregates the pipeline's <em>entire</em>
     * input on every call. {@code previewRows} bounds what comes back, not what it costs to produce, and a
     * declaration grouping by a high-cardinality wide column builds aggregation state proportional to the
     * whole input — enough, on an input of only tens of thousands of rows, for a
     * {@code group by "dial_usage_log_payload.request_body"} declaration to exhaust ClickHouse and take its host down with it.
     * Preview is the endpoint an operator is expected to call
     * on a declaration nobody has vetted, so it cannot be the one that costs the most to call.
     *
     * @param timeout wall-clock ceiling for the sample query
     * @param maxMemory hard memory ceiling per query, per node. Must sit <em>below</em> the ClickHouse
     *     container's own limit to mean anything: a ceiling above what the container can absorb bounds
     *     nothing, because the container dies first
     * @param spillThreshold aggregation-state size at which the query spills to disk instead of growing.
     *     Strictly below {@code maxMemory}, so a large-but-legitimate preview degrades to slow rather than
     *     being refused, and the refusal is reserved for the pathological case. Needs scratch disk: set
     *     generously on a node with little of it, an OOM is traded for a disk-full
     */
    public record Preview(
            @NotNull Duration timeout,
            @NotNull DataSize maxMemory,
            @NotNull DataSize spillThreshold) implements ResourceBudget {

        public Preview {
            ResourceBudget.validate("aggregate.preview", timeout, maxMemory, spillThreshold);
        }
    }
}
