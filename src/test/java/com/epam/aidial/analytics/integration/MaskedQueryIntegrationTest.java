package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.flight.FlightSqlServerLifecycle;
import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.jayway.jsonpath.JsonPath;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Queries over a masked field, end to end in {@code oidc} mode (see {@code openspec/specs/structured-query/spec.md}, "A
 * masked field reads as absent at every reference", "Masked inputs are reported per output cell" and "Set operations
 * over a masked output", and the response envelope, CSV and Flight SQL specs). The probe's {@code n} is masked to the rows
 * of {@code P1} for a caller carrying {@code mq-r}, which reads the rows of {@code P1} with every column, and {@code mq-u},
 * which reads the rows of {@code P2} with {@code k} and {@code project} only. Projections and aggregates are checked
 * against a reference statement the author runs over the stored rows with every absent cell replaced by {@code NULL};
 * the other surfaces against the values the fixture states.
 */
class MaskedQueryIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/tables";
    private static final String TABLE = "mq_probe";
    private static final String MEMBER = "$masked";
    /** The reserved name's refusal, word for word on every surface. */
    private static final String RESERVED = "output name '$masked' is reserved";

    @Autowired
    private FlightSqlServerLifecycle lifecycle;

    @BeforeEach
    void createProbe() throws Exception {
        send(post(BASE), admin(), "{\"name\":\"" + TABLE + "\",\"type\":\"source\"}").andExpect(status().isCreated());
        send(post(BASE + "/" + TABLE + "/schema"), admin(), "{\"columns\":["
                + "{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},"
                + "{\"source_name\":\"project\",\"name\":\"project\",\"type\":\"string\"},"
                + "{\"source_name\":\"n\",\"name\":\"n\",\"type\":\"long\"}],\"ordering_key\":[\"k\"]}")
                .andExpect(status().isOk());
        send(post(BASE + "/" + TABLE + "/rows"), admin(), "{\"rows\":["
                + "{\"k\":\"k1\",\"project\":\"P1\",\"n\":10},{\"k\":\"k2\",\"project\":\"P1\",\"n\":20},"
                + "{\"k\":\"k3\",\"project\":\"P2\",\"n\":30},{\"k\":\"k4\",\"project\":\"P2\",\"n\":40}]}")
                .andExpect(status().isOk());
        entry("mq-r", "[\"read\"]", "null", "P1");
        entry("mq-u", "[\"read\"]", "[\"k\",\"project\"]", "P2");
    }

    @AfterEach
    void dropProbe() throws Exception {
        mockMvc.perform(delete(BASE + "/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(admin())));
    }

    @Test
    void maskedFieldReadsAsAbsentAtEveryReference() throws Exception {
        final List<Map<String, Object>> rows = sqlRows(caller(), "SELECT k, n, n + 1 AS m FROM " + TABLE + " ORDER BY k");
        assertThat(values(rows)).isEqualTo(values(sqlRows(admin(), "SELECT k, CASE WHEN project = 'P1' THEN n END AS n,"
                + " CASE WHEN project = 'P1' THEN n + 1 END AS m FROM " + TABLE + " ORDER BY k")));
        assertThat(rows.get(0)).containsEntry("n", 10).doesNotContainKey(MEMBER);
        assertThat(rows.get(2)).containsEntry("n", null).containsEntry("m", null).containsEntry(MEMBER, List.of("n", "m"));

        // A filter and a sort read the absent value too: no P2 row satisfies n > 15, and the absent rows sort as nulls.
        assertThat(sqlRows(caller(), "SELECT k FROM " + TABLE + " WHERE n > 15 ORDER BY k")).extracting(row -> row.get("k"))
                .containsExactly("k2");
        assertThat(sqlRows(caller(), "SELECT k FROM " + TABLE + " ORDER BY n NULLS FIRST, k")).extracting(row -> row.get("k"))
                .containsExactly("k3", "k4", "k1", "k2");

        final List<Map<String, Object>> dsl = JsonPath.read(send(post("/v1/queries/execute"), caller(), "{\"entity\":\"" + TABLE + "\","
                + "\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"k\"}},{\"expr\":{\"type\":\"field\",\"name\":\"n\"}}],"
                + "\"sort\":[{\"field\":\"k\",\"dir\":\"asc\"}],\"page\":{\"type\":\"offset\",\"limit\":10}}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.rows");
        assertThat(values(dsl)).isEqualTo(values(sqlRows(caller(), "SELECT k, n FROM " + TABLE + " ORDER BY k")));
        assertThat(dsl.get(3)).containsEntry(MEMBER, List.of("n"));
    }

    @Test
    void partialAggregateIsMarkedAndCountIsNot() throws Exception {
        final List<Map<String, Object>> groups = sqlRows(caller(), "SELECT project, sum(n) AS total, count(*) AS c FROM " + TABLE
                + " GROUP BY project ORDER BY project");
        assertThat(values(groups)).isEqualTo(values(sqlRows(admin(), "SELECT project, sum(CASE WHEN project = 'P1' THEN n END) AS total,"
                + " count(*) AS c FROM " + TABLE + " GROUP BY project ORDER BY project")));
        assertThat(groups.get(0)).containsEntry("total", 30).doesNotContainKey(MEMBER);
        assertThat(groups.get(1)).containsEntry("total", null).containsEntry("c", 2).containsEntry(MEMBER, List.of("total"));

        // A group mixing present and absent cells sums the present ones and is marked.
        assertThat(sqlRows(caller(), "SELECT sum(n) AS total FROM " + TABLE).getFirst())
                .containsEntry("total", 30).containsEntry(MEMBER, List.of("total"));
    }

    @Test
    void setOperationsOverMaskedOutput() throws Exception {
        for (final String kind : List.of("INTERSECT", "EXCEPT")) {
            refused(sql(caller(), "SELECT n FROM " + TABLE + " " + kind + " SELECT n FROM " + TABLE), "reads masked field 'n'");
        }
        sql(caller(), "SELECT k FROM " + TABLE + " INTERSECT SELECT k FROM " + TABLE).andExpect(status().isOk());

        // Each union branch marks its own rows, and the marks travel through the derived table that sorts them (a trailing
        // ORDER BY would bind to the last branch, as in ClickHouse).
        final List<Map<String, Object>> union = sqlRows(caller(), "SELECT k, n FROM (SELECT k, n FROM " + TABLE + " WHERE k = 'k1'"
                + " UNION ALL SELECT k, n FROM " + TABLE + " WHERE k = 'k3') u ORDER BY k");
        assertThat(union.get(0)).containsEntry("n", 10).doesNotContainKey(MEMBER);
        assertThat(union.get(1)).containsEntry("n", null).containsEntry(MEMBER, List.of("n"));
    }

    /** "A distinct projection with a window output over a masked field runs", through the DSL and through SQL alike. */
    @Test
    void distinctWithWindowOutputAgreesAcrossDslAndSql() throws Exception {
        final List<Map<String, Object>> viaSql = sqlRows(caller(), "SELECT DISTINCT project, sum(n) OVER (PARTITION BY project) AS total FROM " + TABLE
                + " ORDER BY project");
        final List<Map<String, Object>> viaDsl = JsonPath.read(send(post("/v1/queries/execute"), caller(), "{\"entity\":\"" + TABLE + "\","
                + "\"mode\":\"row\",\"distinct\":true,\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"project\"}},"
                + "{\"expr\":{\"type\":\"window\",\"fn\":{\"type\":\"fn\",\"name\":\"sum\",\"args\":[{\"type\":\"field\",\"name\":\"n\"}]},"
                + "\"partition_by\":[{\"type\":\"field\",\"name\":\"project\"}]},\"as\":\"total\"}],"
                + "\"sort\":[{\"field\":\"project\",\"dir\":\"asc\"}],\"page\":{\"type\":\"offset\",\"limit\":10}}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.rows");

        for (final List<Map<String, Object>> rows : List.of(viaSql, viaDsl)) {
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).containsEntry("project", "P1").containsEntry("total", 30).doesNotContainKey(MEMBER);
            assertThat(rows.get(1)).containsEntry("project", "P2").containsEntry("total", null).containsEntry(MEMBER, List.of("total"));
        }
    }

    /**
     * "A masked UNION DISTINCT keeps the last branch's order under the row limit": the grouped form returns the rows, in
     * order, that ClickHouse's own {@code UNION DISTINCT} returns over the same masked values, each marked when a row it
     * collapses was absent.
     */
    @Test
    void maskedUnionDistinctKeepsTheOrderOfClickHouseUnderTheRowLimit() throws Exception {
        final String table = "mq_union";
        send(post(BASE), admin(), "{\"name\":\"" + table + "\",\"type\":\"source\"}").andExpect(status().isCreated());
        try {
            send(post(BASE + "/" + table + "/schema"), admin(), "{\"columns\":[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},"
                    + "{\"source_name\":\"project\",\"name\":\"project\",\"type\":\"string\"},"
                    + "{\"source_name\":\"route\",\"name\":\"route\",\"type\":\"string\"}],\"ordering_key\":[\"k\"]}").andExpect(status().isOk());
            // P1 holds 30 routes, P3 150 more, P2 20 that are absent for the caller.
            final List<String> rows = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                final String project = i < 30 ? "P1" : i < 180 ? "P3" : "P2";
                rows.add("{\"k\":\"k" + i + "\",\"project\":\"" + project + "\",\"route\":\"r" + String.format("%03d", 199 - i) + "\"}");
            }
            send(post(BASE + "/" + table + "/rows"), admin(), "{\"rows\":[" + String.join(",", rows) + "]}").andExpect(status().isOk());
            entry(table, "mq-r", "[\"read\"]", "null", "{\"filter\":{\"op\":\"in\",\"args\":[{\"type\":\"field\",\"name\":\"project\"},"
                    + "{\"type\":\"array\",\"items\":[{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"P1\"},"
                    + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"P3\"}]}]}}");
            entry(table, "mq-u", "[\"read\"]", "[\"k\",\"project\"]", rowsOf("P2"));

            // The first branch reads P1 and the absent rows of P2: 31 distinct values, null among them.
            final List<Map<String, Object>> masked = sqlRows(caller(), "SELECT route FROM " + table + " WHERE project <> 'P3' UNION DISTINCT"
                    + " SELECT route FROM " + table + " ORDER BY route");
            final String absent = "CASE WHEN project IN ('P1', 'P3') THEN route END";
            final List<Map<String, Object>> reference = sqlRows(admin(), "SELECT " + absent + " AS route FROM " + table + " WHERE project <> 'P3'"
                    + " UNION DISTINCT SELECT " + absent + " AS route FROM " + table + " ORDER BY route");
            assertThat(masked).hasSize(100);
            // The first branch's values in the order it reads them, which neither statement fixes, then the rest in route order.
            assertThat(values(masked).subList(0, 31)).containsExactlyInAnyOrderElementsOf(values(reference).subList(0, 31));
            assertThat(values(masked).subList(31, 100)).isEqualTo(values(reference).subList(31, 100));
            assertThat(masked).filteredOn(row -> row.containsKey(MEMBER)).singleElement()
                    .satisfies(row -> assertThat(row).containsEntry("route", null).containsEntry(MEMBER, List.of("route")));
        } finally {
            mockMvc.perform(delete(BASE + "/" + table).header(HttpHeaders.AUTHORIZATION, bearer(admin())));
        }
    }

    @Test
    void csvAndFlightCarryAnAbsentCellAsNullWithNoMarks() throws Exception {
        final String csv = mockMvc.perform(post("/v1/queries/execute-sql-csv").header(HttpHeaders.AUTHORIZATION, bearer(caller()))
                        .contentType(MediaType.APPLICATION_JSON).accept("text/csv")
                        .content("{\"sql\":\"SELECT k, n FROM " + TABLE + " ORDER BY k\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv.split("\r\n")).containsExactly("k,n", "k1,10", "k2,20", "k3,", "k4,");

        try (BufferAllocator allocator = new RootAllocator(); FlightSqlClient client = flight(allocator)) {
            final CallOption credentials = bearerOption(caller());
            final FlightInfo info = client.execute("SELECT k, n FROM " + TABLE + " ORDER BY k", credentials);
            assertThat(info.getSchemaOptional().orElseThrow().getFields()).extracting(field -> field.getName()).containsExactly("k", "n");
            final List<Object> cells = new ArrayList<>();
            try (FlightStream stream = client.getStream(info.getEndpoints().getFirst().getTicket(), credentials)) {
                while (stream.next()) {
                    final FieldVector n = stream.getRoot().getVector("n");
                    for (int row = 0; row < stream.getRoot().getRowCount(); row++) {
                        cells.add(n.getObject(row));
                    }
                }
            }
            assertThat(cells).containsExactly(10L, 20L, null, null);
        }
    }

    /** The reserved row member: refused as a top-level output name on every surface, for every caller. */
    @Test
    void maskedMemberIsReservedAsTopLevelOutputName() throws Exception {
        final String dslQuery = "{\"entity\":\"" + TABLE + "\",\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"k\"},"
                + "\"as\":\"$masked\"}],\"page\":{\"type\":\"offset\",\"limit\":10}}";
        final String sqlBody = "{\"sql\":\"SELECT k AS \\\"$masked\\\" FROM " + TABLE + "\"}";
        for (final ResultActions refused : List.of(send(post("/v1/queries/execute"), admin(), dslQuery),
                send(post("/v1/queries/translate"), admin(), dslQuery),
                sql(admin(), "SELECT k AS \"$masked\" FROM " + TABLE),
                sql(admin(), "SELECT TABLE_NAME AS \"$masked\" FROM INFORMATION_SCHEMA.TABLES"),
                send(post("/v1/queries/translate-sql"), admin(), sqlBody),
                send(post("/v1/queries/execute-sql-csv"), admin(), sqlBody),
                send(post("/v1/queries/execute-csv"), admin(), dslQuery))) {
            refused(refused, RESERVED);
        }
        send(post("/v1/queries/validate-sql"), admin(), sqlBody)
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[0].message").value(containsString(RESERVED)));
        try (BufferAllocator allocator = new RootAllocator(); FlightSqlClient client = flight(allocator)) {
            assertThatThrownBy(() -> client.execute("SELECT k AS \"$masked\" FROM " + TABLE, bearerOption(admin())))
                    .hasMessageContaining(RESERVED)
                    .isInstanceOfSatisfying(FlightRuntimeException.class,
                            ex -> assertThat(ex.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT));
        }

        // Matched case-sensitively, and only at the top level: a derived table's column of that name runs and saves.
        sql(admin(), "SELECT k AS \"$Masked\" FROM " + TABLE + " ORDER BY k").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0]['$Masked']").value("k1"));
        final String derived = "SELECT x FROM (SELECT k AS \"$masked\", k AS x FROM " + TABLE + ") s ORDER BY x";
        sql(admin(), derived).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].x").value("k1"));
        final String saved = JsonPath.read(send(post("/v1/saved-queries"), admin(), savedSql(derived.replace("\"", "\\\"")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(delete("/v1/saved-queries/" + saved).header(HttpHeaders.AUTHORIZATION, bearer(admin())));
    }

    /**
     * The refusals a masked field brings are met where every surface resolves the statement: on execution and when a
     * {@code sql} saved query, a {@code query} saved query or an inline widget is saved. The saving caller holds
     * {@code writer}, which reads every table, confined here to the rows of {@code P1}, beside {@code mq-u}.
     */
    @Test
    void maskedOutputRefusalsApplyOnExecutionAndOnSave() throws Exception {
        // `writer` is a shared persona; this entry lives on this class's own probe, which each test drops, so no other class sees it.
        entry("writer", "[]", "null", "P1");
        final String writer = JwtTestTokens.token(PERSONAS_ISSUER, AUDIENCE, Map.of("sub", "mq-writer", "roles", List.of("writer", "mq-u"),
                "email", "mq-writer@test"));
        final String tableNamed = "names a table the query reads";
        // Statement (JSON-escaped) -> the refusal's own words, so an unrelated 400 does not pass.
        final Map<String, String> statements = new LinkedHashMap<>();
        statements.put("SELECT n AS " + TABLE + " FROM " + TABLE, tableNamed);
        statements.put("SELECT n AS \\\"" + TABLE + ".x\\\" FROM " + TABLE, tableNamed);
        statements.put("SELECT n FROM " + TABLE + " INTERSECT SELECT n FROM " + TABLE, "reads masked field 'n'");
        statements.put("SELECT k AS \\\"$masked\\\" FROM " + TABLE, RESERVED);
        try {
            for (final Map.Entry<String, String> entry : statements.entrySet()) {
                final String statement = entry.getKey();
                refused(send(post("/v1/queries/execute-sql"), writer, "{\"sql\":\"" + statement + "\"}"), entry.getValue());
                refused(send(post("/v1/saved-queries"), writer, savedSql(statement)), entry.getValue());
                refused(send(post("/v1/dashboards"), writer, "{\"name\":\"Masked\",\"visibility\":\"private\",\"widgets\":[{\"id\":\"w1\","
                        + "\"row_limit\":10,\"layout\":{\"x\":0,\"y\":0,\"w\":6,\"h\":4},\"inline_query\":{\"name\":\"Raw\",\"sql\":\""
                        + statement + "\"}}]}"), entry.getValue());
            }
            for (final Map.Entry<String, String> alias : Map.of(TABLE, tableNamed, "$masked", RESERVED).entrySet()) {
                refused(send(post("/v1/saved-queries"), writer, "{\"name\":\"Masked\",\"scope\":\"personal\",\"query\":{\"entity\":\"" + TABLE
                        + "\",\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"n\"},\"as\":\"" + alias.getKey() + "\"}]}}"),
                        alias.getValue());
            }
            mockMvc.perform(get("/v1/saved-queries").header(HttpHeaders.AUTHORIZATION, bearer(writer)))
                    .andExpect(jsonPath("$.saved_queries[?(@.name == 'Masked')]", hasSize(0)));
            mockMvc.perform(get("/v1/dashboards").header(HttpHeaders.AUTHORIZATION, bearer(writer)))
                    .andExpect(jsonPath("$.dashboards[?(@.name == 'Masked')]", hasSize(0)));

            // The same caller saves and runs the masked field itself, and may choose those names beside no masked field.
            final String saved = JsonPath.read(send(post("/v1/saved-queries"), writer, savedSql("SELECT n FROM " + TABLE)).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(), "$.id");
            mockMvc.perform(delete("/v1/saved-queries/" + saved).header(HttpHeaders.AUTHORIZATION, bearer(writer)));
            sql(writer, "SELECT k AS " + TABLE + " FROM " + TABLE + " ORDER BY k").andExpect(status().isOk());
            sql(writer, "SELECT k AS \"" + TABLE + ".x\" FROM " + TABLE + " ORDER BY k").andExpect(status().isOk());
            sql(writer, "SELECT n FROM " + TABLE + " ORDER BY k").andExpect(status().isOk()).andExpect(jsonPath("$.rows[2]['$masked'][0]").value("n"));
        } finally {
            deleteOwned(writer, "/v1/saved-queries", "saved_queries");
            deleteOwned(writer, "/v1/dashboards", "dashboards");
        }
    }

    private static void refused(ResultActions actions, String refusal) throws Exception {
        actions.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString(refusal)));
    }

    private static String savedSql(String statement) {
        return "{\"name\":\"Masked\",\"scope\":\"personal\",\"sql\":\"" + statement + "\"}";
    }

    /** Deletes whatever {@code token} owns under {@code path} named {@code Masked}: a refusal that let one through. */
    private void deleteOwned(String token, String path, String member) throws Exception {
        final List<String> ids = JsonPath.read(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andReturn().getResponse().getContentAsString(), "$." + member + "[?(@.name == 'Masked')].id");
        for (final String id : ids) {
            mockMvc.perform(delete(path + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)));
        }
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** PUTs {@code role}'s whole entry on the probe, its rows those of {@code project}. */
    private void entry(String role, String privileges, String columns, String project) throws Exception {
        entry(TABLE, role, privileges, columns, rowsOf(project));
    }

    /** PUTs {@code role}'s whole entry on {@code table}, {@code rows} its row policy. */
    private void entry(String table, String role, String privileges, String columns, String rows) throws Exception {
        final long generation = ((Number) JsonPath.read(mockMvc.perform(get(BASE + "/" + table + "/access")
                .header(HttpHeaders.AUTHORIZATION, bearer(admin()))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), "$.generation")).longValue();
        send(put(BASE + "/" + table + "/access?role=" + role), admin(), "{\"generation\":" + generation + ",\"privileges\":" + privileges
                + ",\"columns\":" + columns + ",\"rows\":" + rows + "}").andExpect(status().isOk());
    }

    /** The row policy admitting the rows of {@code project}. */
    private static String rowsOf(String project) {
        return "{\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"project\"},"
                + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"" + project + "\"}]}}";
    }

    /** Each row's cells without the {@code $masked} member, which the reference statement does not carry. */
    private static List<Map<String, Object>> values(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> {
            final Map<String, Object> cells = new LinkedHashMap<>(row);
            cells.remove(MEMBER);
            return cells;
        }).toList();
    }

    private List<Map<String, Object>> sqlRows(String token, String statement) throws Exception {
        return JsonPath.read(sql(token, statement).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.rows");
    }

    private ResultActions sql(String token, String statement) throws Exception {
        return send(post("/v1/queries/execute-sql"), token, "{\"sql\":\"" + statement.replace("\"", "\\\"") + "\"}");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private FlightSqlClient flight(BufferAllocator allocator) {
        return new FlightSqlClient(FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", lifecycle.boundPort())).build());
    }

    private static CallOption bearerOption(String token) {
        final FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", bearer(token));
        return new HeaderCallOption(headers);
    }

    private static String admin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE, Map.of("sub", "mq-admin", "roles", List.of("admin"), "email", "mq-admin@test"));
    }

    /** A caller carrying {@code mq-r} and {@code mq-u}, unmapped: their entries on the probe decide what it reads. */
    private static String caller() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE, Map.of("sub", "mq-user", "roles", List.of("mq-r", "mq-u"), "email", "mq@test"));
    }
}
