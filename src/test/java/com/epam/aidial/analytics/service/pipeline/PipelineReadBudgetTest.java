package com.epam.aidial.analytics.service.pipeline;

import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseBudget;
import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.configuration.ResourceBudget;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.exception.PipelineReadBudgetExceededException;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Whose ceiling a pipeline read's memory refusal names — on the path where getting it wrong destroys work.
 *
 * <p>This is the translation with the largest blast radius of the three that share
 * {@code ClickHouseBudget}. The Enrichment Runner treats 4xx as permanent by contract, so a refusal
 * reported as the budget's own becomes a dead letter rather than a retry. That is right when the ceiling
 * really is ours — the identical read is refused identically until the declaration, the layout or the
 * budget changes — and destroys work when it is not: node-wide pressure clears on its own, and the
 * discarded read was never too expensive. Under node-wide pressure dozens of refusals can arrive within
 * minutes, and none of them is this ceiling.
 *
 * <p>The messages here are hand-written and pin nothing about a real server;
 * {@code ClickHouseMemoryLimitMessageIntegrationTest} holds the wording to what ClickHouse emits.
 */
class PipelineReadBudgetTest {

    private static final PipelineBudgetProperties BUDGET =
            new PipelineBudgetProperties(Duration.ofSeconds(120), DataSize.ofGigabytes(2));

    private static final String OWN_CEILING = "Code: 241. DB::Exception: Query memory limit exceeded: "
            + "would use 2.01 GiB (attempt to allocate chunk of 4.00 MiB), maximum: 2.00 GiB";

    private static final String NODE_CEILING = "Code: 241. DB::Exception: Total memory limit exceeded: "
            + "would use 7.45 GiB, maximum: 7.45 GiB";

    /**
     * Our own ceiling, so the refusal is the pipeline's to act on: 422, naming the stage and the remedies
     * that apply to a read whose {@code limit} is already at its smallest.
     */
    @Test
    void ourOwnCeilingIsRefusedWithTheStageAndTheRemedy() {
        final RuntimeException translated = PipelineReadBudget.refusal(wrapped(OWN_CEILING), "group fetch", BUDGET);

        assertThat(translated)
                .isInstanceOf(PipelineReadBudgetExceededException.class)
                .hasMessageContaining("this group fetch exceeded")
                .hasMessageContaining("memory ceiling")
                .hasMessageContaining("2147483648B")
                .hasMessageContaining("pipeline.budget.*");
        // The stage is also held apart from the message, for a surface that names the refused read in its own words.
        assertThat(((PipelineReadBudgetExceededException) translated).what()).isEqualTo("group fetch");
    }

    /**
     * The defect. A ceiling the service never set is handed back untouched, so the
     * {@link ClickHouseUnavailableException} the caller already wrapped it in survives as the 503 — and the
     * runner retries on its backoff ladder instead of archiving the work.
     */
    @Test
    void ceilingTheServiceDidNotSetStaysTheOutageTheCallerWrapped() {
        final RuntimeException wrapped = wrapped(NODE_CEILING);

        assertThat(PipelineReadBudget.refusal(wrapped, "group fetch", BUDGET)).isSameAs(wrapped);
    }

    /** The timeout arm is decided by its code alone; only the memory code is ambiguous about whose it is. */
    @Test
    void theExecutionTimeoutIsStillRefusedWhateverItSays() {
        final RuntimeException translated = PipelineReadBudget.refusal(
                new ClickHouseUnavailableException("ClickHouse is unavailable",
                        new ServerException(159, "anything at all", 500, "q")),
                "enrichment scan", BUDGET);

        assertThat(translated)
                .isInstanceOf(PipelineReadBudgetExceededException.class)
                .hasMessageContaining("this enrichment scan exceeded")
                .hasMessageContaining("execution timeout");
    }

    /** How the refusal actually arrives: the executors classify by code first, then the budget re-reads it. */
    private static RuntimeException wrapped(String message) {
        return new ClickHouseUnavailableException("ClickHouse is unavailable",
                new ServerException(241, message, 500, "q"));
    }

    /**
     * Each statement is its own row in {@code system.query_log}: the same tag run twice — a probe and its
     * narrowing re-probe — mints two ids, and the budget it was handed is what the settings carry.
     */
    @Test
    void eachRunMintsFreshQueryIdUnderTheBudgetItWasGiven() {
        final StructuredQueryExecutor executor = mock(StructuredQueryExecutor.class);
        final StatementTag tag = new StatementTag("p1", "reprobe");
        final ResourceBudget remaining = ClickHouseBudget.remaining(BUDGET, Duration.ofSeconds(100));
        try {
            PipelineReadBudget.run(executor, null, null, "materialize window probe", BUDGET, tag);
            PipelineReadBudget.run(executor, null, null, "materialize window probe", remaining, tag);

            final ArgumentCaptor<QuerySettings> settings = ArgumentCaptor.forClass(QuerySettings.class);
            verify(executor, times(2)).executeScan(any(), settings.capture(), any());
            final List<String> ids = settings.getAllValues().stream().map(QuerySettings::getQueryId).toList();
            assertThat(ids).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).startsWith("adas-p1-reprobe-"));
            assertThat(settings.getAllValues().get(1).getAllSettings())
                    .containsEntry("clickhouse_setting_max_execution_time", "20");
            assertThat(StatementTag.current()).isEqualTo(ids.get(1));
        } finally {
            StatementTag.clear();
        }
    }
}
