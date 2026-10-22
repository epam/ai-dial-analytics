package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.command.CommandSettings;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.observability.ClientSpanTracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The write-side half of the deduplication contract: an {@code INSERT … SELECT} carries {@code final = 1}
 * always, and {@code do_not_merge_across_partitions_select_final = 1} exactly when the caller vouches for
 * the input entity being partition-local — the same rule the read path applies, from the same
 * {@link FinalSettings}, so a Class-1 materialize never dedupes differently from the query that would read
 * the rows it derives from.
 */
@ExtendWith(MockitoExtension.class)
class ClickHouseInsertSelectExecutorTest {

    private static final String SQL = "INSERT INTO t (k) SELECT k FROM s";

    private static final PipelineBudgetProperties BUDGET =
            new PipelineBudgetProperties(Duration.ofSeconds(30), DataSize.ofGigabytes(2));

    private static final StatementTag TAG = new StatementTag("p1", "write");

    @Mock
    private ClickHouseWriterClient writer;
    @Mock
    private Client client;

    private CommandSettings settingsSentFor(boolean partitionLocal) {
        final CommandResponse response = mock(CommandResponse.class);
        given(response.getWrittenRows()).willReturn(3L);
        given(writer.client()).willReturn(client);
        given(client.execute(eq(SQL), any(CommandSettings.class))).willReturn(CompletableFuture.completedFuture(response));

        final long written = new ClickHouseInsertSelectExecutor(writer, TestClickHouseSpans.noop())
                .insertSelect("t", SQL, partitionLocal, BUDGET, TAG);

        assertThat(written).isEqualTo(3L);
        final ArgumentCaptor<CommandSettings> settings = ArgumentCaptor.forClass(CommandSettings.class);
        verify(client).execute(eq(SQL), settings.capture());
        return settings.getValue();
    }

    @Test
    void partitionLocalWriteDeduplicatesInsideEachPartition() {
        assertThat(settingsSentFor(true).getAllSettings())
                .containsEntry(FinalSettings.FINAL_KEY, "1")
                .containsEntry(FinalSettings.NO_MERGE_ACROSS_PARTITIONS_KEY, "1");
    }

    @Test
    void pipelineWritePinsNullsOutOfIn() {
        // The aggregate recompute's dirty-key restriction is exact only while a NULL never satisfies IN.
        assertThat(settingsSentFor(true).getAllSettings()).containsEntry(FinalSettings.TRANSFORM_NULL_IN_KEY, "0");
    }

    @Test
    void crossPartitionWritePinsNullsOutOfInToo() {
        assertThat(settingsSentFor(false).getAllSettings()).containsEntry(FinalSettings.TRANSFORM_NULL_IN_KEY, "0");
    }

    @Test
    void crossPartitionWriteCarriesFinalOnly() {
        assertThat(settingsSentFor(false).getAllSettings())
                .containsEntry(FinalSettings.FINAL_KEY, "1")
                .doesNotContainKey(FinalSettings.NO_MERGE_ACROSS_PARTITIONS_KEY);
    }

    /** A window over an aligned (day-partitioned) target may span more than the default 100 partitions. */
    @Test
    void writeLiftsThePartitionsPerInsertLimit() {
        assertThat(settingsSentFor(false).getAllSettings())
                .containsEntry("clickhouse_setting_" + ClickHouseInsertSelectExecutor.MAX_PARTITIONS_PER_INSERT_BLOCK, "0");
    }

    @AfterEach
    void clearMdc() {
        StatementTag.clear();
    }

    @Test
    void writeCarriesTheBudget() {
        assertThat(settingsSentFor(false).getAllSettings())
                .containsEntry("clickhouse_setting_max_execution_time", "30")
                .containsEntry("clickhouse_setting_max_memory_usage", String.valueOf(DataSize.ofGigabytes(2).toBytes()));
    }

    /**
     * Every attempt is its own statement in {@code system.query_log}: a narrowing retry that reused the refused
     * attempt's id would make the two indistinguishable there.
     */
    @Test
    void eachWriteGetsFreshQueryIdOnItsSpanAndInTheLogContext() {
        final CommandResponse response = mock(CommandResponse.class);
        given(writer.client()).willReturn(client);
        given(client.execute(eq(SQL), any(CommandSettings.class))).willReturn(CompletableFuture.completedFuture(response));
        final InMemorySpanExporter spans = InMemorySpanExporter.create();
        final ClickHouseSpans traced = new ClickHouseSpans(new ClientSpanTracer(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
                .build()), new ClickHouseProperties("localhost", 8123, null, "default", ""));
        final ClickHouseInsertSelectExecutor executor = new ClickHouseInsertSelectExecutor(writer, traced);

        executor.insertSelect("t", SQL, false, BUDGET, TAG);
        final String firstInContext = MDC.get(StatementTag.MDC_KEY);
        executor.insertSelect("t", SQL, false, BUDGET, TAG);

        final ArgumentCaptor<CommandSettings> settings = ArgumentCaptor.forClass(CommandSettings.class);
        verify(client, times(2)).execute(eq(SQL), settings.capture());
        final List<String> ids = settings.getAllValues().stream().map(CommandSettings::getQueryId).toList();
        assertThat(ids).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).startsWith("adas-p1-write-"));
        assertThat(firstInContext).isEqualTo(ids.get(0));
        assertThat(MDC.get(StatementTag.MDC_KEY)).isEqualTo(ids.get(1));
        assertThat(spans.getFinishedSpanItems())
                .extracting(span -> span.getAttributes().get(ClickHouseSpans.DB_QUERY_ID))
                .containsExactlyElementsOf(ids);
    }
}
