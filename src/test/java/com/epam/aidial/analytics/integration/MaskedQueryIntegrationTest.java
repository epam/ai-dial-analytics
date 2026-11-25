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
 * which reads the rows of {@code P2} with {@code k} and {@code project} only. Each value is checked against a reference
 * the author computes from the stored rows, with every absent cell replaced by {@code NULL}.
 */
class MaskedQueryIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/tables";
    private static final String TABLE = "mq_probe";
    private static final String MEMBER = "$masked";

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
            sql(caller(), "SELECT n FROM " + TABLE + " " + kind + " SELECT n FROM " + TABLE)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("'n'")));
        }
        sql(caller(), "SELECT k FROM " + TABLE + " INTERSECT SELECT k FROM " + TABLE).andExpect(status().isOk());

        // Each union branch marks its own rows, and the marks travel through the derived table that sorts them (a trailing
        // ORDER BY would bind to the last branch, as in ClickHouse).
        final List<Map<String, Object>> union = sqlRows(caller(), "SELECT k, n FROM (SELECT k, n FROM " + TABLE + " WHERE k = 'k1'"
                + " UNION ALL SELECT k, n FROM " + TABLE + " WHERE k = 'k3') u ORDER BY k");
        assertThat(union.get(0)).containsEntry("n", 10).doesNotContainKey(MEMBER);
        assertThat(union.get(1)).containsEntry("n", null).containsEntry(MEMBER, List.of("n"));
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
        for (final ResultActions refused : List.of(send(post("/v1/queries/execute"), admin(), dslQuery),
                send(post("/v1/queries/translate"), admin(), dslQuery),
                sql(admin(), "SELECT k AS \"$masked\" FROM " + TABLE),
                sql(admin(), "SELECT TABLE_NAME AS \"$masked\" FROM INFORMATION_SCHEMA.TABLES"),
                send(post("/v1/queries/translate-sql"), admin(), "{\"sql\":\"SELECT k AS \\\"$masked\\\" FROM " + TABLE + "\"}"))) {
            refused.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("$masked")));
        }
        send(post("/v1/queries/validate-sql"), admin(), "{\"sql\":\"SELECT k AS \\\"$masked\\\" FROM " + TABLE + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));
        try (BufferAllocator allocator = new RootAllocator(); FlightSqlClient client = flight(allocator)) {
            assertThatThrownBy(() -> client.execute("SELECT k AS \"$masked\" FROM " + TABLE, bearerOption(admin())))
                    .isInstanceOfSatisfying(FlightRuntimeException.class,
                            ex -> assertThat(ex.status().code()).isEqualTo(FlightStatusCode.INVALID_ARGUMENT));
        }

        // Matched case-sensitively, and only at the top level.
        sql(admin(), "SELECT k AS \"$Masked\" FROM " + TABLE + " ORDER BY k").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0]['$Masked']").value("k1"));
        sql(admin(), "SELECT x FROM (SELECT k AS \"$masked\", k AS x FROM " + TABLE + ") s ORDER BY x").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].x").value("k1"));
    }

    /**
     * The refusals a masked field brings are met where every surface resolves the statement: on execution and when a
     * {@code sql} saved query, a {@code query} saved query or an inline widget is saved. The saving caller holds
     * {@code writer}, which reads every table, confined here to the rows of {@code P1}, beside {@code mq-u}.
     */
    @Test
    void maskedOutputRefusalsApplyOnExecutionAndOnSave() throws Exception {
        entry("writer", "[]", "null", "P1");
        final String writer = JwtTestTokens.token(PERSONAS_ISSUER, AUDIENCE, Map.of("sub", "mq-writer", "roles", List.of("writer", "mq-u"),
                "email", "mq-writer@test"));
        final List<String> statements = List.of("SELECT n AS " + TABLE + " FROM " + TABLE, "SELECT n AS \\\"" + TABLE + ".x\\\" FROM " + TABLE,
                "SELECT n FROM " + TABLE + " INTERSECT SELECT n FROM " + TABLE, "SELECT k AS \\\"$masked\\\" FROM " + TABLE);
        for (final String statement : statements) {
            send(post("/v1/queries/execute-sql"), writer, "{\"sql\":\"" + statement + "\"}").andExpect(status().isBadRequest());
            send(post("/v1/saved-queries"), writer, "{\"name\":\"Masked\",\"scope\":\"personal\",\"sql\":\"" + statement + "\"}")
                    .andExpect(status().isBadRequest());
            send(post("/v1/dashboards"), writer, "{\"name\":\"Masked\",\"visibility\":\"private\",\"widgets\":[{\"id\":\"w1\","
                    + "\"row_limit\":10,\"layout\":{\"x\":0,\"y\":0,\"w\":6,\"h\":4},\"inline_query\":{\"name\":\"Raw\",\"sql\":\"" + statement
                    + "\"}}]}").andExpect(status().isBadRequest());
        }
        for (final String alias : List.of(TABLE, "$masked")) {
            send(post("/v1/saved-queries"), writer, "{\"name\":\"Masked\",\"scope\":\"personal\",\"query\":{\"entity\":\"" + TABLE + "\","
                    + "\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"n\"},\"as\":\"" + alias + "\"}]}}")
                    .andExpect(status().isBadRequest());
        }
        // The same names beside no masked field are a caller's to choose.
        sql(writer, "SELECT k AS " + TABLE + " FROM " + TABLE + " ORDER BY k").andExpect(status().isOk());
        sql(writer, "SELECT n FROM " + TABLE + " ORDER BY k").andExpect(status().isOk()).andExpect(jsonPath("$.rows[2]['$masked'][0]").value("n"));
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** PUTs {@code role}'s whole entry on the probe, its rows those of {@code project}. */
    private void entry(String role, String privileges, String columns, String project) throws Exception {
        final long generation = ((Number) JsonPath.read(mockMvc.perform(get(BASE + "/" + TABLE + "/access")
                .header(HttpHeaders.AUTHORIZATION, bearer(admin()))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), "$.generation")).longValue();
        send(put(BASE + "/" + TABLE + "/access?role=" + role), admin(), "{\"generation\":" + generation + ",\"privileges\":" + privileges
                + ",\"columns\":" + columns + ",\"rows\":{\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"project\"},"
                + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"" + project + "\"}]}}}").andExpect(status().isOk());
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
