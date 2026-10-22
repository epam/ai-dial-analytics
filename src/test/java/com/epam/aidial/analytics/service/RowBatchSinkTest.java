package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.clickhouse.TestClickHouseSpans;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RowBatchSink}'s dual flush trigger: a batch flushes when it reaches either the
 * configured row count or the configured accumulated serialized-byte size, whichever comes first.
 */
class RowBatchSinkTest {

    /** A {@link UsageLogWriter} double that records the size of every batch it is handed. */
    private static final class BatchRecordingWriter extends UsageLogWriter {
        private final List<Integer> batchSizes = new ArrayList<>();

        private BatchRecordingWriter() {
            super(null, null, null, TestClickHouseSpans.noop(), new SimpleMeterRegistry());
        }

        @Override
        public void insert(String table, List<Map<String, Object>> batch) {
            if (batch.isEmpty()) {
                return;
            }
            batchSizes.add(batch.size());
        }
    }

    private static Map<String, Object> rowWithBodyOfLength(int length) {
        return Map.of("body", "x".repeat(length));
    }

    @Test
    void flushesOnByteBoundBeforeRowCount() {
        BatchRecordingWriter writer = new BatchRecordingWriter();
        // Row count cap is large and never reached; the byte bound (100) is what triggers the flush.
        // Each ASCII row's estimate is ~73 bytes (30-char body doubled for escaping headroom + ~13
        // overhead), so two rows cross 100.
        RowBatchSink sink = new RowBatchSink(writer, "t", 1000, 100L);

        sink.add(rowWithBodyOfLength(30)); // ~73 buffered, below the byte bound
        sink.add(rowWithBodyOfLength(30)); // ~146 buffered, crosses 100 -> flush of 2 rows
        sink.add(rowWithBodyOfLength(30)); // ~73 buffered again
        sink.flush();                      // end-of-run flush of the remaining 1 row

        assertThat(writer.batchSizes).containsExactly(2, 1);
        assertThat(sink.totalInserted()).isEqualTo(3);
    }

    @Test
    void byteEstimateOverApproximatesEscapedSerializedSize() {
        // A quote-heavy, backslash-laden, multi-byte, control-char string — exactly the shape that a
        // naive char-count estimate undercounts once JSON escaping + UTF-8 encoding are applied.
        Map<String, Object> row = Map.of(
                "body", "\"quoted\" \\slashed\\ ☃ snowman\n tab\tend and more \"\"\"\" quotes");

        // The real JSONEachRow bytes this row serializes to at flush.
        long actualBytes = new JsonMapper().writeValueAsBytes(row).length;

        BatchRecordingWriter writer = new BatchRecordingWriter();
        // Byte bound == the real serialized size. Adding the single row flushes immediately only if the
        // estimate is >= the actual bytes — i.e. the bound trips at or before the payload reaches it.
        RowBatchSink sink = new RowBatchSink(writer, "t", 1000, actualBytes);

        sink.add(row);

        assertThat(writer.batchSizes).containsExactly(1);
    }

    @Test
    void flushesOnRowCountWhenRowsAreSmall() {
        BatchRecordingWriter writer = new BatchRecordingWriter();
        // Byte bound is far larger than any batch here, so only the row-count cap (2) triggers flushes.
        RowBatchSink sink = new RowBatchSink(writer, "t", 2, 268_435_456L);

        for (int i = 0; i < 5; i++) {
            sink.add(rowWithBodyOfLength(3));
        }
        sink.flush(); // end-of-run flush of the trailing row

        assertThat(writer.batchSizes).containsExactly(2, 2, 1);
        assertThat(sink.totalInserted()).isEqualTo(5);
    }

    @Test
    void flushOfEmptyBufferIsNoOp() {
        BatchRecordingWriter writer = new BatchRecordingWriter();
        RowBatchSink sink = new RowBatchSink(writer, "t", 2, 268_435_456L);

        sink.flush();

        assertThat(writer.batchSizes).isEmpty();
        assertThat(sink.totalInserted()).isZero();
    }
}
