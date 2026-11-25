package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Hidden columns are absent from every read surface", end to end in {@code oidc} mode. The caller carries only the
 * unmapped role {@code cp-x}, whose entry on the probe grants {@code read}, {@code modify} and the clearance and lists
 * {@code k}, {@code a} and the sensitive {@code s}: the timestamp column {@code c} is hidden from it, while the
 * clearance still shows it {@code s}. Every surface must treat {@code c} as a column the table does not have, which
 * each test pins by comparing with {@code zz}, a column the table really lacks. Flight SQL and
 * {@code INFORMATION_SCHEMA.COLUMNS} are covered in {@code FlightMetadataAuthIntegrationTest}.
 *
 * <p>The masked-column cases ("Column visibility by caller role") run over a probe of their own, read by a caller whose
 * roles each confine the rows: a column one role allows reads its stored value on that role's rows and is absent, and
 * listed under {@code $masked}, on the rest.
 */
class ColumnPolicyReadIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/tables";
    private static final String TABLE = "cp_read_probe";
    private static final String ROLE = "cp-x";
    /** The probe of the masked-column cases, which create and drop it themselves. */
    private static final String MASKED = "cp_mask_probe";
    private static final String MASKED_MEMBER = "$masked";

    @BeforeEach
    void createProbe() throws Exception {
        send(post(BASE), admin(), "{\"name\":\"" + TABLE + "\",\"type\":\"source\"}").andExpect(status().isCreated());
        send(post(BASE + "/" + TABLE + "/schema"), admin(),
                "{\"columns\":[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},"
                        + "{\"source_name\":\"a\",\"name\":\"a\",\"type\":\"string\"},"
                        + "{\"source_name\":\"c\",\"name\":\"c\",\"type\":\"timestamp\"},"
                        + "{\"source_name\":\"s\",\"name\":\"s\",\"type\":\"string\",\"sensitive\":true}],"
                        + "\"ordering_key\":[\"k\"]}")
                .andExpect(status().isOk());
        send(post(BASE + "/" + TABLE + "/rows"), admin(),
                "{\"rows\":[{\"k\":\"k1\",\"a\":\"a1\",\"c\":\"2026-01-01T00:00:00Z\",\"s\":\"s1\"},"
                        + "{\"k\":\"k2\",\"a\":\"a2\",\"c\":\"2026-01-02T00:00:00Z\",\"s\":\"s2\"}]}")
                .andExpect(status().isOk());
        send(put(BASE + "/" + TABLE + "/access?role=" + ROLE), admin(),
                "{\"generation\":" + generation(TABLE) + ",\"privileges\":[\"read\",\"sensitive\",\"modify\"],\"columns\":[\"s\",\"k\",\"a\",\"k\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(3))
                .andExpect(jsonPath("$.columns[0]").value("a"))
                .andExpect(jsonPath("$.columns[1]").value("k"))
                .andExpect(jsonPath("$.columns[2]").value("s"));
    }

    @AfterEach
    void dropProbe() throws Exception {
        mockMvc.perform(delete(BASE + "/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(admin())));
    }

    @Test
    void discoveryOmitsTheHiddenColumnAndKeepsTheClearedSensitiveOne() throws Exception {
        mockMvc.perform(get("/v1/queries/entities/schema/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(restricted())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[*].name", hasItem("a")))
                .andExpect(jsonPath("$.fields[*].name", hasItem("s")))
                .andExpect(jsonPath("$.fields[*].name", not(hasItem("c"))));
        mockMvc.perform(get("/v1/queries/entities/schema/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(jsonPath("$.fields[*].name", hasItem("c")));
    }

    @Test
    void starAndFieldlessQueriesAndCsvOmitTheHiddenColumn() throws Exception {
        sql(restricted(), "SELECT * FROM " + TABLE + " ORDER BY k")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].a").value("a1"))
                .andExpect(jsonPath("$.rows[0].s").value("s1"))
                .andExpect(jsonPath("$.rows[0].c").doesNotExist());
        execute(restricted(), "{\"entity\":\"" + TABLE + "\",\"mode\":\"row\",\"page\":{\"type\":\"offset\",\"limit\":10}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].a").exists())
                .andExpect(jsonPath("$.rows[0].c").doesNotExist());

        final String header = csv("/v1/queries/execute-sql-csv", restricted(), "{\"sql\":\"SELECT * FROM " + TABLE + "\"}").split("\r\n")[0];
        assertThat(Arrays.asList(header.split(","))).contains("a", "k", "s").doesNotContain("c");
        final String structured = csv("/v1/queries/execute-csv", restricted(),
                "{\"entity\":\"" + TABLE + "\",\"mode\":\"row\",\"page\":{\"type\":\"offset\",\"limit\":10}}").split("\r\n")[0];
        assertThat(Arrays.asList(structured.split(","))).contains("a").doesNotContain("c");

        // The author still sees it everywhere.
        sql(admin(), "SELECT * FROM " + TABLE + " ORDER BY k").andExpect(jsonPath("$.rows[0].c").exists());
    }

    @Test
    void referencingTheHiddenColumnFailsAsAnUnknownField() throws Exception {
        for (final String column : List.of("c", "zz")) {
            final String filter = "\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"" + column + "\"},"
                    + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"x\"}]}";
            final String filtered = "{\"entity\":\"" + TABLE + "\",\"mode\":\"row\","
                    + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"k\"}}]," + filter + ",\"page\":{\"type\":\"offset\",\"limit\":10}}";
            final String grouped = "{\"entity\":\"" + TABLE + "\",\"mode\":\"aggregate\",\"group_by\":[\"" + column + "\"],"
                    + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"" + column + "\"}}],\"page\":{\"type\":\"offset\",\"limit\":10}}";
            expectUnknown(execute(restricted(), filtered), column);
            expectUnknown(execute(restricted(), grouped), column);
            expectUnknown(send(post("/v1/queries/translate"), restricted(), filtered), column);
            expectUnknown(sql(restricted(), "SELECT k FROM " + TABLE + " ORDER BY " + column), column);
            expectUnknown(sql(restricted(), "SELECT count(*) AS n FROM " + TABLE + " GROUP BY " + column), column);
            expectUnknown(send(post("/v1/queries/translate-sql"), restricted(),
                    "{\"sql\":\"SELECT k FROM " + TABLE + " WHERE " + column + " IS NULL\"}"), column);
        }
        // The same statements run for the author: the column exists.
        sql(admin(), "SELECT k FROM " + TABLE + " ORDER BY c").andExpect(status().isOk());
    }

    @Test
    void savedQueryAndDashboardWidgetOverTheHiddenColumnFailOnlyForTheRestrictedCaller() throws Exception {
        final String saved = JsonPath.read(send(post("/v1/saved-queries"), admin(),
                        "{\"name\":\"Hidden column\",\"scope\":\"common\",\"sql\":\"SELECT k, c FROM " + TABLE + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        final String dashboard = JsonPath.read(send(post("/v1/dashboards"), admin(),
                        "{\"name\":\"Hidden column\",\"visibility\":\"public\",\"widgets\":[{\"id\":\"w1\",\"row_limit\":10,"
                                + "\"layout\":{\"x\":0,\"y\":0,\"w\":6,\"h\":4},"
                                + "\"inline_query\":{\"name\":\"Raw\",\"sql\":\"SELECT k, c FROM " + TABLE + "\"}}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        try {
            // A client runs either through the query endpoints, under its own identity.
            final String savedSql = JsonPath.read(mockMvc.perform(get("/v1/saved-queries/" + saved)
                    .header(HttpHeaders.AUTHORIZATION, bearer(admin()))).andReturn().getResponse().getContentAsString(), "$.sql");
            final String widgetSql = JsonPath.read(mockMvc.perform(get("/v1/dashboards/" + dashboard)
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin()))).andReturn().getResponse().getContentAsString(),
                    "$.widgets[0].inline_query.sql");
            for (final String statement : List.of(savedSql, widgetSql)) {
                expectUnknown(sql(restricted(), statement), "c");
                sql(admin(), statement).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").exists());
            }
        } finally {
            mockMvc.perform(delete("/v1/saved-queries/" + saved).header(HttpHeaders.AUTHORIZATION, bearer(admin())));
            mockMvc.perform(delete("/v1/dashboards/" + dashboard).header(HttpHeaders.AUTHORIZATION, bearer(admin())));
        }
    }

    @Test
    void catalogReadOmitsTheHiddenColumnAndReportsTheRestriction() throws Exception {
        mockMvc.perform(get(BASE + "/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(restricted())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[*].name", not(hasItem("c"))))
                .andExpect(jsonPath("$.columns.length()").value(3))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(restricted())))
                .andExpect(jsonPath("$.tables[?(@.name == '" + TABLE + "')].column_count").value(3));
        mockMvc.perform(get(BASE + "/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(jsonPath("$.columns.length()").value(4))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(false));
    }

    @Test
    void schemaChangeCannotNameTheHiddenColumn() throws Exception {
        send(patch(BASE + "/" + TABLE + "/schema"), restricted(), "{\"update\":[{\"name\":\"c\",\"description\":\"d\"}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("unknown column 'c'"));
        send(patch(BASE + "/" + TABLE + "/schema"), restricted(), "{\"drop\":[\"c\"]}").andExpect(status().isUnprocessableContent());
        // A collision with it is still a name that exists.
        send(patch(BASE + "/" + TABLE + "/schema"), restricted(), "{\"add\":[{\"source_name\":\"c2\",\"name\":\"c\",\"type\":\"string\"}]}")
                .andExpect(status().isUnprocessableContent());
        send(patch(BASE + "/" + TABLE + "/schema"), restricted(), "{\"rename\":[{\"from\":\"a\",\"to\":\"c\"}]}")
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(get(BASE + "/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(jsonPath("$.columns[*].name", hasItem("c")))
                .andExpect(jsonPath("$.columns[*].name", hasItem("a")));
    }

    @Test
    void ttlCannotReachThePolicyHiddenColumn() throws Exception {
        send(patch(BASE + "/" + TABLE + "/schema"), restricted(), "{\"ttl\":{\"column\":\"c\",\"interval\":1,\"unit\":\"day\"}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
        assertThat(ClickHouseProbe.showCreateTable(TABLE)).doesNotContain("TTL");

        send(patch(BASE + "/" + TABLE + "/schema"), admin(), "{\"ttl\":{\"column\":\"c\",\"interval\":1,\"unit\":\"year\"}}")
                .andExpect(status().isOk());
        for (final String ttl : List.of("{}", "{\"column\":\"c\",\"interval\":2,\"unit\":\"year\"}")) {
            send(patch(BASE + "/" + TABLE + "/schema"), restricted(), "{\"ttl\":" + ttl + "}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("column_not_entitled"))
                    .andExpect(jsonPath("$.message").value(containsString("'c'")));
        }
        mockMvc.perform(get(BASE + "/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(jsonPath("$.ttl.column").value("c"))
                .andExpect(jsonPath("$.ttl.interval").value(1));
    }

    // --- masked columns: a caller whose roles each confine the rows ------------------------------------

    /**
     * "One role's columns are not shown on another role's rows": {@code cpm-r} reads the rows of {@code P1} with
     * {@code k}, {@code project} and {@code a}, {@code cpm-u} the rows of {@code P2} with every column, so {@code c} is
     * masked to {@code P2}, while the sensitive {@code s} stays hidden without the clearance.
     */
    @Test
    void oneRolesColumnsAreAbsentOnAnotherRolesRowsAndListedMasked() throws Exception {
        try {
            createMaskProbe();
            entry("cpm-r", "[\"read\"]", "[\"a\",\"project\",\"k\"]", projectIs("P1"));
            entry("cpm-u", "[\"read\"]", "null", projectIs("P2"));
            final String caller = caller("u9", "cpm-r", "cpm-u");

            final List<Map<String, Object>> rows = rows(caller, "SELECT k, project, a, c FROM " + MASKED + " ORDER BY k");
            assertThat(rows).extracting(row -> row.get("k")).containsExactly("k1", "k2", "k3");
            assertThat(rows.get(0)).containsEntry("a", "a1").containsEntry("c", null).containsEntry(MASKED_MEMBER, List.of("c"));
            assertThat(rows.get(1)).containsEntry("c", "c2").doesNotContainKey(MASKED_MEMBER);
            final List<Map<String, Object>> dsl = JsonPath.read(execute(caller, "{\"entity\":\"" + MASKED + "\",\"mode\":\"row\","
                    + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"k\"}},{\"expr\":{\"type\":\"field\",\"name\":\"c\"}}],"
                    + "\"sort\":[{\"field\":\"k\",\"dir\":\"asc\"}],\"page\":{\"type\":\"offset\",\"limit\":10}}")
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.rows");
            assertThat(dsl.get(0)).containsEntry("c", null).containsEntry(MASKED_MEMBER, List.of("c"));
            assertThat(dsl.get(1)).containsEntry("c", "c2").doesNotContainKey(MASKED_MEMBER);
            // CSV writes the absent cell as an empty field.
            assertThat(csv("/v1/queries/execute-sql-csv", caller, "{\"sql\":\"SELECT k, c FROM " + MASKED + " ORDER BY k\"}").split("\r\n"))
                    .startsWith("k,c", "k1,", "k2,c2");

            mockMvc.perform(get("/v1/queries/entities/schema/" + MASKED).header(HttpHeaders.AUTHORIZATION, bearer(caller)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fields[?(@.name == 'c')].masked", contains(true)))
                    .andExpect(jsonPath("$.fields[?(@.name == 'a')].masked", contains(false)))
                    .andExpect(jsonPath("$.fields[*].name", not(hasItem("s"))));
            mockMvc.perform(get(BASE + "/" + MASKED).header(HttpHeaders.AUTHORIZATION, bearer(caller)))
                    .andExpect(jsonPath("$.columns[*].name", hasItem("c")))
                    .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
            // The clearance still hides a sensitive column the policies would mask: refused word for word as a column the table lacks.
            final String lacking = JsonPath.read(sql(caller, "SELECT zz FROM " + MASKED).andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString(), "$.message");
            sql(caller, "SELECT s FROM " + MASKED).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(lacking.replace("zz", "s")));
        } finally {
            dropMaskProbe();
        }
    }

    /** "A masked column follows the caller value of the policy that allows it", with a known and an unknown value. */
    @Test
    void maskedColumnFollowsTheCallerValueOfThePolicyThatAllowsIt() throws Exception {
        try {
            createMaskProbe();
            entry("*", "[\"read\"]", "null", "{\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"user_hash\"},"
                    + "{\"type\":\"param\",\"name\":\"caller.user_hash\"}]}}");
            entry("cpm-v", "[\"read\"]", "[\"project\"]", projectIs("P2"));
            final String statement = "SELECT project, a, c FROM " + MASKED;

            final List<Map<String, Object>> known = rows(caller("u1", "cpm-v"), statement);
            assertThat(known).extracting(row -> row.get("project")).containsExactlyInAnyOrder("P1", "P2", "P2", "P3");
            for (final Map<String, Object> row : known) {
                if ("P2".equals(row.get("project"))) {
                    assertThat(row).as("a P2 row is not u1's").containsEntry("a", null).containsEntry(MASKED_MEMBER, List.of("a", "c"));
                } else {
                    assertThat(row).as("u1's own row").doesNotContainKey(MASKED_MEMBER).doesNotContainEntry("a", null);
                }
            }

            // The mask binds the caller value outside the query's own caller-value scope: the DSL and discovery resolve it too.
            final List<Map<String, Object>> dsl = JsonPath.read(execute(caller("u1", "cpm-v"), "{\"entity\":\"" + MASKED + "\",\"mode\":\"row\","
                    + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"project\"}},{\"expr\":{\"type\":\"field\",\"name\":\"a\"}}],"
                    + "\"page\":{\"type\":\"offset\",\"limit\":10}}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.rows");
            assertThat(dsl).hasSize(4);
            assertThat(dsl).filteredOn(row -> "P2".equals(row.get("project"))).hasSize(2)
                    .allSatisfy(row -> assertThat(row).containsEntry("a", null).containsEntry(MASKED_MEMBER, List.of("a")));
            // The caller's own rows read the value: a mask left unbound would make them absent too.
            assertThat(dsl).filteredOn(row -> !"P2".equals(row.get("project"))).hasSize(2)
                    .allSatisfy(row -> assertThat(row).doesNotContainKey(MASKED_MEMBER).doesNotContainEntry("a", null));
            mockMvc.perform(get("/v1/queries/entities/schema/" + MASKED).header(HttpHeaders.AUTHORIZATION, bearer(caller("u1", "cpm-v"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fields[?(@.name == 'a')].masked", contains(true)))
                    .andExpect(jsonPath("$.fields[?(@.name == 'project')].masked", contains(false)));

            final List<Map<String, Object>> unknown = rows(caller(null, "cpm-v"), statement);
            assertThat(unknown).extracting(row -> row.get("project")).containsExactly("P2", "P2");
            assertThat(unknown).allSatisfy(row -> assertThat(row).containsEntry("c", null).containsEntry(MASKED_MEMBER, List.of("a", "c")));
        } finally {
            dropMaskProbe();
        }
    }

    /** "A null policy column makes the cell absent": a row whose {@code route} is null reads as a row of another route. */
    @Test
    void nullPolicyColumnMakesTheCellAbsent() throws Exception {
        try {
            createMaskProbe();
            entry("cpm-a", "[\"read\"]", "null", "{\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"route\"},"
                    + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"r1\"}]}}");
            entry("cpm-b", "[\"read\"]", "[\"project\"]", projectIs("P2"));

            final List<Map<String, Object>> rows = rows(caller("u9", "cpm-a", "cpm-b"), "SELECT project, a FROM " + MASKED + " WHERE project = 'P2'");
            assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("a", null).containsEntry(MASKED_MEMBER, List.of("a")));
        } finally {
            dropMaskProbe();
        }
    }

    /**
     * "A caller that only gets the table lists a masked column": {@code cpm-g1} gets the rows of {@code P1} with every
     * column, {@code cpm-g2} those of {@code P2} with {@code a}, so no column is hidden by a policy and every one but
     * {@code a} is masked, which alone sets {@code columns_restricted}.
     */
    @Test
    void getOnlyCallerListsTheMaskedColumns() throws Exception {
        try {
            createMaskProbe();
            entry("cpm-g1", "[\"get\"]", "null", projectIs("P1"));
            entry("cpm-g2", "[\"get\"]", "[\"a\"]", projectIs("P2"));

            mockMvc.perform(get(BASE + "/" + MASKED).header(HttpHeaders.AUTHORIZATION, bearer(caller("u9", "cpm-g1", "cpm-g2"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.columns[*].name", containsInAnyOrder("k", "project", "a", "c", "user_hash", "route")))
                    .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
            // With one role nothing is masked.
            mockMvc.perform(get(BASE + "/" + MASKED).header(HttpHeaders.AUTHORIZATION, bearer(caller("u9", "cpm-g1"))))
                    .andExpect(jsonPath("$.permissions.columns_restricted").value(false));
        } finally {
            dropMaskProbe();
        }
    }

    // --- helpers --------------------------------------------------------------------------------------

    /**
     * {@link #MASKED}: rows of {@code P1}, {@code P2} (one with a null {@code route}) and {@code P3}, the {@code P1} and
     * {@code P3} rows written for user {@code u1}, and a sensitive column {@code s}.
     */
    private void createMaskProbe() throws Exception {
        send(post(BASE), admin(), "{\"name\":\"" + MASKED + "\",\"type\":\"source\"}").andExpect(status().isCreated());
        final StringBuilder columns = new StringBuilder();
        for (final String name : List.of("k", "project", "a", "c", "user_hash")) {
            columns.append("{\"source_name\":\"").append(name).append("\",\"name\":\"").append(name).append("\",\"type\":\"string\"},");
        }
        send(post(BASE + "/" + MASKED + "/schema"), admin(), "{\"columns\":[" + columns
                + "{\"source_name\":\"route\",\"name\":\"route\",\"type\":\"string\",\"nullable\":true},"
                + "{\"source_name\":\"s\",\"name\":\"s\",\"type\":\"string\",\"sensitive\":true}],\"ordering_key\":[\"k\"]}")
                .andExpect(status().isOk());
        send(post(BASE + "/" + MASKED + "/rows"), admin(), "{\"rows\":["
                + "{\"k\":\"k1\",\"project\":\"P1\",\"a\":\"a1\",\"c\":\"c1\",\"user_hash\":\"u1\",\"route\":\"r1\",\"s\":\"s1\"},"
                + "{\"k\":\"k2\",\"project\":\"P2\",\"a\":\"a2\",\"c\":\"c2\",\"user_hash\":\"u2\",\"route\":\"r2\",\"s\":\"s2\"},"
                + "{\"k\":\"k3\",\"project\":\"P2\",\"a\":\"a3\",\"c\":\"c3\",\"user_hash\":\"u2\",\"route\":null,\"s\":\"s3\"},"
                + "{\"k\":\"k4\",\"project\":\"P3\",\"a\":\"a4\",\"c\":\"c4\",\"user_hash\":\"u1\",\"route\":\"r3\",\"s\":\"s4\"}]}")
                .andExpect(status().isOk());
    }

    private void dropMaskProbe() throws Exception {
        mockMvc.perform(delete(BASE + "/" + MASKED).header(HttpHeaders.AUTHORIZATION, bearer(admin())));
    }

    /** PUTs {@code role}'s whole entry on {@link #MASKED}. */
    private void entry(String role, String privileges, String columns, String rows) throws Exception {
        send(put(BASE + "/" + MASKED + "/access?role=" + role), admin(), "{\"generation\":" + generation(MASKED) + ",\"privileges\":"
                + privileges + ",\"columns\":" + columns + ",\"rows\":" + rows + "}").andExpect(status().isOk());
    }

    private static String projectIs(String project) {
        return "{\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"project\"},"
                + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"" + project + "\"}]}}";
    }

    private List<Map<String, Object>> rows(String token, String statement) throws Exception {
        return JsonPath.read(sql(token, statement).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.rows");
    }

    /**
     * A readonly-issuer caller carrying {@code roles}, unmapped, whose {@code caller.user_hash} is {@code userHash} (that
     * issuer takes it from the {@code email} claim as is), or unknown when it is {@code null}.
     */
    private static String caller(String userHash, String... roles) {
        final Map<String, Object> claims = new HashMap<>(Map.of("sub", "cpm-user", "roles", List.of(roles)));
        if (userHash != null) {
            claims.put("email", userHash);
        }
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE, claims);
    }

    /** A refusal of {@code column} exactly as for {@code zz}, which the table does not have: 400 naming it. */
    private static void expectUnknown(ResultActions actions, String column) throws Exception {
        actions.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString(column)));
    }

    private long generation(String table) throws Exception {
        return ((Number) JsonPath.read(mockMvc.perform(get(BASE + "/" + table + "/access").header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.generation")).longValue();
    }

    private ResultActions execute(String token, String query) throws Exception {
        return send(post("/v1/queries/execute"), token, query);
    }

    private ResultActions sql(String token, String statement) throws Exception {
        return send(post("/v1/queries/execute-sql"), token, "{\"sql\":\"" + statement.replace("\"", "\\\"") + "\"}");
    }

    private String csv(String path, String token, String body) throws Exception {
        final MockHttpServletResponse response = mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).accept("text/csv").content(body))
                .andExpect(status().isOk()).andReturn().getResponse();
        return response.getContentAsString();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String admin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE, Map.of("sub", "cp-admin", "roles", List.of("admin"), "email", "admin@test"));
    }

    /** A caller whose only role is {@code cp-x}, unmapped: its entry on the probe alone decides what it sees. */
    private static String restricted() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE, Map.of("sub", "cp-x-user", "roles", List.of(ROLE), "email", "cpx@test"));
    }
}
