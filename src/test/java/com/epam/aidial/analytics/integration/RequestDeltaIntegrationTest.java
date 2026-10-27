package com.epam.aidial.analytics.integration;

import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.sync.delta.ClickHouseParentLookup;
import com.epam.aidial.analytics.sync.delta.Rebuild;
import com.epam.aidial.analytics.sync.delta.RequestBodyMode;
import com.epam.aidial.analytics.sync.delta.RequestDeltaEncoder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Request-delta storage against a real ClickHouse: the {@code V16} columns and skip index, the parent lookup
 * {@link ClickHouseParentLookup} issues (chunked, pruned to the day, through the hash index), cold encoders — a
 * restart, or another instance — linking to parents they only find in the table, re-ingest and mode switches leaving
 * every row rebuildable under a deduplicated read, tools inherited through the table, and the catalog exposing the
 * cache markers while hiding the chain columns.
 */
class RequestDeltaIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PAYLOAD = "dial_usage_log_payload";
    private static final String USER = "user-aa";

    /** Stands for SQL NULL in a read, since the rows come back as strings. */
    private static final String NULL = "<null>";

    @Autowired
    private UsageLogWriter writer;

    @BeforeEach
    void cleanSlate() {
        truncate();
    }

    @Test
    void theMigrationAddsTheColumnsAndTheHashIndex() {
        assertThat(showCreateTable(PAYLOAD))
                .contains("`project_id` LowCardinality(String)", "`deployment` LowCardinality(String)", "`user_hash` String",
                        "`session_id` Nullable(String)", "`request_hash` UUID", "`request_parent_hash` UUID",
                        "`request_ancestor_hashes` Array(UUID)",
                        "`request_cache_control` Nullable(String)", "INDEX idx_request_hash request_hash TYPE bloom_filter(0.001) GRANULARITY 1");
    }

    @Test
    void coldEncoderLinksToParentFoundInTheTable() {
        Map<String, Object> first = row("t1", "10:00:00.000", messages("user:u1"));
        write(newEncoder(), first);

        Map<String, Object> second = row("t2", "10:01:00.000", messages("user:u1", "assistant:a1", "user:u2"));
        write(newEncoder(), second);
        Map<String, Object> third = row("t3", "10:02:00.000", messages("user:u1", "assistant:a1", "user:u2", "assistant:a2", "user:u3"));
        write(newEncoder(), third);

        assertThat(second.get("request_parent_hash")).isEqualTo(first.get("request_hash"));
        assertThat(third.get("request_parent_hash")).isEqualTo(second.get("request_hash"));
        assertThat(third.get("request_ancestor_hashes")).isEqualTo(List.of(first.get("request_hash"), second.get("request_hash")));
        assertThat(queryScalar("SELECT request_body FROM " + PAYLOAD + " WHERE trace_id = 't3' SETTINGS final = 1", "request_body"))
                .isEqualTo("{\"messages\":[{\"content\":\"a2\",\"role\":\"assistant\"},{\"content\":\"u3\",\"role\":\"user\"}],\"model\":\"m\"}");
        assertThat(queryScalar("SELECT arrayStringConcat(arrayMap(x -> toString(x), request_ancestor_hashes), ',') AS a FROM " + PAYLOAD
                + " WHERE trace_id = 't3'", "a")).isEqualTo(first.get("request_hash") + "," + second.get("request_hash"));
    }

    @Test
    void theLookupFindsOnlyPathsOfTheRequestedDays() {
        Map<String, Object> stored = row("t1", "10:00:00.000", messages("user:u1"));
        write(newEncoder(), stored);
        UUID path = UUID.fromString((String) stored.get("request_hash"));
        ClickHouseParentLookup lookup = new ClickHouseParentLookup(writer, PAYLOAD);

        assertThat(lookup.find(List.of(FIXTURE_DATE), List.of(path, UUID.randomUUID()))).containsOnlyKeys(path);
        assertThat(lookup.find(List.of(FIXTURE_DATE), List.of(path)).get(path)).isEmpty();
        assertThat(lookup.find(List.of("2001-01-01"), List.of(path))).isEmpty();
    }

    /** The production statement, parameters inlined: its plan prunes by the partition key and uses the hash index. */
    @Test
    void theLookupStatementUsesThePartitionKeyAndTheHashIndex() {
        write(newEncoder(), row("t1", "10:00:00.000", messages("user:u1")));
        String statement = ClickHouseParentLookup.statement(PAYLOAD)
                .replace("{from:DateTime64(3, 'UTC')}", "toDateTime64('" + FIXTURE_DATE + " 00:00:00.000', 3, 'UTC')")
                .replace("{to:DateTime64(3, 'UTC')}", "toDateTime64('" + LocalDate.parse(FIXTURE_DATE).plusDays(1) + " 00:00:00.000', 3, 'UTC')")
                .replace("{days:String}", "'" + FIXTURE_DATE.replace("-", "") + "'")
                .replace("{paths:String}", "'" + UUID.randomUUID() + "'");

        String plan = queryText("EXPLAIN indexes = 1 " + statement);

        assertThat(plan).contains("Partition", "idx_request_hash");
    }

    /** A deduplicated read after re-ingest: one row per event, and every one rebuilds to the body that was sent. */
    @Test
    void reingestLeavesOneRebuildableRowPerEvent() {
        String historyA = messages("user:u1");
        String historyB = messages("user:u1", "assistant:a1", "user:u2");
        String historyC = messages("user:u1", "assistant:a1", "user:u2", "assistant:a2").replaceFirst("]$", ",")
                + "{\"role\":\"user\",\"content\":\"u3\",\"cache_control\":{\"type\":\"ephemeral\"}}]";
        write(newEncoder(), row("t2", "10:01:00.000", historyB));
        write(newEncoder(), row("t3", "10:02:00.000", historyC));
        write(newEncoder(), row("t1", "10:00:00.000", historyA));
        write(newEncoder(), row("t2", "10:01:00.000", historyB)); // re-ingested: now A's child, with a shorter delta

        Map<String, Map<String, Object>> rows = storedRows();

        assertThat(rows).hasSize(3);
        assertThat(rows.get("t2").get("request_parent_hash")).isEqualTo(rows.get("t1").get("request_hash"));
        assertRebuilds(rows, "t1", historyA);
        assertRebuilds(rows, "t2", historyB);
        assertRebuilds(rows, "t3", historyC);
    }

    /** A string cut inside an emoji: the escaped lone surrogates are inserted and read back as sent. */
    @Test
    void escapedLoneSurrogatesAreInsertedAndRebuild() {
        String first = "[{\"role\":\"user\",\"content\":\"cut \\ud800 here \\udc00 and \\ud83d\"}]";
        String second = first.replaceFirst("]$", ",") + "{\"role\":\"assistant\",\"content\":\"ok\"}]";
        write(newEncoder(), row("t1", "10:00:00.000", first));
        write(newEncoder(), row("t2", "10:01:00.000", second));

        Map<String, Map<String, Object>> rows = storedRows();

        assertThat(rows.get("t2").get("request_parent_hash")).isEqualTo(rows.get("t1").get("request_hash"));
        assertRebuilds(rows, "t1", first);
        assertRebuilds(rows, "t2", second);
    }

    @Test
    void neitherAnotherUserNorTheDayBeforeIsParent() {
        String first = messages("user:u1");
        Map<String, Object> lateYesterday = row("t1", "10:00:00.000", first);
        lateYesterday.put("request_time", LocalDate.parse(FIXTURE_DATE).minusDays(1) + " 23:58:00.000");
        write(newEncoder(), lateYesterday);
        Map<String, Object> otherUser = row("t2", "00:01:00.000", first);
        otherUser.put("user_hash", "user-bb");
        write(newEncoder(), otherUser);

        Map<String, Object> earlyToday = row("t3", "00:03:00.000", messages("user:u1", "assistant:a1", "user:u2"));
        write(newEncoder(), earlyToday);

        assertThat(earlyToday.get("request_parent_hash")).isEqualTo(new UUID(0, 0).toString());
        assertThat(Rebuild.MAPPER.readTree((String) earlyToday.get("request_body")).get("messages")).hasSize(3);
    }

    @Test
    void modeSwitchesKeepEveryRowRebuildable() {
        String historyA = messages("user:u1");
        String historyB = messages("user:u1", "assistant:a1", "user:u2");
        String historyC = messages("user:u1", "assistant:a1", "user:u2", "assistant:a2", "user:u3");
        write(newEncoder(RequestBodyMode.WHOLE), row("t1", "10:00:00.000", historyA)); // before delta was switched on
        write(newEncoder(), row("t2", "10:01:00.000", historyB));                       // delta: links onto the whole row
        write(newEncoder(), row("t3", "10:02:00.000", historyC));
        write(newEncoder(RequestBodyMode.WHOLE), row("t2", "10:01:00.000", historyB)); // rolled back, B backfilled

        Map<String, Map<String, Object>> rows = storedRows();

        assertThat(rows.get("t1").get("request_body")).isNotNull();
        assertThat(rows.get("t2").get("request_body")).isNotNull();
        assertThat(rows.get("t3").get("request_parent_hash")).isEqualTo(rows.get("t2").get("request_hash"));
        assertRebuilds(rows, "t3", historyC);
    }

    /** Thousands of candidates go out in several bounded queries, and every deep request still finds its parent. */
    @Test
    void manyDeepRequestsAreLinkedThroughChunkedLookups() {
        List<Map<String, Object>> parents = new ArrayList<>();
        List<Map<String, Object>> children = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            List<String> history = new ArrayList<>();
            for (int m = 0; m < 70; m++) {
                history.add("user:c" + i + "-" + m);
            }
            parents.add(row("p" + i, String.format("09:%02d:%02d.000", i / 60, i % 60), messages(history.toArray(String[]::new))));
            history.add("assistant:x");
            children.add(row("c" + i, String.format("10:%02d:%02d.000", i / 60, i % 60), messages(history.toArray(String[]::new))));
        }
        writeAll(newEncoder(), parents);

        writeAll(newEncoder(), children); // cold: 200 rows x 8 prefixes = 1600 candidates, two queries

        for (int i = 0; i < 200; i++) {
            assertThat(children.get(i).get("request_parent_hash")).isEqualTo(parents.get(i).get("request_hash"));
        }
    }

    /** A cold encoder finds the parent in the table, leaves the tools out, and the rebuild takes them from the root. */
    @Test
    void toolsAreInheritedThroughTheTable() {
        String tools = ",\"tools\":[{\"name\":\"search\"}]";
        write(newEncoder(), row("t1", "10:00:00.000", messages("user:u1"), tools));
        Map<String, Object> second = row("t2", "10:01:00.000", messages("user:u1", "assistant:a1", "user:u2"), tools);
        write(newEncoder(), second);

        Map<String, Map<String, Object>> rows = storedRows();

        assertThat((String) rows.get("t2").get("request_body")).doesNotContain("\"tools\"");
        assertThat(Rebuild.canonical(Rebuild.body(rows.get("t2"), rows.values())))
                .isEqualTo(Rebuild.canonical(body(messages("user:u1", "assistant:a1", "user:u2"), tools)));
    }

    @Test
    void theCatalogExposesTheCacheMarkersAsSensitiveAndHeavyAndHidesTheChain() throws Exception {
        mockMvc.perform(get("/v1/tables/" + PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[?(@.name == 'request_cache_control')].sensitive").value(hasItem(true)))
                .andExpect(jsonPath("$.columns[?(@.name == 'request_cache_control')].heavy").value(hasItem(true)))
                .andExpect(jsonPath("$.columns[?(@.name == 'request_cache_control')].nullable").value(hasItem(true)))
                .andExpect(jsonPath("$.columns[*].name", not(hasItem("request_hash"))))
                .andExpect(jsonPath("$.columns[*].name", not(hasItem("request_ancestor_hashes"))));
        mockMvc.perform(get("/v1/queries/entities/schema/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..name", hasItem("dial_usage_log_payload.request_cache_control")))
                .andExpect(jsonPath("$..name", not(hasItem("dial_usage_log_payload.request_parent_hash"))));
    }

    private RequestDeltaEncoder newEncoder() {
        return newEncoder(RequestBodyMode.DELTA);
    }

    private RequestDeltaEncoder newEncoder(RequestBodyMode mode) {
        return new RequestDeltaEncoder(mode, new ClickHouseParentLookup(writer, PAYLOAD), 1000, new SimpleMeterRegistry(), PAYLOAD);
    }

    private void write(RequestDeltaEncoder encoder, Map<String, Object> row) {
        writeAll(encoder, List.of(row));
    }

    /** Encodes, inserts and reports a batch written — what the payload sink does on a successful flush. */
    private void writeAll(RequestDeltaEncoder encoder, List<Map<String, Object>> rows) {
        List<Map<String, Object>> batch = new ArrayList<>(rows);
        RequestDeltaEncoder.Encoding encoding = encoder.encode(batch);
        writer.insert(PAYLOAD, batch);
        encoding.written();
    }

    /** Every payload row of the test, as a deduplicated read sees it, keyed by trace id. */
    private Map<String, Map<String, Object>> storedRows() {
        List<String> columns = List.of("request_hash", "request_parent_hash", "request_body", "request_cache_control");
        Map<String, Map<String, Object>> rows = new HashMap<>();
        for (GenericRecord record : writer.query("SELECT trace_id, toString(request_hash) AS request_hash,"
                + " toString(request_parent_hash) AS request_parent_hash, ifNull(request_body, '" + NULL + "') AS request_body,"
                + " ifNull(request_cache_control, '" + NULL + "') AS request_cache_control"
                + " FROM " + PAYLOAD + " SETTINGS final = 1", Map.of())) {
            Map<String, Object> row = new HashMap<>();
            for (String column : columns) {
                String value = record.getString(column);
                row.put(column, NULL.equals(value) ? null : value);
            }
            rows.put(record.getString("trace_id"), row);
        }
        return rows;
    }

    private static void assertRebuilds(Map<String, Map<String, Object>> rows, String traceId, String messages) {
        assertThat(Rebuild.canonical(Rebuild.body(rows.get(traceId), rows.values())))
                .as(traceId).isEqualTo(Rebuild.canonical(body(messages)));
    }

    private static Map<String, Object> row(String traceId, String timeOfDay, String messages) {
        return row(traceId, timeOfDay, messages, "");
    }

    private static Map<String, Object> row(String traceId, String timeOfDay, String messages, String members) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", eventIdFor(traceId));
        row.put("request_time", fixtureTime(timeOfDay));
        row.put("trace_id", traceId);
        row.put("core_span_id", "s-" + traceId);
        row.put("request_body", body(messages, members));
        row.put("project_id", "project-1");
        row.put("deployment", "deployment-1");
        row.put("user_hash", USER);
        return row;
    }

    private static String body(String messages) {
        return body(messages, "");
    }

    /** A chat body with {@code members} (each with its leading comma) between the model and the messages. */
    private static String body(String messages, String members) {
        return "{\"model\":\"m\"" + members + ",\"messages\":" + messages + "}";
    }

    /** A JSON message array from {@code role:content} pairs. */
    private static String messages(String... turns) {
        List<String> json = new ArrayList<>();
        for (String turn : turns) {
            String[] parts = turn.split(":", 2);
            json.add("{\"role\":\"" + parts[0] + "\",\"content\":\"" + parts[1] + "\"}");
        }
        return "[" + String.join(",", json) + "]";
    }
}
