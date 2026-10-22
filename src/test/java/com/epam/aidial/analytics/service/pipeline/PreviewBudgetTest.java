package com.epam.aidial.analytics.service.pipeline;

import com.clickhouse.client.api.ServerException;
import com.epam.aidial.analytics.configuration.AggregateProperties;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.PipelinePreviewBudgetExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The preview budget's two halves: the settings it carries to ClickHouse, and the translation of
 * ClickHouse's refusal back into this capability's own error.
 *
 * <p>The fallback is the case worth testing hardest. Reporting an unrelated ClickHouse failure as a budget
 * refusal would send an operator off to coarsen a declaration that was never the problem, so anything
 * unrecognised has to fall through to the generic ClickHouse-rejection path untouched.
 */
class PreviewBudgetTest {

    private static final AggregateProperties.Preview BUDGET = new AggregateProperties.Preview(
            Duration.ofSeconds(30), DataSize.ofGigabytes(2), DataSize.ofGigabytes(1));

    @Test
    void settingsCarryTheTimeoutMemoryCeilingAndSpillThreshold() {
        final var settings = PreviewBudget.settings(BUDGET).getAllSettings();

        assertThat(settings).containsEntry("clickhouse_setting_max_execution_time", "30");
        assertThat(settings).containsEntry("clickhouse_setting_max_memory_usage", "2147483648");
        // Strictly below the ceiling, so a growing aggregation spills before it is refused — the
        // large-but-legitimate preview degrades to slow rather than failing.
        assertThat(settings).containsEntry("clickhouse_setting_max_bytes_before_external_group_by", "1073741824");
    }

    @Test
    void spillThresholdAtOrAboveTheCeilingIsRejectedAtStartup() {
        assertThat(catching(() -> new AggregateProperties.Preview(
                Duration.ofSeconds(30), DataSize.ofGigabytes(1), DataSize.ofGigabytes(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be strictly below");
    }

    @Test
    void subSecondTimeoutIsRejectedAtStartup() {
        // Shared with the query budget: ClickHouse reads max_execution_time=0 as unlimited and the setting
        // is whole seconds, so a sub-second ceiling switches the wall-clock bound OFF while reading as the
        // strictest setting available.
        assertThat(catching(() -> new AggregateProperties.Preview(
                Duration.ofMillis(500), DataSize.ofGigabytes(2), DataSize.ofGigabytes(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one second")
                .hasMessageContaining("aggregate.preview.timeout");
    }

    @Test
    void timeoutExceededBecomesBudgetRefusal() {
        final RuntimeException translated = PreviewBudget.translate(
                new ClickHouseQueryException("ClickHouse rejected the query",
                        new ServerException(159, "Timeout exceeded", 500, "q1")),
                BUDGET);

        assertThat(translated)
                .isInstanceOf(PipelinePreviewBudgetExceededException.class)
                .hasMessageContaining("execution timeout")
                // Names the remedy on the caller's side of the contract, which is what makes it a 422.
                .hasMessageContaining("coarsen the group keys");
    }

    @Test
    void memoryLimitExceededBecomesBudgetRefusal() {
        final RuntimeException translated = PreviewBudget.translate(
                new ClickHouseQueryException("ClickHouse rejected the query",
                        new ServerException(241, "Code: 241. DB::Exception: Query memory limit exceeded: would use 9.64 MiB "
                                + "(attempt to allocate chunk of 5.00 MiB), maximum: 1.00 KiB", 500, "q2")),
                BUDGET);

        assertThat(translated)
                .isInstanceOf(PipelinePreviewBudgetExceededException.class)
                .hasMessageContaining("memory ceiling");
    }

    /**
     * The same code, a ceiling this service never set — left as the outage it already was. The strings here
     * are hand-written and pin nothing about a real server;
     * {@code ClickHouseMemoryLimitMessageIntegrationTest} holds the wording to what ClickHouse emits.
     */
    @Test
    void memoryCeilingTheServiceDidNotSetIsNotTheBudgetsRefusal() {
        final ClickHouseQueryException original = new ClickHouseQueryException("ClickHouse rejected the query",
                new ServerException(241, "Code: 241. DB::Exception: Total memory limit exceeded: "
                                + "would use 7.45 GiB, maximum: 7.45 GiB", 500, "q4"));

        assertThat(PreviewBudget.translate(original, BUDGET)).isSameAs(original);
    }

    @Test
    void unrecognisedServerErrorFallsThroughUnchanged() {
        // 60 is TABLE_NOT_FOUND — a real failure with a real cause, and nothing to do with cost. It must
        // surface as the generic ClickHouse rejection rather than being relabelled as an expensive
        // declaration.
        final ClickHouseQueryException original = new ClickHouseQueryException("ClickHouse rejected the query",
                new ServerException(60, "Table not found", 500, "q3"));

        assertThat(PreviewBudget.translate(original, BUDGET)).isSameAs(original);
    }

    @Test
    void nonServerFailureFallsThroughUnchanged() {
        final RuntimeException original = new IllegalStateException("connection reset");

        assertThat(PreviewBudget.translate(original, BUDGET)).isSameAs(original);
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
