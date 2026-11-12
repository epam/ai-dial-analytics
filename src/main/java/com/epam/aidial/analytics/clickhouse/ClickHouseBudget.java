package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.ClientConfigProperties;
import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.command.CommandSettings;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.configuration.ResourceBudget;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.function.BiFunction;

/**
 * Renders a {@link ResourceBudget} as ClickHouse query settings, and translates ClickHouse's refusal back
 * into whatever error the calling capability wants to report.
 *
 * <p>Both halves are shared because every capability that declares a budget — the query surface, the
 * dry-run preview, and the pipeline-internal reads — bounds the same resources against the same server
 * error codes; only the exception and the remedy sentence differ, which is what the {@code refusal}
 * factory supplies.
 *
 * <p><b>Memory and time are bounded; rows read are not.</b> {@code max_rows_to_read} is deliberately not
 * used: it bounds the scan, whereas what a costly query exhausts is memory (aggregation state, or a wide
 * projection's buffers) or patience. Capping the resource that actually runs out keeps a bounded-but-wide
 * query working, which is the common case.
 */
public final class ClickHouseBudget {

    /** {@code TIMEOUT_EXCEEDED} — the wall-clock ceiling. */
    private static final int TIMEOUT_EXCEEDED = 159;

    /** {@code MEMORY_LIMIT_EXCEEDED} — a memory ceiling, not necessarily ours; see {@link #ownCeiling}. */
    private static final int MEMORY_LIMIT_EXCEEDED = 241;

    /** The driver's own prefix, which every server message sits behind; the anchor for {@link #ownCeiling}. */
    private static final String DB_EXCEPTION = "DB::Exception: ";

    /** How ClickHouse describes the per-query memory tracker — the only ceiling this service sets. */
    private static final String PER_QUERY_MEMORY_LIMIT = "Query memory limit exceeded";

    /**
     * The key {@link QuerySettings#serverSetting} files {@code max_execution_time} under, so
     * {@link #remainingTime} can find and lower it without re-deriving the client's prefix by hand.
     */
    private static final String MAX_EXECUTION_TIME = ClientConfigProperties.serverSetting("max_execution_time");

    /** The key a {@link QuerySettings#setQueryId query id} is filed under, so {@link #remainingTime} can drop it. */
    private static final String QUERY_ID = ClientConfigProperties.QUERY_ID.getKey();

    private ClickHouseBudget() {
    }

    /**
     * The budget as ClickHouse query settings.
     *
     * <p>The spill setting is emitted only when the budget declares one. {@code
     * max_bytes_before_external_group_by} bounds {@code GROUP BY} state alone, so for a budget over
     * statements that never group it would be inert — and an inert setting reads to an operator as a
     * control they have.
     */
    public static QuerySettings settings(ResourceBudget budget) {
        final QuerySettings settings = new QuerySettings()
                .serverSetting("max_execution_time", String.valueOf(budget.timeout().toSeconds()))
                .serverSetting("max_memory_usage", String.valueOf(budget.maxMemory().toBytes()));
        if (budget.spillThreshold() != null) {
            settings.serverSetting("max_bytes_before_external_group_by",
                    String.valueOf(budget.spillThreshold().toBytes()));
        }
        return settings;
    }

    /**
     * The same ceilings on a {@link CommandSettings}, for the budgeted statements that are not queries: the
     * Class-1 materialize and aggregate-refresh {@code INSERT … SELECT}.
     *
     * <p>A separate overload rather than a shared abstraction because the two settings types share no
     * interface in the driver, and the thing worth keeping single-sourced is the <em>setting names</em> —
     * spelling {@code max_memory_usage} in a second place is how a ceiling comes to be configured on one
     * path and silently absent on another. The spill threshold is not emitted here, so the refresh write's
     * {@code GROUP BY} does not spill: its memory is the grain join, not a group count bounded by the
     * window.
     */
    public static CommandSettings apply(CommandSettings settings, ResourceBudget budget) {
        // Not chained: the driver's fluent setter is declared on the shared base and answers with the query
        // settings type, so chaining here would not typecheck against the command settings it mutates.
        settings.serverSetting("max_execution_time", String.valueOf(budget.timeout().toSeconds()));
        settings.serverSetting("max_memory_usage", String.valueOf(budget.maxMemory().toBytes()));
        return settings;
    }

