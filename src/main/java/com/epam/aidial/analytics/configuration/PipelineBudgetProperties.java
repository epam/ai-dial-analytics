package com.epam.aidial.analytics.configuration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The resource ceiling every read ADAS issues on a <em>pipeline's</em> behalf executes under
 * ({@code pipeline.budget.*}): the enrichment scan page, both phases of a group fetch, the materialize and
 * refresh window probes, and every read an {@code enrich} preview issues. Values are env-driven via
 * {@code application.yml}; descriptions live in {@code docs/configuration.md}.
 *
 * <p>These reads were deliberately left unbudgeted at first, on the reasoning that each is bounded by its
 * own window, page size or limit and that a ceiling would refuse a legitimately large window as though it
 * were malformed. That reasoning does not hold, because <b>a row limit bounds the rows returned, not the
 * rows read</b> — the same sentence {@link QueryBudgetProperties} is built on. A group fetch's second phase
 * reads the bound payload columns for the <em>one</em> member the selection policy kept, and on a source
 * whose payload enrichment is keyed on a column that prunes no partitions that single-row read costs
 * gigabytes: on the order of a gigabyte per heavy column, so two heavy columns can exceed a 2 GiB ceiling
 * to return one row. Cost here is a property of the physical layout, not of how many
 * rows the caller asked for, so no limit the pipeline declares can bound it.
 *
 * <p><b>Why this must be bounded even though the caller is trusted.</b> The runner is a first-party caller
 * and its requests are not attacker-controlled, so this is not an abuse ceiling — it is a blast-radius
 * ceiling. Without it one such read consumes whatever the server will give it, and on a node whose
 * {@code max_memory_usage} is unset that is the entire server budget: the observed failure was the
 * ClickHouse pod being OOM-killed by a single group fetch, taking down every other capability with it. With
 * it, the same read is refused in isolation and everything else keeps serving.
 *
 * <p><b>Cost, never result.</b> As on the query surface, the ceilings refuse an over-expensive read; they
 * never sample, narrow or truncate one. A group labelled from a silently narrowed input would be
 * indistinguishable from one labelled from the whole of it.
 *
 * <p><b>Two knobs, not three.</b> {@link ResourceBudget#spillThreshold()} is null here, unlike on the two
 * budgets that aggregate. It renders {@code max_bytes_before_external_group_by}, which bounds
 * {@code GROUP BY} state and nothing else, and <em>none</em> of the six statements this budget covers has a
 * {@code GROUP BY}: the scan, both group-fetch phases and the preview sample are projections, and the two
 * window probes collapse with bare aggregates over no grouping key. A value here would be a configuration
 * knob an operator could turn to no effect. The {@code GROUP BY} that does need bounding belongs to the
 * aggregate refresh write and the aggregate preview sample — the first deliberately unbudgeted, the second
 * carrying {@code aggregate.preview.*}, which keeps its spill threshold.
 *
 * <p>The values are validated by {@link ResourceBudget#validate}, shared with
 * {@link QueryBudgetProperties} and {@link AggregateProperties.Preview}.
 *
 * @param timeout wall-clock ceiling for one such statement. It must sit <b>below the calling client's own
 *     read timeout</b>, or the ceiling cannot do its job: the runner hangs up first and dead-letters on an
 *     opaque socket timeout instead of receiving the {@code 422} that names the ceiling and the remedy,
 *     while ADAS holds the thread and the ClickHouse query for the remainder of the budget on behalf of a
 *     caller that has gone. Sized above the slowest legitimate read rather than against the median: the
 *     median such read takes well under a second, but a Class-1 materialize write can legitimately take
 *     tens of seconds, and a ceiling under that cost refuses an honest write rather than protecting the
 *     node. The default carries several times that headroom, so a read still running at the
 *     ceiling is itself the signal. Note that a group fetch issues one statement to select its members and
 *     one per {@code heavy} column its declaration binds, each under its own ceiling, so one request can
 *     occupy this value times one more than that count — which is the figure the caller's read timeout has
 *     to clear
 * @param maxMemory hard memory ceiling per statement, per node. Must sit below the ClickHouse container's
 *     own limit, and above the heaviest read a healthy pipeline legitimately issues — a partition-aligned
 *     payload scan tick can still peak above a gigabyte, so a ceiling picked to let the
 *     runner's whole scheduler thread pool run at once would refuse the pipeline rather than protect the
 *     node. The product of this value and that pool is what bounds the node, and where it exceeds the
 *     server's own {@code max_server_memory_usage} it is the concurrency that has to come down: the server
 *     then refuses the marginal statement, and
 *     {@link com.epam.aidial.analytics.service.pipeline.PipelineReadBudget} reports that as this ceiling
 */
@Validated
@ConfigurationProperties(prefix = "pipeline.budget")
public record PipelineBudgetProperties(
        @NotNull Duration timeout,
        @NotNull DataSize maxMemory) implements ResourceBudget {

    public PipelineBudgetProperties {
        ResourceBudget.validate("pipeline.budget", timeout, maxMemory, null);
    }

    /** Null: no statement this budget covers aggregates, so there is no {@code GROUP BY} state to spill. */
    @Override
    public DataSize spillThreshold() {
        return null;
    }
}
