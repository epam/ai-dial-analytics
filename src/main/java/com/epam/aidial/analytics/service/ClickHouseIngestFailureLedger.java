package com.epam.aidial.analytics.service;

import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link IngestFailureLedger} kept in ClickHouse table {@value #TABLE} (ClickHouse migration {@code V17}), beside
 * the ingest ledger and on the same writer credential.
 *
 * <p>Writes are plain inserts through {@link UsageLogWriter#insert}, each event carrying its own {@code event_at}, so
 * recording a failure never reads first. Reads aggregate per {@code (kind, subject)} over the whole table: failures
 * are exceptional, so the table stays small, and the aggregation needs no {@code FINAL} because nothing is replaced.
 */
@Component
@RequiredArgsConstructor
public class ClickHouseIngestFailureLedger implements IngestFailureLedger {

    /** The failure-ledger table. The {@code _dial_internal_} prefix is reserved, so no table API can create or drop it. */
    public static final String TABLE = "_dial_internal_failed_ingest";

    /** The ledger's columns, for startup schema validation. */
    public static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("kind", "LowCardinality(String)"),
            Map.entry("subject", "String"),
            Map.entry("day", "Date"),
            Map.entry("blob_last_modified", "DateTime64(3, 'UTC')"),
            Map.entry("event", "LowCardinality(String)"),
            Map.entry("path", "LowCardinality(String)"),
            Map.entry("mode", "LowCardinality(String)"),
            Map.entry("failure_class", "LowCardinality(String)"),
            Map.entry("reason", "LowCardinality(String)"),
            Map.entry("message", "String"),
            Map.entry("event_at", "DateTime64(3, 'UTC')"));

    private static final String FAILED = "event = 'failed'";
    private static final String NOT_FAILED = "event != 'failed'";

    /**
     * The ledger mode a subject's retry takes: {@code ingest_all} when any failure since its latest {@code resolved} event
     * recorded it, otherwise the mode of its latest failure. So a later failure of a skipping run never hides an earlier
     * {@code ingest_all} one, and the day is still retried ingesting every blob.
     */
    private static final String RETRY_MODE = "if(arrayExists(f -> f.1 > maxIf(event_at, event = 'resolved') AND f.2 = 'ingest_all',"
            + " groupArrayIf((event_at, mode), " + FAILED + ")), 'ingest_all', argMaxIf(mode, event_at, " + FAILED + "))";

    private static final String OPEN_SQL = "SELECT kind, subject,"
            + " toString(argMaxIf(day, event_at, " + FAILED + ")) AS failed_day,"
            + " toUnixTimestamp64Milli(argMaxIf(blob_last_modified, event_at, " + FAILED + ")) AS blob_last_modified_ms,"
            + " toUnixTimestamp64Milli(maxIf(event_at, " + NOT_FAILED + ")) AS reset_at_ms,"
            + " arrayCount(t -> t > maxIf(event_at, " + NOT_FAILED + "), groupArrayIf(event_at, " + FAILED + ")) AS attempts,"
            + " toUnixTimestamp64Milli(minIf(event_at, " + FAILED + ")) AS first_failed_ms,"
            + " toUnixTimestamp64Milli(maxIf(event_at, " + FAILED + ")) AS last_failed_ms,"
            + " argMaxIf(failure_class, event_at, " + FAILED + ") AS latest_class,"
            + " argMaxIf(reason, event_at, " + FAILED + ") AS latest_reason,"
            + " argMaxIf(message, event_at, " + FAILED + ") AS latest_message,"
            + " argMaxIf(path, event_at, " + FAILED + ") AS latest_path,"
            + " " + RETRY_MODE + " AS latest_mode"
            + " FROM " + TABLE
            + " GROUP BY kind, subject"
            + " HAVING argMax(event, event_at) != 'resolved' AND countIf(" + FAILED + ") > 0";

    private static final String LATEST_SQL = "SELECT argMax(event, event_at) AS latest, " + RETRY_MODE + " AS failed_mode,"
            + " count() AS events FROM " + TABLE
            + " WHERE kind = {kind:String} AND subject = {subject:String}";

    /** The {@code DateTime64(3)} spelling the row-write path also hands ClickHouse. */
    private static final DateTimeFormatter STORAGE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final UsageLogWriter writer;

    @Override
    public void append(Collection<IngestFailureEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>(events.size());
        for (IngestFailureEvent event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", event.kind().wire());
            row.put("subject", event.subject());
            row.put("day", event.day().toString());
            row.put("blob_last_modified", STORAGE.format(event.blobLastModified().truncatedTo(ChronoUnit.MILLIS)));
            row.put("event", event.type().wire());
            row.put("path", event.path());
            row.put("mode", event.mode());
            row.put("failure_class", event.reason() == null ? "" : event.reason().failureClass());
            row.put("reason", event.reason() == null ? "" : event.reason().wire());
            row.put("message", event.message());
            row.put("event_at", STORAGE.format(event.eventAt().truncatedTo(ChronoUnit.MILLIS)));
            rows.add(row);
        }
        writer.insert(TABLE, rows);
    }

    @Override
    public List<OpenIngestFailure> open() {
        List<OpenIngestFailure> open = new ArrayList<>();
        for (GenericRecord row : writer.query(OPEN_SQL, Map.of())) {
            IngestFailureKind kind = IngestFailureKind.fromWire(row.getString("kind"));
            if (kind == null) {
                continue;
            }
            open.add(new OpenIngestFailure(
                    kind,
                    row.getString("subject"),
                    LocalDate.parse(row.getString("failed_day")),
                    Instant.ofEpochMilli(row.getLong("blob_last_modified_ms")),
                    Instant.ofEpochMilli(row.getLong("reset_at_ms")),
                    (int) row.getLong("attempts"),
                    Instant.ofEpochMilli(row.getLong("first_failed_ms")),
                    Instant.ofEpochMilli(row.getLong("last_failed_ms")),
                    row.getString("latest_class"),
                    row.getString("latest_reason"),
                    row.getString("latest_message"),
                    row.getString("latest_path"),
                    row.getString("latest_mode")));
        }
        return open;
    }

    @Override
    public Optional<Latest> latestOf(IngestFailureKind kind, String subject) {
        for (GenericRecord row : writer.query(LATEST_SQL, Map.of("kind", kind.wire(), "subject", subject))) {
            if (row.getLong("events") == 0) {
                return Optional.empty();
            }
            String latest = row.getString("latest");
            for (IngestFailureEvent.Type type : IngestFailureEvent.Type.values()) {
                if (type.wire().equals(latest)) {
                    return Optional.of(new Latest(type, row.getString("failed_mode")));
                }
            }
        }
        return Optional.empty();
    }
}
