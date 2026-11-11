package com.epam.aidial.analytics.sync.delta;

import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.clickhouse.QueryParameterChunks;
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
 * <p>The candidates go out in chunks of {@value QueryParameterChunks#SIZE} per query ({@link QueryParameterChunks}), so
 * one query per flush cannot outgrow the request URI the client sends its parameters in. Each query is
 * bounded to the partitions the candidates can live in: a parent shares its child's UTC day, and the partition key is
 * {@code toYYYYMMDD(request_time)}. Within a partition the {@code idx_request_hash} skip index (ClickHouse
 * {@code V16}) keeps the read off most granules, since the table is sorted by {@code event_id}, not by path. Values are
 * bound server-side as query parameters, each list as one comma-separated string, so a chunk's size never changes the
 * statement. No {@code FINAL}: every row carrying a path rebuilds to the same history up to the shape of a bare text block, so any copy will do. Each query
 * runs under {@code max_execution_time}. The bound is per query, not per lookup: a flush asks about at most
 * {@code RequestDeltaEncoder.MAX_TABLE_CANDIDATES} paths in chunks of {@value QueryParameterChunks#SIZE}, so its lookup can take up to
 * about 17 times {@link #TIMEOUT_SECONDS}. A query that times out ends the lookup, and its requests are stored as chain
 * roots.
 */
public final class ClickHouseParentLookup implements ParentLookup {

    /**
     * Server-side bound on one lookup query, not on the whole lookup of a flush, which sends one query per chunk.
     * Ingest flushes one batch at a time, so a slow lookup holds up every flush behind it; a query that times out
     * fails the lookup like any other error, and its requests are stored as chain roots.
     */
    static final int TIMEOUT_SECONDS = 10;

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
        return "SELECT toString(request_hash) AS path,"
                + " arrayStringConcat(arrayMap(x -> toString(x), any(request_ancestor_hashes)), ',') AS ancestors"
                + " FROM `" + table.replace("`", "``") + "`"
                + " WHERE request_time >= {from:DateTime64(3, 'UTC')} AND request_time < {to:DateTime64(3, 'UTC')}"
                + " AND toYYYYMMDD(request_time) IN (SELECT toUInt32(arrayJoin(splitByChar(',', {days:String}))))"
                + " AND request_hash IN (SELECT toUUID(arrayJoin(splitByChar(',', {paths:String}))))"
                + " GROUP BY request_hash"
                + " SETTINGS max_execution_time = " + TIMEOUT_SECONDS;
    }

    @Override
    public Map<UUID, List<UUID>> find(Collection<String> days, Collection<UUID> paths) {
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
        Map<UUID, List<UUID>> found = new HashMap<>();
        for (List<String> chunk : QueryParameterChunks.of(candidates)) {
            Map<String, Object> params = Map.of(
                    "from", first + " 00:00:00.000",
                    "to", last.plusDays(1) + " 00:00:00.000",
                    "days", String.join(",", partitions),
                    "paths", String.join(",", chunk));
            for (GenericRecord row : writer.query(sql, params)) {
                String ancestors = row.getString("ancestors");
                List<UUID> lineage = new ArrayList<>();
                if (ancestors != null && !ancestors.isEmpty()) {
                    for (String ancestor : ancestors.split(",")) {
                        lineage.add(UUID.fromString(ancestor));
                    }
                }
                found.put(UUID.fromString(row.getString("path")), lineage);
            }
        }
        return found;
    }
}
