package com.epam.aidial.analytics.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The partition floor every cursor-bounded read carries on a partitioned source (see
 * {@code CursorPredicates}): that it prunes for real under the pipeline's own {@code final=1} settings, that it
 * changes no result — including for a late row whose partition is days older than the rest of its window —
 * and that it reaches the payload grain join's key sub-selects.
 *
 * <p>Result identity for the Class-1 and aggregate paths is carried by their own integration classes: the
 * seeded {@code dial_usage_log} declares {@code partition_column = request_time}, so every window those classes
 * assert row-for-row already runs with the floor.
 *
 * <p>Rows are spread over the last few days rather than one constant date, because the floor only has something
 * to prune when the source holds more than one partition — and within the payload table's 90-day TTL, because
 * one test joins it. {@code _ingested_at} is past-dated with the safety lag off, as in the scan tests.
 */
@TestPropertySource(properties = {AbstractClickHouseIntegrationTest.SCAN_SAFETY_LAG_OFF,
        AbstractClickHouseIntegrationTest.GROUP_FETCH_MAX_BYTES_4KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_WINDOW_LIMIT_3,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_MEMORY_1KB,
        AbstractClickHouseIntegrationTest.AGGREGATE_PREVIEW_SPILL_OFF})
class CursorPartitionFloorIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String ENRICHMENT = "floor_tox";
    private static final String FLOOR = "\"dial_usage_log\".\"request_time\" >= (select min(";

    @BeforeEach
    void setUp() throws Exception {
        truncate();
        IntegrationTables.enrichment(mockMvc, ENRICHMENT, SOURCE, "event_id",
                "[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]");
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + ENRICHMENT));
    }

    /** Midnight UTC {@code daysAgo} days before today, as a ClickHouse literal. */
    private static String day(int daysAgo) {
        return LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo) + " 00:00:00.000";
    }

    private void seedRow(String eventId, String requestTime, String ingestedAt) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, _ingested_at, total_tokens) VALUES (toUUID('" + eventId + "'),'p','d','"
                + requestTime + "','t','" + eventId + "','" + ingestedAt + "', 1)");
    }

    /** {@code count} rows in the {@code daysAgo} partition, ingested in one early burst. */
    private void seedBulk(int daysAgo, int count) {
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id,"
                + " core_span_id, _ingested_at, total_tokens) SELECT generateUUIDv4(), 'p', 'd', '" + day(daysAgo)
                + "', 'bulk', concat('" + daysAgo + "-', toString(number)),"
                + " toDateTime64('2020-01-01 00:00:00.000', 3, 'UTC') + toIntervalMillisecond(number), 1"
                + " FROM numbers(" + count + ")");
    }

    private String createPipeline(String name, String filter) throws Exception {
        String body = "{\"name\":\"" + name + "\","
                + Transforms.llm().proseOutputs("tox_score").member() + ","
                + "\"kind\":\"enrich\",\"target\":\"" + ENRICHMENT + "\",\"trigger\":{\"kind\":\"schedule\"},"
                + "\"filter\":\"" + filter + "\",\"enabled\":true}";
        String content = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(content, "$.name");
    }

    private String scan(String pipeline, String body) throws Exception {
        return mockMvc.perform(runnerPost("/v1/pipelines/" + pipeline + "/scan")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<String> eventIds(String page) {
        return JsonPath.read(page, "$.rows[*].event_id");
    }

    /** The most recent finished scan statement of {@code pipeline}, read from ClickHouse's own query log. */
    private String lastScan(String pipeline, String column) {
        execute("SYSTEM FLUSH LOGS");
        return queryScalar("SELECT " + column + " FROM system.query_log WHERE type = 'QueryFinish'"
                + " AND query_id LIKE 'adas-" + pipeline + "-scan-%' ORDER BY event_time_microseconds DESC LIMIT 1",
                column);
    }

    /**
     * Ingest order deliberately zig-zags across partitions, so consecutive pages land in different days and the
     * floor each seek derives moves backwards as well as forwards; paging must still yield every row once.
     */
    @Test
    void pagingAcrossPartitionsReturnsEveryRowExactlyOnce() throws Exception {
        final int[] daysAgo = {0, 3, 1, 3, 0, 2, 1, 0};
        final List<String> expected = new ArrayList<>();
        for (int i = 0; i < daysAgo.length; i++) {
            final String id = String.format("00000000-0000-0000-0000-%012d", i + 1);
            seedRow(id, day(daysAgo[i]), "2020-01-01 00:00:0" + (i + 1) + ".000");
            expected.add(id);
        }
        final String pipeline = createPipeline("floor-paging", "total_tokens > 0");

        final List<String> seen = new ArrayList<>();
        String page = scan(pipeline, "{\"limit\":3}");
        seen.addAll(eventIds(page));
        while (JsonPath.<Boolean>read(page, "$.has_more")) {
            page = scan(pipeline, "{\"cursor\":\"" + JsonPath.read(page, "$.next_cursor") + "\",\"limit\":3}");
            seen.addAll(eventIds(page));
        }

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(lastScan(pipeline, "query")).contains(FLOOR);
    }

    /**
     * The case a fixed lag would get wrong: a row ingested after the cursor but belonging to a partition days
     * older than anything else in its window. The derived minimum moves back to admit it.
     */
    @Test
    void lateRowInAnOldPartitionIsReadExactlyOnce() throws Exception {
        seedRow("00000000-0000-0000-0000-000000000001", day(0), "2020-01-01 00:00:01.000");
        seedRow("00000000-0000-0000-0000-000000000002", day(0), "2020-01-01 00:00:02.000");
        final String pipeline = createPipeline("floor-late", "total_tokens > 0");
        final String caughtUp = scan(pipeline, "{\"limit\":10}");
        assertThat(eventIds(caughtUp)).hasSize(2);
        final String cursor = JsonPath.read(caughtUp, "$.next_cursor");

        seedRow("00000000-0000-0000-0000-000000000003", day(6), "2020-01-01 00:00:03.000");

        final String next = scan(pipeline, "{\"cursor\":\"" + cursor + "\",\"limit\":10}");
        assertThat(eventIds(next)).containsExactly("00000000-0000-0000-0000-000000000003");
        final String after = scan(pipeline, "{\"cursor\":\"" + JsonPath.read(next, "$.next_cursor") + "\",\"limit\":10}");
        assertThat(eventIds(after)).isEmpty();
    }

    /**
     * The floor prunes under the pipeline's own settings, which is the whole point: a plain version-column bound
     * is expanded back to the full table by {@code final=1}. Three old partitions hold the bulk of the rows, all
     * ingested before the window; the read must not touch them, through either lower bound — the caller's
     * {@code from} on the first page, the keyset seek on the next.
     */
    @Test
    void windowReadSkipsPartitionsItCannotReach() throws Exception {
        for (int d = 1; d <= 3; d++) {
            seedBulk(d, 2_000);
        }
        seedRow("00000000-0000-0000-0000-000000000001", day(0), "2020-01-02 00:00:01.000");
        seedRow("00000000-0000-0000-0000-000000000002", day(0), "2020-01-02 00:00:02.000");
        final String pipeline = createPipeline("floor-prune", "total_tokens > 0");

        final String first = scan(pipeline, "{\"from\":\"2020-01-01T12:00:00Z\",\"limit\":1}");
        assertThat(eventIds(first)).containsExactly("00000000-0000-0000-0000-000000000001");
        assertThat(Long.parseLong(lastScan(pipeline, "read_rows"))).isLessThan(2_000);

        final String second = scan(pipeline, "{\"cursor\":\"" + JsonPath.read(first, "$.next_cursor") + "\",\"limit\":1}");
        assertThat(eventIds(second)).containsExactly("00000000-0000-0000-0000-000000000002");
        assertThat(Long.parseLong(lastScan(pipeline, "read_rows"))).isLessThan(2_000);
    }

    /**
     * The payload table carries a same-named {@code request_time}, so the floor must be qualified to execute at
     * all; and the grain join's key sub-selects reuse the source predicates, so they carry it too — which is
     * what bounds the payload side of a window to the reachable partitions.
     */
    @Test
    void payloadJoinExecutesAndItsKeySubSelectsCarryTheFloor() throws Exception {
        for (int d = 1; d <= 2; d++) {
            seedBulk(d, 500);
        }
        final String eventId = "00000000-0000-0000-0000-000000000001";
        seedRow(eventId, day(0), "2020-01-02 00:00:01.000");
        execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id, request_body)"
                + " VALUES (toUUID('" + eventId + "'), '" + day(0) + "', 't', '" + eventId + "', '{\"model\":\"m1\"}')");
        final String pipeline = createPipeline("floor-payload",
                "json_extract_string(\\\"dial_usage_log_payload.request_body\\\", 'model') = 'm1'");

        final String page = scan(pipeline, "{\"from\":\"2020-01-01T12:00:00Z\",\"limit\":10}");

        assertThat(eventIds(page)).containsExactly(eventId);
        final String query = lastScan(pipeline, "query");
        assertThat(query).contains("dial_usage_log_payload");
        assertThat(query.split(java.util.regex.Pattern.quote(FLOOR), -1).length - 1).isGreaterThanOrEqualTo(2);
    }
}
