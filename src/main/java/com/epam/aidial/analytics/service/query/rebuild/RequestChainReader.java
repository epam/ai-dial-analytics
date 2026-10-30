package com.epam.aidial.analytics.service.query.rebuild;

import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseClientWrapper;
import com.epam.aidial.analytics.clickhouse.FinalSettings;
import com.epam.aidial.analytics.service.query.ComputedFields;
import com.epam.aidial.analytics.sync.delta.RequestBodyRebuild;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The two statements a request-body rebuild reads with (see {@code openspec/specs/data-sync/spec.md}, "A rebuilt
 * request body is readable beside the stored one"), on the read-only client and always inside one UTC-day partition
 * of the payload table, where a chain lives.
 *
 * <ul>
 *   <li>{@link #links} resolves paths from the small chain columns alone — no body — through the
 *       {@code idx_request_hash} skip index. {@code LIMIT 1 BY request_hash} is sound because every row carrying a
 *       path yields the same history.
 *   <li>{@link #bodies} reads the bodies of the rows a walk visited by their {@code event_id}, the table's sort key, so
 *       the read prunes to the granules holding them.
 * </ul>
 *
 * <p>Both read {@code FINAL}, rendered and as the {@code final} setting, with deduplication kept inside each partition,
 * whatever the caller's statement did: a walk is correct only when a row's link and its body come from one version.
 * Lists are bound as server-side parameters, one comma-separated string each and at most {@value #CHUNK} values per
 * statement, because the client sends parameters in the request URI (the {@code ClickHouseParentLookup} rationale).
 *
 * <p>Each statement asks for its settings when it is about to run, so it carries what is left of the triggering read's
 * wall-clock ceiling at that moment; when nothing is left the statement is not issued and {@link BudgetSpentException}
 * is thrown in its place.
 */
@Component
@RequiredArgsConstructor
public class RequestChainReader {

    /** Values per statement: about 37 URI bytes each, so a statement stays well inside common URI limits. */
    static final int CHUNK = 1000;

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private static final String LINKS = statement(
            "toString(request_hash) AS path, toString(request_parent_hash) AS parent,"
                    + " arrayStringConcat(arrayMap(x -> toString(x), request_ancestor_hashes), ',') AS ancestors,"
                    + " toString(event_id) AS event_id",
            "request_hash IN (SELECT toUUID(arrayJoin(splitByChar(',', {paths:String}))))")
            + " LIMIT 1 BY request_hash";

    private static final String BODIES = statement(
            "toString(event_id) AS event_id, toString(request_hash) AS path, toString(request_parent_hash) AS parent,"
                    + " request_body AS body",
            "event_id IN (SELECT toUUID(arrayJoin(splitByChar(',', {events:String}))))");

    private final ClickHouseClientWrapper clickHouse;

    /**
     * A path as phase 1 found it.
     *
     * @param path      its {@code request_hash}
     * @param parent    the parent path of the row found carrying it
     * @param ancestors that row's {@code request_ancestor_hashes} hint
     * @param eventId   that row's {@code event_id}
     */
    public record Link(UUID path, UUID parent, List<UUID> ancestors, String eventId) {}

    /** Thrown in place of a statement the read's wall-clock ceiling has no time left for; nothing was issued. */
    public static final class BudgetSpentException extends RuntimeException {

        BudgetSpentException() {
            super("the read's wall-clock ceiling has no time left for a request body rebuild statement");
        }
    }

    /**
     * The phase-1 statement: parameters {@code from}/{@code to} (the day's {@code DateTime64} bounds), {@code days}
     * (the day as {@code yyyyMMdd}) and {@code paths} (comma-separated UUIDs). Public so a test can run it through
     * {@code EXPLAIN}.
     */
    public static String linksStatement() {
        return LINKS;
    }

    /** The phase-2 statement: as {@link #linksStatement}, with {@code events} (comma-separated UUIDs) for the paths. */
    public static String bodiesStatement() {
        return BODIES;
    }

    /** The parameters of either statement for {@code day} and one chunk of {@code values}, bound as {@code name}. */
    public static Map<String, Object> parameters(int day, String name, List<String> values) {
        final LocalDate date = LocalDate.parse(Integer.toString(day), DAY);
        return Map.of(
                "from", date + " 00:00:00.000",
                "to", date.plusDays(1) + " 00:00:00.000",
                "days", Integer.toString(day),
                name, String.join(",", values));
    }

    /**
     * The rows carrying {@code paths} in day {@code day}'s partition, one per path found.
     *
     * @param settings asked once per statement; {@code null} means no time is left
     * @throws BudgetSpentException when {@code settings} answers {@code null} before a statement
     */
    public List<Link> links(int day, Collection<UUID> paths, Supplier<QuerySettings> settings) {
        final List<Link> found = new ArrayList<>();
        for (final List<String> chunk : chunks(paths.stream().map(UUID::toString).toList())) {
            for (final GenericRecord row : clickHouse.query(LINKS, parameters(day, "paths", chunk), dedup(settings.get()))) {
                final List<UUID> ancestors = new ArrayList<>();
                final String hint = row.getString("ancestors");
                if (hint != null && !hint.isEmpty()) {
                    for (final String ancestor : hint.split(",")) {
                        ancestors.add(UUID.fromString(ancestor));
                    }
                }
                found.add(new Link(UUID.fromString(row.getString("path")), UUID.fromString(row.getString("parent")),
                        List.copyOf(ancestors), row.getString("event_id")));
            }
        }
        return found;
    }

    /** The current version of each of {@code eventIds} in day {@code day}'s partition; {@code settings} as for {@link #links}. */
    public List<RequestBodyRebuild.Ancestor> bodies(int day, Collection<String> eventIds, Supplier<QuerySettings> settings) {
        final List<RequestBodyRebuild.Ancestor> found = new ArrayList<>();
        for (final List<String> chunk : chunks(List.copyOf(eventIds))) {
            for (final GenericRecord row : clickHouse.query(BODIES, parameters(day, "events", chunk), dedup(settings.get()))) {
                found.add(new RequestBodyRebuild.Ancestor(row.getString("event_id"), UUID.fromString(row.getString("path")),
                        UUID.fromString(row.getString("parent")), row.getString("body")));
            }
        }
        return found;
    }

    private static String statement(String projection, String condition) {
        return "SELECT " + projection
                + " FROM `" + ComputedFields.TABLE + "` FINAL"
                + " WHERE request_time >= {from:DateTime64(3, 'UTC')} AND request_time < {to:DateTime64(3, 'UTC')}"
                + " AND toYYYYMMDD(request_time) IN (SELECT toUInt32(arrayJoin(splitByChar(',', {days:String}))))"
                + " AND " + condition;
    }

    private static List<List<String>> chunks(List<String> values) {
        final List<List<String>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += CHUNK) {
            chunks.add(values.subList(start, Math.min(values.size(), start + CHUNK)));
        }
        return chunks;
    }

    /** {@code settings} with the per-partition deduplication added; the caller's object is not modified. */
    private static QuerySettings dedup(QuerySettings settings) {
        if (settings == null) {
            throw new BudgetSpentException();
        }
        final QuerySettings copy = FinalSettings.mergedOver(new QuerySettings(), settings);
        copy.serverSetting(FinalSettings.FINAL, "1");
        copy.serverSetting(FinalSettings.NO_MERGE_ACROSS_PARTITIONS, "1");
        return copy;
    }
}
