package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.data.ClickHouseFormat;
import com.epam.aidial.analytics.exception.ClickHouseStorageFullException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link UsageLogWriter#batchStream} yields the same {@code JSONEachRow} payload the
 * previous whole-batch path did — one {@code \n}-delimited JSON line per row — but produced lazily as
 * the consumer reads, so a large batch is never materialized as one in-memory buffer. Also verifies a
 * mid-stream serialization failure aborts the stream rather than emitting a truncated payload.
 *
 * <p>And covers the insert path's failure classification and meters: which server error code counts as
 * disk exhaustion, and that each issued batch lands on {@code clickhouse.inserts} or
 * {@code clickhouse.insert.failures} under the reason that classified it — exercised over a stubbed driver
 * client, so no live ClickHouse is needed.
 */
class UsageLogWriterTest {

    private static final String TABLE = "dial_usage_log";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final UsageLogWriter writer =
            new UsageLogWriter(null, null, jsonMapper, TestClickHouseSpans.noop(), new SimpleMeterRegistry());

    private static Map<String, Object> row(String project, Object tokens, String body) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("project_id", project);
        row.put("total_tokens", tokens);
        row.put("dial_usage_log_payload.request_body", body);
        return row;
    }

    @Test
    void batchStreamMatchesNewlineDelimitedJsonPerRow() throws IOException {
        List<Map<String, Object>> rows = List.of(
                row("p1", 1, "hello"),
                row("p2", 42, "naïve ☃ unicode"), // multi-byte content must round-trip identically
                row("p3", null, "{\"nested\":\"json-as-string\"}"));

        // The exact bytes the whole-batch path would have produced: one JSON line per row, UTF-8.
        StringBuilder expected = new StringBuilder();
        for (Map<String, Object> row : rows) {
            expected.append(jsonMapper.writeValueAsString(row)).append('\n');
        }
        byte[] expectedBytes = expected.toString().getBytes(StandardCharsets.UTF_8);

        byte[] actual;
        try (InputStream stream = writer.batchStream(rows)) {
            actual = stream.readAllBytes(); // drives the bulk read(byte[], off, len) path
        }

        assertThat(actual).isEqualTo(expectedBytes);
        // One JSON line per row: exactly rows.size() newline separators, none missing or doubled.
        long newlines = new String(actual, StandardCharsets.UTF_8).chars().filter(c -> c == '\n').count();
        assertThat(newlines).isEqualTo(rows.size());
    }

    @Test
    void batchStreamReadByteByByteMatchesBulkRead() throws IOException {
        List<Map<String, Object>> rows = List.of(row("p1", 1, "a"), row("p2", 2, "☃"));

        byte[] bulk;
        try (InputStream stream = writer.batchStream(rows)) {
            bulk = stream.readAllBytes();
        }

        // The single-byte read() path must yield identical bytes to the bulk read.
        byte[] singleByte = new byte[bulk.length];
        try (InputStream stream = writer.batchStream(rows)) {
            int i = 0;
            for (int b = stream.read(); b != -1; b = stream.read()) {
                singleByte[i++] = (byte) b;
            }
            assertThat(stream.read()).isEqualTo(-1); // stays at end
            assertThat(i).isEqualTo(bulk.length);
        }
        assertThat(singleByte).isEqualTo(bulk);
    }

    @Test
    void batchStreamResetReplaysIdenticalBytes() throws IOException {
        List<Map<String, Object>> rows = List.of(row("p1", 1, "hello"), row("p2", 2, "☃ unicode"));

        try (InputStream stream = writer.batchStream(rows)) {
            assertThat(stream.markSupported()).isTrue();

            byte[] first = stream.readAllBytes();
            // A retry replays the batch via reset() (the client calls it on retryable failures); the
            // second pass must yield exactly the same payload, not a truncated or empty one.
            stream.reset();
            byte[] second = stream.readAllBytes();

            assertThat(second).isEqualTo(first);
        }
    }

    @Test
    void batchStreamSerializesJsonNodeObjectValueAsNestedJson() throws IOException {
        JsonNode claims = jsonMapper.readTree("{\"sub\":\"user-1\",\"roles\":[\"admin\"]}");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("project_id", "p1");
        row.put("jwt_claims", claims);
        List<Map<String, Object>> rows = List.of(row);

        String actual;
        try (InputStream stream = writer.batchStream(rows)) {
            actual = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(actual.trim()).isEqualTo(jsonMapper.writeValueAsString(row));
        assertThat(actual).contains("\"jwt_claims\":{\"sub\":\"user-1\",\"roles\":[\"admin\"]}");
    }

    /** A value whose getter throws, forcing Jackson to fail while serializing the row that holds it. */
    static final class Exploding {
        @SuppressWarnings("unused")
        public String getValue() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void batchStreamAbortsOnRowSerializationFailure() {
        Map<String, Object> exploding = Map.of("value", new Exploding());
        List<Map<String, Object>> rows = List.of(row("p1", 1, "ok"), exploding);

        assertThatThrownBy(() -> {
            try (InputStream stream = writer.batchStream(rows)) {
                stream.readAllBytes();
            }
        }).isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to serialize row");
    }

    /**
     * A throwable that reports itself as its own cause. {@link Throwable#initCause} refuses self-causation,
     * so a cycle can only be built by overriding the getter — which is exactly the shape a naive
     * "walk until the cause repeats" loop mishandles (it inspects nothing) or spins on.
     */
    static final class SelfCausing extends RuntimeException {
        SelfCausing(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    private static ServerException serverError(int code) {
        return new ServerException(code, "server said no", 500, "query-1");
    }

    @Test
    void recognizesDiskFullServerCodeAnywhereInTheChain() {
        // Directly, and wrapped the way the insert path and the batch sink wrap it.
        assertThat(UsageLogWriter.isOutOfDiskSpace(serverError(243))).isTrue();
        assertThat(UsageLogWriter.isOutOfDiskSpace(
                new IllegalStateException("ClickHouse insert failed",
                        new RuntimeException("wrapped", serverError(243))))).isTrue();
    }

    @Test
    void doesNotRecognizeOtherFailures() {
        // 241 is MEMORY_LIMIT_EXCEEDED: a real server refusal, but not this one — it must keep the generic path.
        assertThat(UsageLogWriter.isOutOfDiskSpace(serverError(241))).isFalse();
        assertThat(UsageLogWriter.isOutOfDiskSpace(new IllegalStateException("no server exception here")))
                .isFalse();
        assertThat(UsageLogWriter.isOutOfDiskSpace(null)).isFalse();
    }

    @Test
    void terminatesOnSelfReferentialCauseChain() {
        assertThat(UsageLogWriter.isOutOfDiskSpace(new SelfCausing("loops forever"))).isFalse();
        assertThat(UsageLogWriter.isOutOfDiskSpace(
                new IllegalStateException("wrapper", new SelfCausing("loops forever")))).isFalse();
    }

    /**
     * A writer whose driver client is {@code client}. {@link UsageLogWriter#client()} is package-private
     * precisely so the insert path — the only place the meters are touched — is exercisable without a live
     * server; every other collaborator the path needs is either the real one or unused.
     */
    private UsageLogWriter writerOver(Client client, SimpleMeterRegistry registry) {
        return new UsageLogWriter(null, null, jsonMapper, TestClickHouseSpans.noop(), registry) {
            @Override
            Client client() {
                return client;
            }
        };
    }

    /** A client whose insert completes with {@code response}, or fails with {@code failure} if given. */
    private static Client clientAnswering(InsertResponse response, Throwable failure) {
        Client client = mock(Client.class);
        when(client.insert(eq(TABLE), any(InputStream.class), eq(ClickHouseFormat.JSONEachRow)))
                .thenReturn(failure == null
                        ? CompletableFuture.completedFuture(response)
                        : CompletableFuture.failedFuture(failure));
        return client;
    }

    private static double count(SimpleMeterRegistry registry, String meter, String... tags) {
        return registry.find(meter).tags(tags).counter() == null
                ? 0d
                : registry.find(meter).tags(tags).counter().count();
    }

    /**
     * One batch insert is one part per partition it touches, so the counter has to move once per issued
     * INSERT and be attributed to the table — that attribution is the whole point of the meter.
     */
    @Test
    void countsOneInsertPerBatchAgainstItsTable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsageLogWriter subject = writerOver(clientAnswering(mock(InsertResponse.class), null), registry);

        subject.insert(TABLE, List.of(row("p", 1, "{}")));
        subject.insert(TABLE, List.of(row("p", 2, "{}")));

        assertThat(count(registry, "clickhouse.inserts", "table", TABLE)).isEqualTo(2d);
        assertThat(registry.find("clickhouse.insert.failures").counter()).isNull();
    }

    /** An empty batch issues no INSERT, so it must not be counted as one. */
    @Test
    void doesNotCountAnEmptyBatch() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        writerOver(clientAnswering(mock(InsertResponse.class), null), registry).insert(TABLE, List.of());

        assertThat(registry.find("clickhouse.inserts").counter()).isNull();
    }

    /**
     * The refusal that this whole change turns on: code 243 must raise the dedicated exception and land on
     * the failure meter under its own reason, so disk exhaustion is countable apart from everything else.
     */
    @Test
    void countsAndNamesAnInsertRefusedForLackOfDiskSpace() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsageLogWriter subject =
                writerOver(clientAnswering(null, new ServerException(243, "not enough space", 500, "q")), registry);

        assertThatThrownBy(() -> subject.insert(TABLE, List.of(row("p", 1, "{}"))))
                .isInstanceOf(ClickHouseStorageFullException.class)
                .hasMessageContaining(TABLE);

        assertThat(count(registry, "clickhouse.insert.failures", "table", TABLE, "reason", "storage_full"))
                .isEqualTo(1d);
        assertThat(registry.find("clickhouse.inserts").counter()).isNull();
    }

    /** Every other failure keeps the generic exception and the generic reason, so the two stay separable. */
    @Test
    void countsAnyOtherInsertFailureUnderTheGenericReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsageLogWriter subject = writerOver(
                clientAnswering(null, new ServerException(241, "memory limit exceeded", 500, "q")), registry);

        assertThatThrownBy(() -> subject.insert(TABLE, List.of(row("p", 1, "{}"))))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ClickHouseStorageFullException.class);

        assertThat(count(registry, "clickhouse.insert.failures", "table", TABLE, "reason", "other")).isEqualTo(1d);
        assertThat(count(registry, "clickhouse.insert.failures", "table", TABLE, "reason", "storage_full"))
                .isZero();
    }
}
