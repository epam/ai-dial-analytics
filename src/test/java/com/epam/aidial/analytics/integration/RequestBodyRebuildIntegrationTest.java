package com.epam.aidial.analytics.integration;

import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseClientWrapper;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.RequestBodyRebuildProperties;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.rebuild.ComputedFieldCompleter;
import com.epam.aidial.analytics.service.query.rebuild.RequestChainReader;
import com.epam.aidial.analytics.service.query.repository.QueryResultPage;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.security.RowPolicyFilter;
import com.epam.aidial.analytics.sync.delta.ClickHouseParentLookup;
import com.epam.aidial.analytics.sync.delta.Rebuild;
import com.epam.aidial.analytics.sync.delta.RequestBodyMode;
import com.epam.aidial.analytics.sync.delta.RequestDeltaEncoder;
import com.epam.aidial.analytics.web.security.Privilege;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rebuilt request body end to end against a real ClickHouse: rows written by the production encoder and writer
 * (as {@code RequestDeltaIntegrationTest} writes them), read back through the query surfaces, and the two rebuild
 * statements' plans and costs checked on the server itself. Specified in {@code openspec/specs/data-sync/spec.md}
 * ("A rebuilt request body is readable beside the stored one") and the structured-query and SQL deltas.
 */
class RequestBodyRebuildIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PAYLOAD = "dial_usage_log_payload";
    private static final String FULL = "dial_usage_log_payload.request_body_full";
    private static final String STATUS = "dial_usage_log_payload.request_body_rebuild";
    private static final String BODY = "dial_usage_log_payload.request_body";
    private static final String USER = "user-aa";

    @Autowired
    private UsageLogWriter writer;
    @Autowired
    private JsonMapper json;
    @Autowired
    private MeterRegistry registry;
    @Autowired
    private CatalogQueryService catalog;
    @Autowired
    private StructuredQueryBuilder builder;
    @Autowired
    private DSLContext renderContext;
    @Autowired
    private ClickHouseClientWrapper clickHouse;
    @Autowired
    private RowPolicyFilter rowPolicies;
    @Autowired
    private RequestChainReader reader;

    @BeforeEach
    void cleanSlate() {
        truncate();
    }

    @Test
    void followUpTurnReadsBackWholeWhileRequestBodyStaysTheDelta() throws Exception {
        String a = messages("user:u1");
        String b = messages("user:u1", "assistant:a1", "user:u2");
        String c = messages("user:u1", "assistant:a1", "user:u2", "assistant:a2", "user:u3");
        turn("t1", "10:00:00.000", a);
        turn("t2", "10:01:00.000", b);
        Map<String, Object> third = turn("t3", "10:02:00.000", c);

        JsonNode row = hop("t3", "10:02:00.000");

        assertThat(row.properties().stream().map(Map.Entry::getKey).toList()).containsExactly("trace_id", FULL, STATUS);
        assertThat(Rebuild.canonical(row.get(FULL).asString())).isEqualTo(Rebuild.canonical(body(c)));
        assertThat(row.get(STATUS).asString()).isEqualTo("rebuilt");
        assertThat(read("t3", BODY).get(BODY).asString()).isEqualTo(third.get("request_body"));
    }

    @Test
    void bodyStoredWholeIsReturnedByteForByteAndIssuesNoRebuildStatement() throws Exception {
        turn(newEncoder(RequestBodyMode.WHOLE), "t1", "10:00:00.000", messages("user:u1", "assistant:a1", "user:u2"), "");
        String stored = queryScalar("SELECT request_body FROM " + PAYLOAD + " FINAL WHERE trace_id = 't1'", "request_body");
        long before = rebuildStatements();

        JsonNode row = hop("t1", "10:00:00.000");

        assertThat(row.get(FULL).asString()).isEqualTo(stored);
        assertThat(row.get(STATUS).asString()).isEqualTo("stored");
        assertThat(rebuildStatements()).isEqualTo(before);
    }

    @Test
    void chainDeeperThanTheAncestorHintIsFollowed() throws Exception {
        RequestDeltaEncoder encoder = newEncoder(RequestBodyMode.DELTA);
        List<String> history = new ArrayList<>();
        String last = null;
        for (int turn = 0; turn < 140; turn++) {
            history.add("user:q" + turn);
            last = messages(history.toArray(String[]::new));
            turn(encoder, "d" + turn, String.format("09:%02d:%02d.000", turn / 60, turn % 60), last, "");
            history.add("assistant:r" + turn);
        }

        JsonNode row = hop("d139", "09:02:19.000");

        assertThat(row.get(STATUS).asString()).isEqualTo("rebuilt");
        assertThat(Rebuild.canonical(row.get(FULL).asString())).isEqualTo(Rebuild.canonical(body(last)));
    }

    @Test
    void roundBoundMakesTheRowPartialRatherThanFailTheRead() {
        RequestDeltaEncoder encoder = newEncoder(RequestBodyMode.DELTA);
        List<String> history = new ArrayList<>();
        for (int turn = 0; turn < 135; turn++) {
            history.add("user:q" + turn);
            turn(encoder, "d" + turn, String.format("09:%02d:%02d.000", turn / 60, turn % 60), messages(history.toArray(String[]::new)), "");
            history.add("assistant:r" + turn);
        }
        turn("short-2", "11:00:01.000", messages("user:s"));
        turn("short-1", "11:00:02.000", messages("user:s", "assistant:s", "user:t"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        StructuredQueryExecutor bounded = new StructuredQueryExecutor(builder, renderContext, clickHouse, rowPolicies,
                new ComputedFieldCompleter(reader, new RequestBodyRebuildProperties(1, 20000), meters));
        QueryEntityModel model = catalog.findModel("dial_usage_log", Privilege.TABLE_READ).orElseThrow();

        QueryResultPage page = bounded.execute(model, structured(traceIn("d134", "short-1")), 100, new QuerySettings());

        Map<String, Map<String, Object>> byTrace = new LinkedHashMap<>();
        page.rows().forEach(row -> byTrace.put((String) row.get("trace_id"), row));
        assertThat(byTrace.get("d134").get(STATUS)).isEqualTo("partial");
        assertThat(byTrace.get("d134").get(FULL)).isEqualTo(storedBody("d134"));
        assertThat(byTrace.get("short-1").get(STATUS)).isEqualTo("rebuilt");
        assertThat(meters.get("query.request.rebuild.partial").tag("reason", "limit").counter().count()).isEqualTo(1.0);
    }

    /**
     * Unshaped, the payload join is a keyed derived table rather than the late-materialized bare join, so the stand-ins
     * render against the columns that table projects; the values are the same.
     */
    @Test
    void theKeyedDerivedTableCarriesWhatTheStandInsRead() {
        String c = messages("user:u1", "assistant:a1", "user:u2", "assistant:a2", "user:u3");
        turn("t1", "10:00:00.000", messages("user:u1"));
        turn("t2", "10:01:00.000", messages("user:u1", "assistant:a1", "user:u2"));
        turn("t3", "10:02:00.000", c);
        QueryEntityModel model = catalog.findModel("dial_usage_log", Privilege.TABLE_READ).orElseThrow();
        StructuredQueryExecutor unshaped = new StructuredQueryExecutor(builder.withoutReadShaping(), renderContext, clickHouse, rowPolicies,
                new ComputedFieldCompleter(reader, new RequestBodyRebuildProperties(8, 20000), new SimpleMeterRegistry()));

        Map<String, Object> row = unshaped.execute(model, structured(traceIn("t3")), 100, new QuerySettings()).rows().getFirst();
        Map<String, Object> status = unshaped.execute(model, json.readValue(query(traceIn("t3"), STATUS), StructuredQuery.class), 100,
                new QuerySettings()).rows().getFirst();

        assertThat(row.get(STATUS)).isEqualTo("rebuilt");
        assertThat(Rebuild.canonical((String) row.get(FULL))).isEqualTo(Rebuild.canonical(body(c)));
        assertThat(status.get(STATUS)).isEqualTo("rebuilt");
    }

    @Test
    void deletedParentMakesTheRowPartialMissing() throws Exception {
        turn("t1", "10:00:00.000", messages("user:u1"));
        turn("t2", "10:01:00.000", messages("user:u1", "assistant:a1", "user:u2"));
        execute("ALTER TABLE " + PAYLOAD + " DELETE WHERE trace_id = 't1' SETTINGS mutations_sync = 2");
        double missing = counter("query.request.rebuild.partial", "reason", "missing");

        JsonNode row = hop("t2", "10:01:00.000");

        assertThat(row.get(STATUS).asString()).isEqualTo("partial");
        assertThat(row.get(FULL).asString()).isEqualTo(storedBody("t2"));
        assertThat(counter("query.request.rebuild.partial", "reason", "missing")).isEqualTo(missing + 1);
    }

    @Test
    void parentReingestedOntoNewRootStillRebuildsExactly() throws Exception {
        String a = messages("user:u1");
        String b = messages("user:u1", "assistant:a1", "user:u2");
        String c = messages("user:u1", "assistant:a1", "user:u2", "assistant:a2", "user:u3");
        turn("t2", "10:01:00.000", b);   // stored as a root
        turn("t3", "10:02:00.000", c);   // its child
        turn("t1", "10:00:00.000", a);
        turn("t2", "10:01:00.000", b);   // re-ingested: now t1's child, with a shorter delta

        JsonNode row = hop("t3", "10:02:00.000");

        assertThat(row.get(STATUS).asString()).isEqualTo("rebuilt");
        assertThat(Rebuild.canonical(row.get(FULL).asString())).isEqualTo(Rebuild.canonical(body(c)));
    }

    @Test
    void rowsOfTwoDaysRebuildEachInItsOwnDay() throws Exception {
        String yesterday = LocalDate.parse(FIXTURE_DATE).minusDays(1).toString();
        String b = messages("user:u1", "assistant:a1", "user:u2");
        turnAt("y1", yesterday + " 10:00:00.000", messages("user:u1"));
        turnAt("y2", yesterday + " 10:01:00.000", b);
        turn("t1", "10:00:00.000", messages("user:u1"));
        turn("t2", "10:01:00.000", b);

        JsonNode rows = rows(executeJson(query(traceIn("y2", "t2"), "trace_id", FULL, STATUS)));

        assertThat(rows).hasSize(2);
        for (JsonNode row : rows) {
            assertThat(row.get(STATUS).asString()).as(row.get("trace_id").asString()).isEqualTo("rebuilt");
            assertThat(Rebuild.canonical(row.get(FULL).asString())).isEqualTo(Rebuild.canonical(body(b)));
        }
    }

    @Test
    void rowWithoutPayloadRowReadsNull() throws Exception {
        usageRow("t1", fixtureTime("10:00:00.000"));

        JsonNode row = read("t1", FULL, STATUS);

        assertThat(row.get(FULL).isNull()).isTrue();
        assertThat(row.get(STATUS).isNull()).isTrue();
    }

    @Test
    void toolsAndMarkersComeBackEndToEnd() throws Exception {
        String tools = ",\"tools\":[{\"name\":\"search\"}]";
        String first = messages("user:u1");
        String second = messages("user:u1", "assistant:a1").replaceFirst("]$", ",")
                + "{\"role\":\"user\",\"content\":\"u2\",\"cache_control\":{\"type\":\"ephemeral\"}}]";
        turn(newEncoder(RequestBodyMode.DELTA), "t1", "10:00:00.000", first, tools);
        turn(newEncoder(RequestBodyMode.DELTA), "t2", "10:01:00.000", second, tools);

        JsonNode row = hop("t2", "10:01:00.000");

        assertThat(storedBody("t2")).doesNotContain("tools").doesNotContain("cache_control");
        assertThat(row.get(STATUS).asString()).isEqualTo("rebuilt");
        assertThat(Rebuild.canonical(row.get(FULL).asString())).isEqualTo(Rebuild.canonical(body(second, tools)));
    }

    @Test
    void theSqlAndCsvSurfacesCarryTheSameValues() throws Exception {
        turn("t1", "10:00:00.000", messages("user:u1"));
        turn("t2", "10:01:00.000", messages("user:u1", "assistant:a1", "user:u2"));
        JsonNode dsl = read("t2", FULL, STATUS);
        String full = dsl.get(FULL).asString();

        JsonNode sql = rows(postJson("/v1/queries/execute-sql", sql("SELECT trace_id, \"" + FULL + "\" AS body, \"" + STATUS
                + "\" AS status FROM dial_usage_log WHERE trace_id = 't2' LIMIT 1"))).get(0);
        assertThat(sql.get("body").asString()).isEqualTo(full);
        assertThat(sql.get("status").asString()).isEqualTo("rebuilt");

        String csvField = "\"" + full.replace("\"", "\"\"") + "\"";
        String csv = postText("/v1/queries/execute-csv", query(traceIn("t2"), "trace_id", FULL));
        assertThat(csv).startsWith("trace_id," + FULL).contains(csvField);
        String sqlCsv = postText("/v1/queries/execute-sql-csv", sql("SELECT trace_id, \"" + STATUS + "\", \"" + FULL
                + "\" FROM dial_usage_log WHERE trace_id = 't2'"));
        assertThat(sqlCsv).contains("rebuilt," + csvField);
    }

    @Test
    void everyMisuseIsRefusedBeforeClickHouseIsContacted() throws Exception {
        String filtered = """
                {"entity":"dial_usage_log","mode":"row",
                 "filter":{"op":"eq","args":[{"type":"field","name":"%s"},{"type":"value","value_type":"string","value":"partial"}]},
                 "select":[{"expr":{"type":"field","name":"trace_id"}}]}""".formatted(STATUS);
        refused("/v1/queries/execute", filtered, STATUS);
        String sortedByAlias = """
                {"entity":"dial_usage_log","mode":"row",
                 "select":[{"expr":{"type":"field","name":"%s"},"as":"body"}],
                 "sort":[{"field":"body","dir":"asc"}]}""".formatted(FULL);
        refused("/v1/queries/execute", sortedByAlias, FULL);
        refused("/v1/queries/execute-sql", sql("SELECT a.trace_id, b.\"" + FULL + "\" FROM dial_usage_log a JOIN dial_usage_log b"
                + " ON a.trace_id = b.trace_id"), FULL);
        refused("/v1/queries/execute-sql", sql("SELECT trace_id FROM dial_usage_log WHERE \"" + STATUS + "\" = 'partial'"), STATUS);
        refused("/v1/queries/execute-sql-csv", sql("SELECT trace_id FROM dial_usage_log ORDER BY \"" + FULL + "\""), FULL);
        refused("/v1/queries/execute-sql", sql("SELECT x FROM (SELECT \"" + FULL + "\" AS x, trace_id FROM dial_usage_log"
                + " ORDER BY trace_id LIMIT 5) AS d WHERE length(x) > 1"), FULL);
        refused("/v1/queries/execute-sql", sql("SELECT trace_id, \"" + FULL + "\" AS b FROM dial_usage_log ORDER BY b"), FULL);
        refused("/v1/queries/execute-sql", sql("SELECT trace_id, \"" + FULL + "\" FROM dial_usage_log ORDER BY 2"), FULL);
        refused("/v1/queries/execute-sql", sql("SELECT \"" + STATUS + "\", count(*) FROM dial_usage_log GROUP BY \"" + STATUS + "\""), STATUS);
        refused("/v1/queries/execute-sql", sql("SELECT DISTINCT \"" + STATUS + "\" FROM dial_usage_log"), STATUS);
        refused("/v1/queries/execute-sql", sql("SELECT length(\"" + FULL + "\") FROM dial_usage_log"), FULL);
        refused("/v1/queries/execute-sql", sql("WITH c AS (SELECT trace_id, \"" + FULL + "\" AS b FROM dial_usage_log)"
                + " SELECT c.b FROM c JOIN dial_usage_log u ON c.trace_id = u.trace_id"), FULL);
        refused("/v1/queries/execute-sql", sql("SELECT \"" + STATUS + "\" FROM dial_usage_log UNION ALL"
                + " SELECT \"" + STATUS + "\" FROM dial_usage_log"), STATUS);
        refused("/v1/queries/execute-sql", sql("SELECT trace_id, \"" + FULL + "\" FROM dial_usage_log WHERE trace_id IN"
                + " (SELECT trace_id FROM dial_usage_log WHERE project_id = 'p')"), FULL);
        refused("/v1/queries/translate", sortedByAlias, FULL);
        refused("/v1/queries/translate-sql", sql("SELECT trace_id FROM dial_usage_log WHERE \"" + STATUS + "\" = 'partial'"), STATUS);
    }

    @Test
    void wildcardLeavesTheFieldsOutAndTranslationRoundTripsProjection() throws Exception {
        turn("t1", "10:00:00.000", messages("user:u1"));
        JsonNode star = rows(postJson("/v1/queries/execute-sql", sql("SELECT * FROM dial_usage_log WHERE trace_id = 't1'"))).get(0);
        assertThat(star.has(FULL)).isFalse();
        assertThat(star.has(STATUS)).isFalse();

        String rendered = json.readTree(postJson("/v1/queries/translate", query(traceIn("t1"), FULL))).get("sql").asString();
        assertThat(rendered).contains("\"" + FULL + "\"");
        JsonNode back = json.readTree(postJson("/v1/queries/translate-sql", sql(rendered))).get("query");
        assertThat(back.toString()).contains("\"name\":\"" + FULL + "\"");
    }

    /** Phase 1 prunes by the partition key and the hash index; phase 2 reads through the primary key. */
    @Test
    void theRebuildStatementsPruneByPartitionHashIndexAndPrimaryKey() {
        turn("t1", "10:00:00.000", messages("user:u1"));
        int day = Integer.parseInt(FIXTURE_DATE.replace("-", ""));

        String links = queryText("EXPLAIN indexes = 1 " + inline(RequestChainReader.linksStatement(),
                RequestChainReader.parameters(day, "paths", List.of(UUID.randomUUID().toString()))));
        String bodies = queryText("EXPLAIN indexes = 1 " + inline(RequestChainReader.bodiesStatement(),
                RequestChainReader.parameters(day, "events", List.of(eventIdFor("t1")))));

        assertThat(links).contains("Partition", "idx_request_hash");
        assertThat(bodies).contains("Partition", "PrimaryKey");
    }

    /**
     * A status-only read names the body only through its null map. Measured against the read of the body itself, so the
     * probe is known to see the body's bytes when a statement reads them: under {@code FINAL}, {@code isNull(body)} read
     * the whole column on the supported server, which is why the stand-in reads the {@code .null} subcolumn.
     */
    @Test
    void statusOnlyReadReadsTheNullMapNotTheBody() throws Exception {
        int size = 4 * 1000 * 1000;
        execute("INSERT INTO " + PAYLOAD + " (event_id, request_time, trace_id, core_span_id, request_body) SELECT toUUID('"
                + eventIdFor("big") + "'), '" + fixtureTime("10:00:00.000") + "', 'big', 's-big', repeat('wxyz', " + size / 4 + ")");
        usageRow("big", fixtureTime("10:00:00.000"));
        execute("SELECT length(request_body) AS n FROM " + PAYLOAD + " FINAL WHERE trace_id = 'big' SETTINGS log_comment = 'rebuild-body-probe'");

        JsonNode row = read("big", STATUS);
        execute("SYSTEM FLUSH LOGS");

        assertThat(row.get(STATUS).asString()).isEqualTo("stored");
        long body = Long.parseLong(queryScalar(readBytes("log_comment = 'rebuild-body-probe'"), "b"));
        long status = Long.parseLong(queryScalar(readBytes("query LIKE '%request_body.null%' AND query LIKE '%''big''%'"
                + " AND query NOT LIKE '%query_log%' AND event_time >= now() - INTERVAL 5 MINUTE"), "b"));
        assertThat(body).isGreaterThan(size);
        assertThat(status).isPositive().isLessThan(size / 16);
    }

    private static String readBytes(String condition) {
        return "SELECT toString(max(read_bytes)) AS b FROM system.query_log WHERE type = 'QueryFinish' AND " + condition;
    }

    private static String inline(String statement, Map<String, Object> params) {
        String sql = statement;
        for (Map.Entry<String, Object> param : params.entrySet()) {
            String value = param.getValue().toString();
            sql = sql.replace("{" + param.getKey() + ":DateTime64(3, 'UTC')}", "toDateTime64('" + value + "', 3, 'UTC')")
                    .replace("{" + param.getKey() + ":String}", "'" + value + "'");
        }
        return sql;
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Statements the rebuild reader has issued so far, by its two statements' text. */
    private long rebuildStatements() {
        execute("SYSTEM FLUSH LOGS");
        return Long.parseLong(queryScalar("SELECT toString(count()) AS n FROM system.query_log WHERE type = 'QueryFinish'"
                + " AND (query LIKE '%LIMIT 1 BY request_hash%' OR query LIKE '%request_body AS body%')"
                + " AND query NOT LIKE '%system.query_log%'", "n"));
    }

    private double counter(String name, String tag, String value) {
        var counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    /** The hop inspector's read: one row by trace, span and time, selecting both fields. */
    private JsonNode hop(String traceId, String timeOfDay) throws Exception {
        String time = fixtureTime(timeOfDay);
        String body = """
                {"entity":"dial_usage_log","mode":"row",
                 "filter":{"op":"and","args":[
                   {"op":"eq","args":[{"type":"field","name":"trace_id"},{"type":"value","value_type":"string","value":"%s"}]},
                   {"op":"eq","args":[{"type":"field","name":"core_span_id"},{"type":"value","value_type":"string","value":"s-%s"}]},
                   {"op":"ge","args":[{"type":"field","name":"request_time"},{"type":"value","value_type":"timestamp","value":"%s"}]},
                   {"op":"le","args":[{"type":"field","name":"request_time"},{"type":"value","value_type":"timestamp","value":"%s"}]}]},
                 "select":[{"expr":{"type":"field","name":"trace_id"}},{"expr":{"type":"field","name":"%s"}},
                           {"expr":{"type":"field","name":"%s"}}],
                 "page":{"type":"offset","offset":0,"limit":1}}""".formatted(traceId, traceId, iso(time), iso(time), FULL, STATUS);
        JsonNode rows = rows(postJson("/v1/queries/execute", body));
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** A fixture time as the epoch milliseconds a {@code timestamp} literal carries. */
    private static String iso(String time) {
        return Long.toString(java.time.LocalDateTime.parse(time.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli());
    }

    private JsonNode read(String traceId, String... fields) throws Exception {
        JsonNode rows = rows(executeJson(query(traceIn(traceId), fields)));
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private String executeJson(String query) throws Exception {
        return postJson("/v1/queries/execute", query);
    }

    private JsonNode rows(String response) {
        return json.readTree(response).get("rows");
    }

    private String postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String postText(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void refused(String path, String body, String field) throws Exception {
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message", containsString(field)))
                .andExpect(jsonPath("$.message", containsString("can only be selected")));
    }

    private String sql(String statement) {
        return json.writeValueAsString(Map.of("sql", statement));
    }

    private static String traceIn(String... traceIds) {
        List<String> values = new ArrayList<>();
        for (String traceId : traceIds) {
            values.add("{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"" + traceId + "\"}");
        }
        return "{\"op\":\"in\",\"args\":[{\"type\":\"field\",\"name\":\"trace_id\"},{\"type\":\"array\",\"items\":["
                + String.join(",", values) + "]}]}";
    }

    private String query(String filter, String... fields) {
        List<String> select = new ArrayList<>();
        select.add("{\"expr\":{\"type\":\"field\",\"name\":\"trace_id\"}}");
        for (String field : fields) {
            if (!"trace_id".equals(field)) {
                select.add("{\"expr\":{\"type\":\"field\",\"name\":\"" + field + "\"}}");
            }
        }
        return "{\"entity\":\"dial_usage_log\",\"mode\":\"row\",\"filter\":" + filter + ",\"select\":[" + String.join(",", select)
                + "],\"page\":{\"type\":\"offset\",\"offset\":0,\"limit\":100}}";
    }

    private StructuredQuery structured(String filter) {
        return json.readValue(query(filter, "trace_id", FULL, STATUS), StructuredQuery.class);
    }

    private String storedBody(String traceId) {
        return queryScalar("SELECT request_body FROM " + PAYLOAD + " FINAL WHERE trace_id = '" + traceId + "'", "request_body");
    }

    private Map<String, Object> turn(String traceId, String timeOfDay, String messages) {
        return turn(newEncoder(RequestBodyMode.DELTA), traceId, timeOfDay, messages, "");
    }

    /** One turn: its payload row through the encoder and writer, and the usage-log row it belongs to. */
    private Map<String, Object> turn(RequestDeltaEncoder encoder, String traceId, String timeOfDay, String messages, String members) {
        Map<String, Object> row = payloadRow(traceId, fixtureTime(timeOfDay), body(messages, members));
        write(encoder, row);
        usageRow(traceId, fixtureTime(timeOfDay));
        return row;
    }

    private Map<String, Object> turnAt(String traceId, String requestTime, String messages) {
        Map<String, Object> row = payloadRow(traceId, requestTime, body(messages));
        write(newEncoder(RequestBodyMode.DELTA), row);
        usageRow(traceId, requestTime);
        return row;
    }

    private void usageRow(String traceId, String requestTime) {
        execute("INSERT INTO dial_usage_log (event_id, request_time, event_kind, project_id, user_hash, trace_id, core_span_id)"
                + " VALUES (toUUID('" + eventIdFor(traceId) + "'), '" + requestTime + "', 'chat', 'project-1', '" + USER + "', '"
                + traceId + "', 's-" + traceId + "')");
    }

    private RequestDeltaEncoder newEncoder(RequestBodyMode mode) {
        return new RequestDeltaEncoder(mode, new ClickHouseParentLookup(writer, PAYLOAD), 1000, new SimpleMeterRegistry(), PAYLOAD);
    }

    private void write(RequestDeltaEncoder encoder, Map<String, Object> row) {
        List<Map<String, Object>> batch = new ArrayList<>(List.of(row));
        RequestDeltaEncoder.Encoding encoding = encoder.encode(batch);
        writer.insert(PAYLOAD, batch);
        encoding.written();
    }

    private static Map<String, Object> payloadRow(String traceId, String requestTime, String body) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", eventIdFor(traceId));
        row.put("request_time", requestTime);
        row.put("trace_id", traceId);
        row.put("core_span_id", "s-" + traceId);
        row.put("request_body", body);
        row.put("project_id", "project-1");
        row.put("deployment", "deployment-1");
        row.put("user_hash", USER);
        return row;
    }

    private static String body(String messages) {
        return body(messages, "");
    }

    private static String body(String messages, String members) {
        return "{\"model\":\"m\"" + members + ",\"messages\":" + messages + "}";
    }

    private static String messages(String... turns) {
        List<String> parts = new ArrayList<>();
        for (String turn : turns) {
            String[] pair = turn.split(":", 2);
            parts.add("{\"role\":\"" + pair[0] + "\",\"content\":\"" + pair[1] + "\"}");
        }
        return "[" + String.join(",", parts) + "]";
    }
}