    /**
     * {@code settings} with its wall-clock ceiling reduced by {@code elapsed}, for a second statement run on
     * behalf of the same request.
     *
     * <p>The ceiling bounds what one <em>caller</em> waits, not what one statement takes: a request that
     * runs two statements — a row query and its {@code count(*)} total — would otherwise occupy twice the
     * configured time and hand the caller exactly the transport failure past its own read timeout that the
     * budget exists to remove. The remainder floors at one second rather than zero, because ClickHouse
     * reads {@code max_execution_time = 0} as unlimited: a budget already spent must still refuse promptly,
     * not run unbounded.
     *
     * <p>Settings that carry no wall-clock ceiling and no query id are returned unchanged, so this is inert
     * wherever neither was attached.
     *
     * <p>The result never carries a query id, with or without a ceiling: the second statement is a statement of
     * its own, and one that reused the first one's id would make the two indistinguishable in
     * {@code system.query_log}. A caller that tags the second statement sets its id on the result (see
     * {@link StatementTag}).
     */
    public static QuerySettings remainingTime(QuerySettings settings, Duration elapsed) {
        final Object configured = settings.getOption(MAX_EXECUTION_TIME);
        if (configured == null && settings.getQueryId() == null) {
            return settings;
        }
        final QuerySettings reduced = new QuerySettings();
        settings.getAllSettings().forEach(reduced::setOption);
        reduced.resetOption(QUERY_ID);
        if (configured == null) {
            return reduced;
        }
        // Whole seconds left, rounded down: truncating the elapsed time instead would grant up to a second more than is left.
        final long remaining = Duration.ofSeconds(Long.parseLong(configured.toString())).minus(elapsed).toSeconds();
        return reduced.setOption(MAX_EXECUTION_TIME, String.valueOf(Math.max(1L, remaining)));
    }

    /**
     * As {@link #remainingTime}, but {@code null} when {@code settings} carry a wall-clock ceiling of which less than one
     * second is left after {@code elapsed}: for a statement the caller may skip rather than run, so a spent budget stops
     * the sequence instead of granting each further statement {@link #remainingTime}'s one-second floor.
     */
    public static QuerySettings remainingTimeIfAny(QuerySettings settings, Duration elapsed) {
        final Object configured = settings.getOption(MAX_EXECUTION_TIME);
        if (configured != null && Duration.ofSeconds(Long.parseLong(configured.toString())).minus(elapsed).toMillis() < 1000) {
            return null;
        }
        return remainingTime(settings, elapsed);
    }

    /**
     * {@code budget} with only the time it has left after {@code elapsed}, for a sequence of statements run
     * on one caller's behalf — a write that is refused, narrowed and retried.
     *
     * <p>Without this the ceiling would bound each attempt rather than the call, and a narrowing sequence
     * would occupy a multiple of the configured time. That is not an abstract overrun: the caller has its
     * own read timeout, and a server that keeps working past it finishes into a closed socket, which the
     * caller sees as a transport failure and retries — the livelock this budget exists to replace with a
     * clean refusal.
     *
     * <p>Returns {@code null} when nothing useful is left, which the caller reports as the refusal rather
     * than starting an attempt it cannot finish. The threshold is one second because ClickHouse reads
     * {@code max_execution_time = 0} as <em>unlimited</em>: a budget rendered as zero would remove the
     * ceiling at exactly the moment it is most needed.
     */
    public static ResourceBudget remaining(ResourceBudget budget, Duration elapsed) {
        final Duration left = budget.timeout().minus(elapsed);
        return left.toSeconds() < 1 ? null
                : new Remaining(left, budget.maxMemory(), budget.spillThreshold(), configuredTimeout(budget));
    }

    /**
     * A {@link ResourceBudget} view carrying a reduced wall-clock ceiling and the original's memory ones, plus the
     * configured timeout it was cut from, so a refusal still names the ceiling an operator can change.
     */
    private record Remaining(Duration timeout, DataSize maxMemory, DataSize spillThreshold, Duration configured)
            implements ResourceBudget {}

    /** The timeout {@code budget} was configured with, before any statement of its call spent part of it. */
    private static Duration configuredTimeout(ResourceBudget budget) {
        return budget instanceof Remaining remaining ? remaining.configured() : budget.timeout();
    }

    /**
     * How a refusal names the wall-clock ceiling: the configured value, and what was left of it when the refused
     * statement ran on a remainder. A remainder alone ({@code PT43.217S}) is no value anyone configured.
     */
    private static String describeTimeout(ResourceBudget budget) {
        return budget instanceof Remaining remaining
                ? "the " + remaining.configured() + " execution timeout (" + remaining.timeout().toSeconds()
                        + "s of it left for this statement)"
                : "the " + budget.timeout() + " execution timeout";
    }

