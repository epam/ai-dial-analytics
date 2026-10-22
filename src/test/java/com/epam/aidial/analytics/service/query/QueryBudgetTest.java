package com.epam.aidial.analytics.service.query;

import com.clickhouse.client.api.ServerException;
import com.epam.aidial.analytics.configuration.QueryBudgetProperties;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.QueryBudgetExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query budget's two halves: the settings it carries to ClickHouse, and the translation of ClickHouse's
 * refusal back into the query surface's own error.
 *
 * <p>Both ceilings are exercised here because only one of them is reachable end to end: the integration test
 * lowers the memory ceiling to trip it, while a wall-clock breach would cost the suite the wall-clock time it
 * is asserting. The mapping from server error code to the ceiling the message names is the part that has to
 * be right either way — a refusal that named the wrong ceiling would send a caller to narrow the wrong thing.
 *
 * <p>The fallback is the case worth testing hardest. Reporting an unrelated ClickHouse failure as a budget
 * refusal would send a caller off to narrow a query that was never the problem, so anything unrecognised has
 * to fall through to the generic ClickHouse-rejection path untouched, keeping its existing status and code.
 */
class QueryBudgetTest {

    private static final QueryBudgetProperties BUDGET = new QueryBudgetProperties(
            Duration.ofSeconds(20), DataSize.ofGigabytes(2), DataSize.ofGigabytes(1));

    @Test
    void settingsCarryTheTimeoutMemoryCeilingAndSpillThreshold() {
        final var settings = QueryBudget.settings(BUDGET).getAllSettings();

        assertThat(settings).containsEntry("clickhouse_setting_max_execution_time", "20");
        assertThat(settings).containsEntry("clickhouse_setting_max_memory_usage", "2147483648");
        // Strictly below the ceiling, so a growing aggregation spills before it is refused — the
        // large-but-legitimate aggregate query degrades to slow rather than failing.
        assertThat(settings).containsEntry("clickhouse_setting_max_bytes_before_external_group_by", "1073741824");
    }

    @Test
    void spillThresholdAtOrAboveTheCeilingIsRejectedAtStartup() {
        assertThat(catching(() -> new QueryBudgetProperties(
                Duration.ofSeconds(20), DataSize.ofGigabytes(1), DataSize.ofGigabytes(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be strictly below");
    }

    @Test
    void subSecondTimeoutIsRejectedAtStartup() {
        // ClickHouse reads max_execution_time=0 as unlimited and the setting is whole seconds, so a
        // sub-second ceiling switches the wall-clock bound OFF while reading as the strictest setting
        // available. Refused at startup, because the alternative is discovering it from a query that never
        // came back.
        assertThat(catching(() -> new QueryBudgetProperties(
                Duration.ofMillis(500), DataSize.ofGigabytes(2), DataSize.ofGigabytes(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one second")
                .hasMessageContaining("query.budget.timeout");
    }

    @Test
    void timeoutExceededBecomesBudgetRefusalNamingTheCeiling() {
        final RuntimeException translated = QueryBudget.translate(
                new ClickHouseQueryException("ClickHouse rejected the query",
                        new ServerException(159, "Timeout exceeded", 500, "q1")),
                BUDGET);

        assertThat(translated)
                .isInstanceOf(QueryBudgetExceededException.class)
                // The ceiling AND its configured value, so "this query got slower" and "this query is
                // refused" are distinguishable from the response alone.
                .hasMessageContaining("execution timeout")
                .hasMessageContaining("PT20S")
                // Names the remedy on the caller's side of the contract, which is what makes it a 422.
                .hasMessageContaining("narrow the time range");
    }

    @Test
    void memoryLimitExceededBecomesBudgetRefusalNamingTheCeiling() {
        final RuntimeException translated = QueryBudget.translate(
                new ClickHouseQueryException("ClickHouse rejected the query",
                        new ServerException(241, "Code: 241. DB::Exception: Query memory limit exceeded: would use 9.64 MiB "
                                + "(attempt to allocate chunk of 5.00 MiB), maximum: 1.00 KiB", 500, "q2")),
                BUDGET);

        assertThat(translated)
                .isInstanceOf(QueryBudgetExceededException.class)
                .hasMessageContaining("memory ceiling")
                // DataSize renders as bytes; what matters is that the configured value is in the message.
                .hasMessageContaining("2147483648B");
    }

    /**
     * The same code, a ceiling this service never set. It must stay the outage it already was, because a
     * query refused by node-wide pressure succeeds unchanged once that pressure clears — telling an
     * interactive caller to narrow its time range would be advice about the wrong thing entirely.
     *
     * <p>The strings in this class are hand-written and pin nothing about a real server;
     * {@code ClickHouseMemoryLimitMessageIntegrationTest} is what holds the wording to the one ClickHouse
     * actually emits.
     */
    @Test
    void memoryCeilingTheServiceDidNotSetIsNotTheBudgetsRefusal() {
        final ClickHouseQueryException original = new ClickHouseQueryException("ClickHouse rejected the query",
                new ServerException(241, "Code: 241. DB::Exception: Total memory limit exceeded: "
                                + "would use 7.45 GiB, maximum: 7.45 GiB", 500, "q4"));

        assertThat(QueryBudget.translate(original, BUDGET)).isSameAs(original);
    }

    @Test
    void unrecognisedServerErrorFallsThroughUnchanged() {
        // 60 is TABLE_NOT_FOUND — a real failure with a real cause, and nothing to do with cost. It must
        // surface as the generic ClickHouse rejection (400) rather than being relabelled as an expensive
        // query the caller should narrow.
        final ClickHouseQueryException original = new ClickHouseQueryException("ClickHouse rejected the query",
                new ServerException(60, "Table not found", 500, "q3"));

        assertThat(QueryBudget.translate(original, BUDGET)).isSameAs(original);
    }

    @Test
    void nonServerFailureFallsThroughUnchanged() {
        // A connectivity failure is already translated to ClickHouseUnavailableException (503) upstream of
        // here; it must keep that mapping rather than becoming a deterministic 422 a client would not retry.
        final RuntimeException original = new IllegalStateException("connection reset");

        assertThat(QueryBudget.translate(original, BUDGET)).isSameAs(original);
    }

    private static Throwable catching(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable ex) {
            return ex;
        }
    }
}
