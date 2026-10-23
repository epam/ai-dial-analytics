package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.clickhouse.UsageLogWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A run-scoped row buffer that batches inserts <em>across</em> the blobs of a single ingestion run
 * (a poll cycle or a scheduled reconciliation run). Callers append rows via {@link #add(Map)}; the
 * sink flushes to {@link UsageLogWriter} whenever the buffer reaches <em>either</em> the configured row
 * count ({@code batchSize}) <em>or</em> the configured accumulated serialized-byte size
 * ({@code maxBatchBytes}), whichever comes first, and once more when the caller {@link #flush() flushes}
 * at end of run. This replaces the previous per-blob flush, so a run of many tiny one-record blobs
 * produces a few large inserts (≈ {@code totalRows / batchSize}) rather than one insert — and one
 * ClickHouse part — per blob.
 *
 * <p>The byte bound exists because the row count alone does not bound the serialized payload: DIAL
 * usage-log rows vary wildly in size (they can embed full request/response bodies), so a count-only
 * batch of large rows can serialize past the JVM's maximum array length and OOM the writer. The
 * per-row byte size is a cheap, conservative <em>estimate</em> ({@link #estimateBytes}); the row is
 * serialized for real exactly once, by {@link UsageLogWriter} at flush. Exactness is unnecessary — the
 * configured threshold sits far enough below the array-length ceiling that estimation error cannot
 * bridge the gap.
 *
 * <p>A failure to write a batch is surfaced as {@link FlushException} so callers can distinguish it
 * from a single blob's parse failure: a flush failure means the writer (ClickHouse) is unavailable and
 * is a run-level fault, whereas a parse failure is isolated to its blob. A {@link FlushException}
 * renders the sink <em>unusable</em>: the failed batch is dropped and any further {@link #add(Map)} or
 * {@link #flush()} throws {@link IllegalStateException}, so a caller that swallows the exception cannot
 * retry or double-count it.
 *
 * <p>A sink may carry a {@link FlushStage}, which rewrites each batch right before the insert and learns once it
 * succeeded. A failure in the stage fails the flush exactly as a failed insert does.
 *
 * <p>Not thread-safe: a sink belongs to one run, driven by the single-threaded scheduler.
 */
class RowBatchSink {

    private final UsageLogWriter writer;
    private final String table;
    private final int batchSize;
    private final long maxBatchBytes;
    private final FlushStage stage;
    private final List<Map<String, Object>> buffer = new ArrayList<>();
    private long bufferedBytes;
    private long totalInserted;
    private boolean failed;

    RowBatchSink(UsageLogWriter writer, String table, int batchSize, long maxBatchBytes) {
        this(writer, table, batchSize, maxBatchBytes, FlushStage.NONE);
    }

    RowBatchSink(UsageLogWriter writer, String table, int batchSize, long maxBatchBytes, FlushStage stage) {
        this.writer = writer;
        this.table = table;
        this.batchSize = batchSize;
        this.maxBatchBytes = maxBatchBytes;
        this.stage = stage;
    }

    /** Appends a row, flushing the buffer if it has reached the batch size or the byte bound. */
    void add(Map<String, Object> row) {
        if (offer(row)) {
            flush();
        }
    }

    /**
     * Appends a row WITHOUT flushing, and reports whether the buffer has reached the batch size or the byte
     * bound. For a caller that owns the flush order across several sinks ({@link RunSink}): it buffers here,
     * and on {@code true} decides which sinks to flush and in what sequence.
     */
    boolean offer(Map<String, Object> row) {
        ensureUsable();
        buffer.add(row);
        bufferedBytes += estimateBytes(row);
        return buffer.size() >= batchSize || bufferedBytes >= maxBatchBytes;
    }

    /** Writes any buffered rows. Safe to call when empty. Throws {@link FlushException} on write error. */
    void flush() {
        ensureUsable();
        if (buffer.isEmpty()) {
            return;
        }
        Runnable written;
        try {
            written = stage.prepare(buffer);
            writer.insert(table, buffer);
            totalInserted += buffer.size();
        } catch (Exception ex) {
            // A flush failure is terminal for this single-use sink: mark it unusable and drop the failed
            // batch (in finally) so a caller that catches FlushException cannot retry or double-count it.
            failed = true;
            throw new FlushException(ex);
        } finally {
            buffer.clear();
            bufferedBytes = 0;
        }
        written.run();
    }

    /**
     * Cheap, conservative estimate of a value's serialized JSON byte size, used only to bound the buffer
     * (see class Javadoc) — never as the bytes actually inserted. It is a deliberate <em>over</em>-
     * approximation of the escaped UTF-8 payload: the string branch counts real UTF-8 byte length and
     * doubles it to absorb JSON escaping of embedded quotes/backslashes (common in DIAL rows that carry
     * request/response JSON bodies), so the byte bound governs the payload actually produced at flush
     * rather than undercounting it. It stays an estimate — no real serialization — so it remains cheap.
     */
    private static long estimateBytes(Object value) {
        if (value == null) {
            return 4; // "null"
        }
        if (value instanceof CharSequence text) {
            // UTF-8 bytes, doubled for escaping headroom, plus the surrounding quotes.
            return utf8Length(text) * 2L + 2L;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return 8L;
        }
        if (value instanceof Map<?, ?> map) {
            long bytes = 2; // braces
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                bytes += String.valueOf(entry.getKey()).length() + 4L; // "key":
                bytes += estimateBytes(entry.getValue()) + 1L; // value + comma
            }
            return bytes;
        }
        if (value instanceof Iterable<?> items) {
            long bytes = 2; // brackets
            for (Object item : items) {
                bytes += estimateBytes(item) + 1L; // item + comma
            }
            return bytes;
        }
        return String.valueOf(value).length() + 2L;
    }

    /** UTF-8 byte length of a char sequence, computed without allocating an intermediate array. */
    private static long utf8Length(CharSequence text) {
        long bytes = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < length
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4; // supplementary code point encoded from a surrogate pair
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private void ensureUsable() {
        if (failed) {
            throw new IllegalStateException("RowBatchSink is unusable after a FlushException");
        }
    }

    /** Total rows actually written to the writer so far across this run. */
    long totalInserted() {
        return totalInserted;
    }

    /** Raised when a batch cannot be written, marking a run-level (writer-unavailable) failure. */
    static class FlushException extends RuntimeException {
        FlushException(Throwable cause) {
            this("Failed to insert a row batch", cause);
        }

        FlushException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
