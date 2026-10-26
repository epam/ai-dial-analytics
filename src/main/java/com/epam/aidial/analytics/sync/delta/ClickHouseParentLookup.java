package com.epam.aidial.analytics.sync.delta;

import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The {@link ParentLookup} over the payload table, on the writer credential like the rest of ingest's own reads.
 *
 * <p>The candidates go out in chunks of {@value #CHUNK} per query. The client sends query parameters in the request
 * URI, which the server bounds ({@code http_max_uri_size}), so one query per flush could outgrow it. Each query is
 * bounded to the partitions the candidates can live in: a parent shares its child's UTC day, and the partition key is
 * {@code toYYYYMMDD(request_time)}. Within a partition the {@code idx_request_path} skip index (ClickHouse
 * {@code V16}) keeps the read off most granules, since the table is sorted by {@code event_id}, not by path. Values are
 * bound server-side as query parameters, each list as one comma-separated string, so a chunk's size never changes the
 * statement. No {@code FINAL}: every row carrying a path rebuilds to the same history, so any copy will do. The
 * rows carrying a path can differ in their tools, so up to {@value #TOOLS_HASHES} distinct tools hashes come back per
 * path; a child whose tools match none of them stores its own.
 */
public final class ClickHouseParentLookup implements ParentLookup {

    /** Candidate paths per query: about 37 URI bytes each, so a chunk stays well inside common URI limits. */
    static final int CHUNK = 1000;

    /** Distinct tools hashes returned per path. */
    static final int TOOLS_HASHES = 8;

    private final UsageLogWriter writer;
    private final String sql;

    public ClickHouseParentLookup(UsageLogWriter writer, String table) {
        this.writer = writer;
        this.sql = statement(table);
    }

    /**
     * The lookup statement for {@code table}, with parameters {@code days} (comma-separated {@code yyyyMMdd}),
     * {@code from}/{@code to} (a {@code DateTime64} range covering them) and {@code paths} (comma-separated UUIDs).
     * Public so a test can run the exact statement through {@code EXPLAIN}.
     */
    public static String statement(String table) {
        return "SELECT toString(request_path) AS path,"
                + " arrayStringConcat(arrayMap(x -> toString(x), any(request_ancestors)), ',') AS ancestors,"
                + " arrayStringConcat(arrayMap(x -> toString(x), groupUniqArray(" + TOOLS_HASHES + ")(request_tools_hash)), ',') AS tools"
                + " FROM `" + table.replace("`", "``") + "`"
                + " WHERE request_time >= {from:DateTime64(3, 'UTC')} AND request_time < {to:DateTime64(3, 'UTC')}"
                + " AND toYYYYMMDD(request_time) IN (SELECT toUInt32(arrayJoin(splitByChar(',', {days:String}))))"
                + " AND request_path IN (SELECT toUUID(arrayJoin(splitByChar(',', {paths:String}))))"
                + " GROUP BY request_path";
    }

    @Override
    public Map<UUID, Found> find(Collection<String> days, Collection<UUID> paths) {
        if (days.isEmpty() || paths.isEmpty()) {
            return Map.of();
        }
        LocalDate first = null;
        LocalDate last = null;
        List<String> partitions = new ArrayList<>(days.size());
        for (String day : days) {
            LocalDate date = LocalDate.parse(day);
            first = first == null || date.isBefore(first) ? date : first;
            last = last == null || date.isAfter(last) ? date : last;
            partitions.add(day.replace("-", ""));
        }
        List<String> candidates = new ArrayList<>(paths.size());
        paths.forEach(path -> candidates.add(path.toString()));
        Map<UUID, Found> found = new HashMap<>();
        for (int start = 0; start < candidates.size(); start += CHUNK) {
            Map<String, Object> params = Map.of(
                    "from", first + " 00:00:00.000",
                    "to", last.plusDays(1) + " 00:00:00.000",
                    "days", String.join(",", partitions),
                    "paths", String.join(",", candidates.subList(start, Math.min(candidates.size(), start + CHUNK))));
            for (GenericRecord row : writer.query(sql, params)) {
                found.put(UUID.fromString(row.getString("path")), new Found(uuids(row.getString("ancestors")), uuids(row.getString("tools"))));
            }
        }
        return found;
    }

    private static List<UUID> uuids(String joined) {
        List<UUID> uuids = new ArrayList<>();
        if (joined != null && !joined.isEmpty()) {
            for (String uuid : joined.split(",")) {
                uuids.add(UUID.fromString(uuid));
            }
        }
        return uuids;
    }
}
