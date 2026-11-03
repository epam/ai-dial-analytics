package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.clickhouse.ClickHouseDataRejectedException;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p><b>A batch refused for its data is split by blob.</b> Each buffered row remembers the blob it came from. When
 * ClickHouse refuses the batch with a data-class error ({@link ClickHouseDataRejectedException}), the flush halves the
 * set of blobs and inserts each half, recursively: a half that is accepted is stored, and a single blob whose rows are
 * still refused is <em>refused</em> — its rows are not stored, and {@link #flush()} returns it so the caller can keep it
 * out of the ingest ledger and record it in the failure ledger. The sub-inserts send the rows as the stage rewrote them,
 * never preparing them again, and the stage learns which rows were not stored. A sub-insert failing for any other
 * reason fails the flush as a whole. Isolating {@code k} refused blobs among {@code b} costs at most about
 * {@code 2k·log2(b)} sub-inserts; refused ones create no parts. See {@code openspec/specs/data-sync/spec.md}, "A batch
 * ClickHouse refuses for its data is split by blob".
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
    /** The blob each buffered row came from, by index; {@code null} for a row added outside a blob's read. */
    private final List<BlobListing> origins = new ArrayList<>();
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
        return offer(row, null);
    }

    /** As {@link #offer(Map)}, remembering the blob the row came from, so a refused batch can be split by blob. */
    boolean offer(Map<String, Object> row, BlobListing origin) {
        ensureUsable();
        buffer.add(row);
        origins.add(origin);
        bufferedBytes += estimateBytes(row);
        return buffer.size() >= batchSize || bufferedBytes >= maxBatchBytes;
    }

    /**
     * Writes any buffered rows. Safe to call when empty. Throws {@link FlushException} on write error, except for a
     * data-class refusal, which is split by blob (see the class note).
     *
     * @return the blobs whose rows ClickHouse still refused on their own, in buffer order; empty when all were stored
     */
    List<RefusedBlob> flush() {
        ensureUsable();
        if (buffer.isEmpty()) {
            return List.of();
        }
        FlushStage.Written written;
        List<RefusedBlob> refused = List.of();
        Set<Map<String, Object>> notStored = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            written = stage.prepare(buffer);
            try {
                writer.insert(table, buffer);
            } catch (ClickHouseDataRejectedException rejected) {
                refused = split(rejected, notStored);
            }
            totalInserted += buffer.size() - notStored.size();
        } catch (Exception ex) {
            // A flush failure is terminal for this single-use sink: mark it unusable and drop the failed
            // batch (in finally) so a caller that catches FlushException cannot retry or double-count it.
            failed = true;
            throw ex instanceof FlushException flush ? flush : new FlushException(ex);
        } finally {
            buffer.clear();
            origins.clear();
            bufferedBytes = 0;
        }
        written.written(notStored);
        return refused;
    }

    /**
     * Drops the buffered rows of {@code blobs}, unwritten: the rows of a blob refused by another table's flush, which
     * must not be written in this run either.
     */
    void discard(Set<BlobListing> blobs) {
        ensureUsable();
        if (blobs.isEmpty()) {
            return;
        }
        for (int i = buffer.size() - 1; i >= 0; i--) {
            if (origins.get(i) != null && blobs.contains(origins.get(i))) {
                bufferedBytes -= estimateBytes(buffer.get(i));
                buffer.remove(i);
                origins.remove(i);
            }
        }
    }

    /**
     * Splits the refused buffer by blob, halving recursively (see the class note).
     *
     * @param notStored receives the rows not stored
     * @return the blobs refused on their own
     */
    private List<RefusedBlob> split(ClickHouseDataRejectedException rejected, Set<Map<String, Object>> notStored) {
        Map<BlobListing, List<Map<String, Object>>> byBlob = new LinkedHashMap<>();
        for (int i = 0; i < buffer.size(); i++) {
            BlobListing origin = origins.get(i);
            if (origin == null) {
                // A row no blob owns cannot be refused as a blob, so the batch cannot be split.
                throw new FlushException("ClickHouse refused a batch whose rows do not all name their blob", rejected);
            }
            byBlob.computeIfAbsent(origin, ignored -> new ArrayList<>()).add(buffer.get(i));
        }
        List<BlobListing> blobs = new ArrayList<>(byBlob.keySet());
        if (blobs.size() == 1) {
            // A batch of one blob is already split as far as it goes.
            notStored.addAll(byBlob.get(blobs.getFirst()));
            return List.of(refused(blobs.getFirst(), rejected));
        }
        List<RefusedBlob> refused = new ArrayList<>();
        int half = blobs.size() / 2;
        split(blobs.subList(0, half), byBlob, refused, notStored);
        split(blobs.subList(half, blobs.size()), byBlob, refused, notStored);
        return refused;
    }

    private void split(List<BlobListing> blobs, Map<BlobListing, List<Map<String, Object>>> byBlob, List<RefusedBlob> refused,
            Set<Map<String, Object>> notStored) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BlobListing blob : blobs) {
            rows.addAll(byBlob.get(blob));
        }
        try {
            writer.insert(table, rows);
        } catch (ClickHouseDataRejectedException rejected) {
            if (blobs.size() == 1) {
                notStored.addAll(rows);
                refused.add(refused(blobs.getFirst(), rejected));
                return;
            }
            int half = blobs.size() / 2;
            split(blobs.subList(0, half), byBlob, refused, notStored);
            split(blobs.subList(half, blobs.size()), byBlob, refused, notStored);
        }
    }

    private RefusedBlob refused(BlobListing blob, ClickHouseDataRejectedException rejected) {
        return new RefusedBlob(blob, table, rejected.code(), rejected.codeName());
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