    /**
     * Translates a ClickHouse refusal into the capability's own error when it is one of the budget's, and
     * rethrows everything else untouched.
     *
     * <p>Matching is on the numeric server error code rather than on message text, which is the less brittle
     * of the two. Anything unrecognised falls through to the {@code ClickHouseQueryException} → 400 path, so
     * a driver or version change that stops these codes being recognised costs the specific error and
     * nothing more — a budget refusal reported for an unrelated failure would send a caller to narrow a
     * query that was never the problem.
     *
     * <h2>The one code that needs its message read</h2>
     *
     * <p>{@code MEMORY_LIMIT_EXCEEDED} is ambiguous by construction: ClickHouse reports the per-query
     * ceiling <em>this service set</em> and a ceiling somebody else set — the node's, the connecting
     * account's — under the same code. The two need opposite answers. Ours is deterministic, so the caller
     * must stop (422, and {@link ClickHouseFailures} is overridden). Anybody else's depends on what is
     * executing alongside this statement, so the identical statement succeeds once that clears, and the
     * 503 {@code ClickHouseFailures} already assigned must stand — a caller told 4xx treats it as
     * permanent and discards work that was never too expensive, so that misreport turns every transient
     * refusal under memory pressure into a dead letter.
     *
     * <p>So this one code is decided by {@link #ownCeiling(String)} reading the message, which is a bounded
     * exception to the paragraph above and not a licence to read messages elsewhere.
     *
     * @param refusal builds the capability's exception from a phrase naming the ceiling that was exceeded
     *     (with its configured value) and the original cause
     */
    public static RuntimeException translate(
            RuntimeException ex, ResourceBudget budget, BiFunction<String, Throwable, RuntimeException> refusal) {
        for (Throwable cause = ex; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof ServerException server) {
                final String exceeded = switch (server.getCode()) {
                    case TIMEOUT_EXCEEDED -> describeTimeout(budget);
                    case MEMORY_LIMIT_EXCEEDED -> ownCeiling(server.getMessage())
                            ? "the " + budget.maxMemory() + " memory ceiling"
                            : null;
                    default -> null;
                };
                if (exceeded != null) {
                    return refusal.apply(exceeded, ex);
                }
            }
        }
        return ex;
    }

    /**
     * Whether a {@code MEMORY_LIMIT_EXCEEDED} message names the per-query ceiling <em>this service set</em>,
     * as opposed to a ceiling something else set.
     *
     * <p><b>Identifies our own ceiling rather than enumerating the alternatives</b>, which is what keeps
     * this correct as ClickHouse gains limits. The service sets exactly one memory ceiling, so "is it ours"
     * has a single answer, while "is it somebody else's" is an open-ended list — and a list is only correct
     * while it is exhaustive. Everything unmatched keeps the outage {@link ClickHouseFailures} already
     * classified it as.
     *
     * <p><b>Both directions of a mismatch cost something.</b> A ceiling the service did not set, reported
     * as ours, is a 4xx: a first-party caller treats it as permanent and discards work that was never too
     * expensive. Our own ceiling reported as an outage is a retry loop against a statement that cannot
     * succeed. The unmatched case takes the second, because it is the loud one — a loop shows up in the 503
     * rate and in the caller's retry metrics within minutes, where a dead letter is silent — and because
     * the form that must keep working is the one a test can provoke against a real server, so it stays
     * pinned while the other cannot be.
     *
     * <p><b>Anchored, not searched.</b> The description is matched immediately after the <em>last</em>
     * {@code DB::Exception: } in the message, so a caller-supplied alias or literal that happens to contain
     * this wording cannot turn its own deterministic refusal into a retryable one — ClickHouse can append
     * the offending query to a message. The last marker rather than the first because a forwarded error
     * nests ({@code … DB::Exception: Received from host. DB::Exception: <the real description>}), and the
     * description naming the limit is the innermost one.
     *
     * <p><b>One case it cannot distinguish.</b> A server profile or quota can impose
     * {@code max_memory_usage} on the account the service connects as, which renders identically to the
     * ceiling the service set. That is still deterministic, so 422 stays right; only the value the message
     * names is then not the one that was enforced.
     *
     * <p><b>Verified against 26.3.21.7 and 26.8.15.10</b> by hand, and by the suite against whatever patch
     * {@code ClickHouseContainerSupport}'s floating {@code 26.8} tag resolves to — so the suite moves with 26.8
     * patch releases rather than pinning one — where the
     * two forms read {@code Query memory limit exceeded: …} and {@code User memory limit exceeded: …}. Only
     * this head is matched: everything after {@code exceeded:} — the sizes, the chunk clause, the trailing
     * context — is known to change between server versions. A server upgrade must re-check this constant;
     * {@code ClickHouseMemoryLimitMessageIntegrationTest} fails the build if it moves.
     */
    private static boolean ownCeiling(String message) {
        if (message == null) {
            return false;
        }
        final int marker = message.lastIndexOf(DB_EXCEPTION);
        return marker >= 0 && message.startsWith(PER_QUERY_MEMORY_LIMIT, marker + DB_EXCEPTION.length());
    }
}
