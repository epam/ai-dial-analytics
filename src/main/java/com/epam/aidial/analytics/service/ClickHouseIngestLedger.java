package com.epam.aidial.analytics.service;

import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link IngestLedger} kept in ClickHouse table {@value #TABLE}, beside the rows it vouches for and on
 * the same writer credential (ClickHouse migration {@code V13}).
 *
 * <p>The read is by prefix on the {@code ORDER BY} key, with the prefix a bound parameter, so the statement
 * size is constant however many blobs a day holds; {@code argMax} over {@code ingested_at} picks the newest
 * entry per key whether or not older ones have merged away, so no {@code FINAL} is needed. The write goes
 * through {@link UsageLogWriter#insert}, which pins {@code async_insert = 0}, so each insert gets its own
 * {@code ingested_at} stamp and is visible once acknowledged.
 */
@Component
@RequiredArgsConstructor
public class ClickHouseIngestLedger implements IngestLedger {

    /** The ledger table. The {@code _dial_internal_} prefix is reserved, so no table API can create or drop it. */
    public static final String TABLE = "_dial_internal_ingested_blob";

    /** The ledger's columns, for startup schema validation. */
    public static final Map<String, String> COLUMNS = Map.of(
            "blob_key", "String",
            "blob_last_modified", "DateTime64(3, 'UTC')",
            "ingested_at", "DateTime64(3, 'UTC')");

    private static final String RECORDED_SQL = "SELECT blob_key, toUnixTimestamp64Milli(argMax(blob_last_modified, ingested_at)) AS last_modified_ms"
            + " FROM " + TABLE
            + " WHERE startsWith(blob_key, {prefix:String})"
            + " GROUP BY blob_key";

    /** The most keys one keyed read binds. */
    static final int MAX_KEYS_PER_READ = 1000;
    /**
     * The most characters one keyed read's array parameter may hold. The HTTP interface refuses a form field longer than
     * {@code http_max_field_value_size} (128 KiB by default), so a read of long keys is split well below that.
     */
    static final int MAX_PARAMETER_CHARS = 64 * 1024;

    private static final String RECORDED_AMONG_SQL = "SELECT blob_key, toUnixTimestamp64Milli(argMax(blob_last_modified, ingested_at)) AS last_modified_ms,"
            + " toUnixTimestamp64Milli(max(ingested_at)) AS ingested_at_ms"
            + " FROM " + TABLE
            + " WHERE blob_key IN {keys:Array(String)}"
            + " GROUP BY blob_key";

    /** The {@code DateTime64(3)} spelling the row-write path also hands ClickHouse ({@code WriteTimestampNormalizer}). */
    private static final DateTimeFormatter STORAGE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final UsageLogWriter writer;

    @Override
    public Map<String, Instant> recorded(String prefix) {
        Map<String, Instant> recorded = new HashMap<>();
        for (GenericRecord row : writer.query(RECORDED_SQL, Map.of("prefix", prefix))) {
            recorded.put(row.getString("blob_key"), Instant.ofEpochMilli(row.getLong("last_modified_ms")));
        }
        return recorded;
    }

    @Override
    public Map<String, Recorded> recordedAmong(Collection<String> keys) {
        Map<String, Recorded> recorded = new HashMap<>();
        for (List<String> chunk : chunks(keys)) {
            for (GenericRecord row : writer.query(RECORDED_AMONG_SQL, Map.of("keys", arrayLiteral(chunk)))) {
                recorded.put(row.getString("blob_key"), new Recorded(
                        Instant.ofEpochMilli(row.getLong("last_modified_ms")), Instant.ofEpochMilli(row.getLong("ingested_at_ms"))));
            }
        }
        return recorded;
    }

    /** Splits {@code keys} into reads of at most {@link #MAX_KEYS_PER_READ} keys and about {@link #MAX_PARAMETER_CHARS} characters. */
    static List<List<String>> chunks(Collection<String> keys) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> chunk = new ArrayList<>();
        long chars = 0;
        for (String key : keys) {
            // Quotes, comma and a margin for escapes.
            long size = key.length() + 8L;
            if (!chunk.isEmpty() && (chunk.size() >= MAX_KEYS_PER_READ || chars + size > MAX_PARAMETER_CHARS)) {
                chunks.add(chunk);
                chunk = new ArrayList<>();
                chars = 0;
            }
            chunk.add(key);
            chars += size;
        }
        if (!chunk.isEmpty()) {
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * The text form of an {@code Array(String)} query parameter. The client hands a parameter to the server as text, and
     * a {@link java.util.List}'s own {@code toString} is not ClickHouse's array syntax; each element is quoted with its
     * backslashes and quotes escaped, so a key is never read as syntax.
     */
    static String arrayLiteral(Collection<String> values) {
        StringBuilder text = new StringBuilder("[");
        for (String value : values) {
            if (text.length() > 1) {
                text.append(',');
            }
            text.append('\'').append(value.replace("\\", "\\\\").replace("'", "\\'")).append('\'');
        }
        return text.append(']').toString();
    }

    @Override
    public void record(Collection<BlobListing> written) {
        List<Map<String, Object>> rows = new ArrayList<>(written.size());
        for (BlobListing listing : written) {
            if (Instant.EPOCH.equals(listing.lastModified())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("blob_key", listing.name());
            row.put("blob_last_modified", STORAGE.format(listing.lastModified().truncatedTo(ChronoUnit.MILLIS)));
            rows.add(row);
        }
        writer.insert(TABLE, rows);
    }
}
