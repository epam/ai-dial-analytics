package com.epam.aidial.analytics.configuration;

import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * The shape every ClickHouse resource ceiling in this service is declared in: a wall-clock ceiling, a hard
 * memory ceiling, and the aggregation-state size at which a query spills to disk instead of growing.
 *
 * <p>Three capabilities declare one — {@code query.budget.*} for a caller-supplied query,
 * {@code aggregate.preview.*} for the dry-run preview, and {@code pipeline.budget.*} for every read ADAS
 * issues on a pipeline's behalf — with different defaults and different refusals but the same knobs and
 * the same way of failing to be set sensibly. Only the two aggregating capabilities carry a
 * {@link #spillThreshold()}; see that method. This interface is what lets
 * {@link com.epam.aidial.analytics.clickhouse.ClickHouseBudget} render and enforce either one, so a fix to
 * how a ceiling is validated or carried to ClickHouse lands in one place rather than two.
 */
public interface ResourceBudget {

    /** Wall-clock ceiling for the executed statement. */
    Duration timeout();

    /** Hard memory ceiling per query, per node. */
    DataSize maxMemory();

    /**
     * Aggregation-state size at which the query spills to disk; strictly below {@link #maxMemory()}.
     *
     * <p>Null for a budget whose statements do not aggregate, which omits the setting rather than sending
     * a bound ClickHouse would never consult. {@code max_bytes_before_external_group_by} governs
     * {@code GROUP BY} state and nothing else, so a value on such a budget would be a knob an operator
     * could turn during an incident to no effect at all — worse than an absent one.
     */
    DataSize spillThreshold();

    /**
     * Rejects a budget whose values would silently fail to bound anything, at startup rather than at the
     * first query that needed the ceiling.
     *
     * <p>A null {@code spillThreshold} skips the second check: the budget declares no spill bound, which
     * is not the same as declaring an ineffective one.
     *
     * <p>Both checks exist because the misconfiguration they catch looks <em>stricter</em> than the default
     * while being weaker: ClickHouse reads {@code max_execution_time = 0} as unlimited, so a sub-second
     * timeout — which is what {@link Duration#toSeconds()} renders as {@code 0} — turns the wall-clock
     * ceiling off entirely; and a spill threshold at or above the hard ceiling never triggers before the
     * refusal it is meant to avert.
     *
     * <p>Takes the values rather than a {@code ResourceBudget} so an implementing record can call it from
     * its compact constructor, before {@code this} exists.
     *
     * @param prefix the configuration prefix to name in the message ({@code query.budget},
     *     {@code aggregate.preview}, {@code pipeline.budget}), so an operator is told which one to edit
     */
    static void validate(String prefix, Duration timeout, DataSize maxMemory, DataSize spillThreshold) {
        if (timeout.toSeconds() < 1) {
            throw new IllegalArgumentException(prefix + ".timeout (" + timeout
                    + ") must be at least one second; ClickHouse takes max_execution_time=0 as unlimited, so a"
                    + " sub-second ceiling switches the wall-clock bound off rather than tightening it");
        }
        if (spillThreshold != null && spillThreshold.toBytes() >= maxMemory.toBytes()) {
            throw new IllegalArgumentException(prefix + ".spill-threshold (" + spillThreshold
                    + ") must be strictly below " + prefix + ".max-memory (" + maxMemory
                    + "); spilling only helps if it triggers before the hard ceiling");
        }
    }
}
