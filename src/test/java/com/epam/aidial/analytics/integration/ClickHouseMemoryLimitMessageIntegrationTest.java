package com.epam.aidial.analytics.integration;

import com.clickhouse.client.api.ServerException;
import com.epam.aidial.analytics.clickhouse.ClickHouseBudget;
import com.epam.aidial.analytics.clickhouse.ClickHouseClientWrapper;
import com.epam.aidial.analytics.configuration.ResourceBudget;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real server's own wording for a <b>per-query</b> memory breach, pinned against the ClickHouse this
 * suite runs.
 *
 * <p>This is the test that makes reading the message defensible. {@link ClickHouseBudget} attributes error
 * code 241 from the message text, because the code alone cannot say <em>whose</em> ceiling was reached: the
 * per-query ceiling ADAS set is deterministic and must be refused, while the node running out of memory
 * under unrelated load is transient and must be retried. Matching on text contradicts the standing rule
 * that only codes are stable across versions and locales, so the exception is bounded to that one question
 * and pinned here — a server that renames the wording fails this test rather than silently re-pointing
 * every capacity refusal at the wrong status.
 *
 * <p>The ceiling is passed as {@code QuerySettings} rather than through a {@code @TestPropertySource},
 * deliberately: a property override is part of the test-context cache key, so declaring one here would fork
 * a second Spring context and a second pool of ClickHouse connections for a single assertion.
 *
 * <p>Its sibling for the node-wide form needs a container of its own, since starving the shared singleton
 * would turn one failing test into a failing suite.
 *
 * <p>Requires a running Docker daemon.
 */
class ClickHouseMemoryLimitMessageIntegrationTest extends AbstractClickHouseIntegrationTest {

    /**
     * Aggregation state is what a memory ceiling actually bounds, and {@code numbers()} needs no fixture.
     * A narrow projection allocates below ClickHouse's {@code max_untracked_memory} (4 MiB) and is never measured
     * against the ceiling at all, however low the ceiling is set — so the query has to build a hash table
     * large enough to be tracked before it can be refused.
     */
    private static final String EXHAUSTING_QUERY =
            "SELECT number, count() FROM numbers(100000) GROUP BY number";

    /** A ceiling no real query can meet. Spill is null: the refusal must be the ceiling, not a spill failure. */
    private static final ResourceBudget TINY = new Tiny(Duration.ofSeconds(30), DataSize.ofKilobytes(1));

    @Autowired
    private ClickHouseClientWrapper clickHouse;

    @Test
    void perQueryBreachNamesNoNodeWideLimitAndIsClaimedByUs() {
        final ServerException server = refuse();
        final String message = server.getMessage();

        assertThat(server.getCode())
                .as("MEMORY_LIMIT_EXCEEDED, message was: %s", message)
                .isEqualTo(241);
        assertThat(message)
                .as("the exact head ClickHouseBudget anchors on, message was: %s", message)
                .contains("DB::Exception: Query memory limit exceeded")
                .doesNotContain("User memory limit exceeded")
                .doesNotContain("Total memory limit exceeded");
        assertThat(message)
                .as("the ceiling this query actually ran under, message was: %s", message)
                .contains("maximum: 1.00 KiB");
    }

    /**
     * The end the classifier cares about: this exact exception, handed to {@link ClickHouseBudget}, must be
     * claimed as the budget's own refusal rather than left as the outage the raw code would imply.
     */
    @Test
    void thePerQueryBreachIsClaimedByTheBudgetThatSetTheCeiling() {
        final ServerException server = refuse();
        final RuntimeException translated = ClickHouseBudget.translate(
                new IllegalStateException("wrapper", server), TINY,
                (exceeded, cause) -> new IllegalArgumentException("refused: " + exceeded, cause));

        assertThat(translated)
                .as("message was: %s", server.getMessage())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(translated.getMessage()).contains("refused: ").contains("memory ceiling");
    }

    /**
     * A refused statement must reach the service <b>once</b>.
     *
     * <p>The ClickHouse driver ships {@code retry=3} with {@code ServerRetryable} among its fault causes,
     * and {@code ServerException#isRetryable()} is true for {@code MEMORY_LIMIT_EXCEEDED} — so out of the
     * box every refusal is executed four times below the service, where no budget can see it. Under memory
     * pressure that aims three more heavy statements at a node that has just run out of memory.
     * {@code ClickHouseFailures.transportFaults()} removes that cause, and this is the only assertion that
     * the removal is actually <em>wired</em>: the two unit tests around it can only check the constant.
     *
     * <p>Counted through {@code system.query_log} rather than by instrumenting the client, because what
     * matters is how many times the statement reached the server. A per-run marker in a SQL comment keeps
     * the count scoped to this invocation — the log is shared with every other test in the suite.
     */
    @Test
    void refusedStatementReachesTheServerOnce() {
        final String marker = UUID.randomUUID().toString();

        refuse(EXHAUSTING_QUERY + " /* " + marker + " */");
        clickHouse.query("SYSTEM FLUSH LOGS");

        final Object executions = clickHouse.query(
                        "SELECT count() AS n FROM system.query_log WHERE exception_code = 241"
                                + " AND type IN ('ExceptionBeforeStart', 'ExceptionWhileProcessing')"
                                + " AND position(query, '" + marker + "') > 0")
                .getFirst().getString("n");

        assertThat(Long.parseLong(String.valueOf(executions)))
                .as("a server-side refusal must not be replayed by the driver before the service sees it")
                .isEqualTo(1L);
    }

    /** Runs the exhausting query under {@link #TINY} and returns the {@link ServerException} underneath. */
    private ServerException refuse() {
        return refuse(EXHAUSTING_QUERY);
    }

    /** As {@link #refuse()}, for a statement carrying a per-run marker. */
    private ServerException refuse(String sql) {
        final Throwable thrown = assertThatThrownBy(
                () -> clickHouse.query(sql, ClickHouseBudget.settings(TINY)))
                .isInstanceOf(ClickHouseUnavailableException.class)
                .actual();

        for (Throwable cause = thrown; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof ServerException server) {
                return server;
            }
        }
        throw new AssertionError("no ServerException in the cause chain of " + thrown);
    }

    private record Tiny(Duration timeout, DataSize maxMemory) implements ResourceBudget {
        @Override
        public DataSize spillThreshold() {
            return null;
        }
    }
}
