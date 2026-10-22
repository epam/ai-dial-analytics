package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.data.ClickHouseFormat;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.ClickHouseWriterProperties;
import com.epam.aidial.analytics.exception.CauseChain;
import com.epam.aidial.analytics.exception.ClickHouseStorageFullException;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes transformed usage-log rows into the {@code data-sync} target table. This is the only write
 * path to ClickHouse and it uses a dedicated writer credential, separate from the read-only query
 * client ({@link ClickHouseClientWrapper}), so the read path keeps its read-only posture. The
 * underlying {@link Client} is created lazily on first use.
 *
 * <p>Rows are inserted in {@code JSONEachRow} format; {@code _ingested_at} is left to the table's
 * {@code DEFAULT now64(3)}. The service is responsible for batching; each {@link #insert(String, List)}
 * call sends one batch to the named target table, so a run with several targets shares one writer.
 * Because the version column is that server-side default, the client pins
 * {@link ClickHouseWriterClient#ASYNC_INSERT} to {@code 0} so one call stays one server-side insert with
 * its own version stamp — see the constant for why the server default cannot be inherited here.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UsageLogWriter {

    /**
     * {@code NOT_ENOUGH_SPACE} — ClickHouse refused the write because the disk is full. Matched on the
     * numeric server code rather than on message text, which is the less brittle of the two (see
     * {@link ClickHouseBudget} for the same treatment of the budget codes). Anything unrecognised keeps the
     * generic failure path, so a driver or server change that stops surfacing this code costs the specific
     * classification and nothing else.
     */
    private static final int NOT_ENOUGH_SPACE = 243;

    private final ClickHouseProperties properties;
    private final ClickHouseWriterProperties writerProperties;
    private final JsonMapper jsonMapper;
    private final ClickHouseSpans clickHouseSpans;
    private final MeterRegistry meterRegistry;
    private volatile Client client;

    /**
     * Inserts a single batch of rows into the given target table. Each row is an ordered map keyed by
     * ClickHouse column name (as produced by the mapping engine), serialized as one {@code JSONEachRow}
     * line.
     *
     * <p>One call is one ClickHouse INSERT, which creates one part per partition it touches, so the
     * {@code clickhouse.inserts} counter this method increments is the service's own measure of the part
     * pressure the ingest paths impose on a table — a number ADAS cannot query for, because its query
     * surface reaches only catalog-registered tables. Both meters are tagged by target table rather than by
     * ingest path (unlike the {@code sync.*} / {@code poll.*} / {@code backfill.*} row counts): part
     * pressure is a property of the table, and every ingest path writes through here. The per-run
     * attribution stays available on the {@code clickhouse insert} spans.
     *
     * @param table the target ClickHouse table
     * @param rows  rows to insert (a no-op if empty)
     * @throws ClickHouseStorageFullException if ClickHouse refused the write for lack of disk space
     */
    public void insert(String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        clickHouseSpans.insert(table, () -> {
            try (InputStream data = batchStream(rows);
                    InsertResponse response = client()
                            .insert(table, data, ClickHouseFormat.JSONEachRow)
                            .get()) {
                log.debug("Inserted {} rows into {}", rows.size(), table);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                failed(table, "other");
                throw new IllegalStateException("Interrupted while inserting into ClickHouse", ex);
            } catch (Exception ex) {
                if (isOutOfDiskSpace(ex)) {
                    failed(table, "storage_full");
                    throw new ClickHouseStorageFullException(
                            "ClickHouse refused the insert into " + table + ": the disk is full", ex);
                }
                failed(table, "other");
                throw new IllegalStateException("ClickHouse insert failed", ex);
            }
            // Counted after the block rather than inside it, so a failure raised while closing the response
            // is counted once, as a failure, instead of once on each meter.
            meterRegistry.counter("clickhouse.inserts", "table", table).increment();
        });
    }

    /** Counts a failed batch insert under the reason that classified it, so disk exhaustion stays separable. */
    private void failed(String table, String reason) {
        meterRegistry.counter("clickhouse.insert.failures", "table", table, "reason", reason).increment();
    }

    /**
     * Whether {@code error} is, or was caused by, ClickHouse refusing the write for lack of disk space.
     * Package-private so the classification is testable on its own, without a live server.
     */
    static boolean isOutOfDiskSpace(Throwable error) {
        return CauseChain.anyMatch(
                error, cause -> cause instanceof ServerException server && server.getCode() == NOT_ENOUGH_SPACE);
    }

    /**
     * Opens a streaming {@code JSONEachRow} view over the batch: each row is serialized to UTF-8 bytes
     * on demand as ClickHouse reads from the stream, so the whole batch is never materialized as a
     * single in-memory {@code byte[]}/{@code String}. Peak insert memory beyond the caller's retained
     * row buffer is therefore bounded by roughly a single row's serialized form, independent of batch
     * size. Package-private so the serialization can be verified without a live ClickHouse.
     *
     * @param rows rows to serialize (assumed non-empty)
     * @return a lazily-serializing {@code JSONEachRow} UTF-8 byte stream over the rows
     */
    InputStream batchStream(List<Map<String, Object>> rows) {
        return new JsonEachRowStream(rows, jsonMapper);
    }

    /**
     * A pull-based {@link InputStream} that serializes rows to newline-delimited {@code JSONEachRow}
     * UTF-8 bytes one row at a time: it holds only the current row's bytes and advances to the next row
     * when they are drained. A serialization failure surfaces as an {@link IOException} so the insert
     * aborts rather than sending a truncated payload. Single-consumer, not thread-safe.
     *
     * <p>Supports {@link #reset()} by re-iterating the backing row list from the start (no {@link #mark}
     * needed) so the ClickHouse client can replay the batch on a retry; the row list itself is retained
     * by the caller regardless, so replay adds no memory beyond re-serializing rows on demand.
     */
    static final class JsonEachRowStream extends InputStream {

        private static final byte[] EMPTY = new byte[0];

        private final List<Map<String, Object>> rowList;
        private final JsonMapper jsonMapper;
        private Iterator<Map<String, Object>> rows;
        private byte[] chunk = EMPTY;
        private int pos;

        JsonEachRowStream(List<Map<String, Object>> rows, JsonMapper jsonMapper) {
            this.rowList = rows;
            this.rows = rows.iterator();
            this.jsonMapper = jsonMapper;
        }

        /** Always resettable: rewinds to the first row so the client can replay the batch on a retry. */
        @Override
        public boolean markSupported() {
            return true;
        }

        /** Rewinds to the start of the batch, discarding the current position (mark position is ignored). */
        @Override
        public void reset() {
            rows = rowList.iterator();
            chunk = EMPTY;
            pos = 0;
        }

        @Override
        public int read() throws IOException {
            if (!ensureChunk()) {
                return -1;
            }
            return chunk[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (!ensureChunk()) {
                return -1;
            }
            int n = Math.min(len, chunk.length - pos);
            System.arraycopy(chunk, pos, buffer, off, n);
            pos += n;
            return n;
        }

        /** Ensures {@link #chunk} has unread bytes, serializing the next row on demand; false at end. */
        private boolean ensureChunk() throws IOException {
            while (pos >= chunk.length) {
                if (!rows.hasNext()) {
                    return false;
                }
                chunk = serializeRow(rows.next());
                pos = 0;
            }
            return true;
        }

        private byte[] serializeRow(Map<String, Object> row) throws IOException {
            byte[] json;
            try {
                json = jsonMapper.writeValueAsBytes(row);
            } catch (Exception ex) {
                throw new IOException("Failed to serialize row for JSONEachRow insert", ex);
            }
            byte[] withNewline = Arrays.copyOf(json, json.length + 1);
            withNewline[json.length] = '\n';
            return withNewline;
        }
    }

    /**
     * Introspects the target table via {@code DESCRIBE TABLE}, returning its columns and declared
     * types in table order. Used by the startup schema validator to reconcile the configured mapping
     * against the live table.
     *
     * @param table the target ClickHouse table to introspect
     * @return ordered map of column name to ClickHouse type spelling
     * @throws ClickHouseUnavailableException if ClickHouse cannot be reached
     * @throws IllegalStateException          if the describe query otherwise fails
     */
    public Map<String, String> describeTable(String table) {
        String sql = "DESCRIBE TABLE " + table;
        return clickHouseSpans.describe(() -> {
            try {
                List<GenericRecord> records = client().queryAll(sql);
                Map<String, String> columns = new LinkedHashMap<>();
                for (GenericRecord record : records) {
                    columns.put(record.getString("name"), record.getString("type"));
                }
                return columns;
            } catch (Exception ex) {
                if (ClickHouseFailures.isUnavailable(ex)) {
                    throw new ClickHouseUnavailableException("ClickHouse is unreachable", ex);
                }
                throw new IllegalStateException("DESCRIBE TABLE failed for " + table, ex);
            }
        });
    }

    /**
     * Runs a read on the writer credential, for the ingest path's own state (the ingest ledger) — the
     * read-only query client stays out of ingest. Values are bound server-side as ClickHouse query
     * parameters ({@code {name:Type}}), never concatenated into the statement.
     *
     * @param sql    the statement, referencing {@code params} by name
     * @param params the query parameters
     * @return every result row
     * @throws ClickHouseUnavailableException if ClickHouse cannot be reached
     * @throws IllegalStateException          if the query otherwise fails
     */
    public List<GenericRecord> query(String sql, Map<String, Object> params) {
        return clickHouseSpans.query(null, () -> {
            try {
                return client().queryAll(sql, params);
            } catch (Exception ex) {
                if (ClickHouseFailures.isUnavailable(ex)) {
                    throw new ClickHouseUnavailableException("ClickHouse is unreachable", ex);
                }
                throw new IllegalStateException("ClickHouse query failed", ex);
            }
        });
    }

    /** Package-private, not private, so a test can substitute the driver client; never overridden in production. */
    Client client() {
        Client local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = buildClient();
                    client = local;
                }
            }
        }
        return local;
    }

    private Client buildClient() {
        return ClickHouseClients.builder(properties, writerProperties.user(), writerProperties.password())
                .serverSetting(ClickHouseWriterClient.ASYNC_INSERT, "0")
                .build();
    }

    @PreDestroy
    void shutdown() {
        Client local = client;
        if (local != null) {
            try {
                local.close();
            } catch (Exception ex) {
                log.debug("Error closing ClickHouse writer client", ex);
            }
        }
    }
}
