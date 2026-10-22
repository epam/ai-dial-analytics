package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end behaviour of per-role row policies in {@code oidc} mode, over real PostgreSQL and ClickHouse:
 * the policy API, enforcement through ClickHouse's {@code additional_table_filters} on every user read
 * surface and query shape, ClickHouse's permissive combination rules, and the governance edges (schema
 * guard, pipeline arming).
 *
 * <p>Personas. {@code team-x} and {@code team-y} are unmapped raw roles holding a per-table {@code read} grant on
 * the probe, so a caller carrying one reads it through that grant and is confined by its policy. {@code team-z}
 * is unmapped and has no read at all — an IdP group with nothing to do with the table. {@code viewer} maps to
 * {@code READ_ONLY_ADMIN}, a global read: with no policy of its own it lifts the confinement of the caller's
 * other roles, and with one it is confined like any other.
 */
class RowPolicyIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/tables";
    private static final String PROBE = "rls_probe";
    private static final String ROLLUP = "rls_rollup";
    private static final String PIPELINE = "rls-rollup";

    @BeforeEach
    void createProbe() throws Exception {
        createSource(PROBE);
        createSource(ROLLUP);
        asAdmin(post(BASE + "/" + PROBE + "/rows").contentType(MediaType.APPLICATION_JSON).content("""
                {"rows":[{"project":"P1","n":1},{"project":"P1","n":2},{"project":"P2","n":3},
                         {"project":"P3","n":4}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(4));
        asAdmin(put(BASE + "/" + PROBE + "/access").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"read\":[\"team-x\",\"team-y\"]}"))
                .andExpect(status().isOk());
    }

    @AfterEach
    void dropProbe() throws Exception {
        asAdmin(delete("/v1/pipelines/" + PIPELINE));
        asAdmin(delete(BASE + "/" + PROBE));
        asAdmin(delete(BASE + "/" + ROLLUP));
    }

    // --- the policy API ----------------------------------------------------------------------------------

    @Test
    void policyIsCreatedReadAndRemoved() throws Exception {
        putPolicy("team-x", projectIn("P1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter.op").value("in"));
        putPolicy("team-y", projectIn("P2")).andExpect(status().isOk());

        asAdmin(get(BASE + "/" + PROBE + "/row-policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies['team-x'].filter.args[0].name").value("project"))
                .andExpect(jsonPath("$.policies['team-y'].filter.args[1].items[0].value").value("P2"));

        asAdmin(delete(BASE + "/" + PROBE + "/row-policies/team-x")).andExpect(status().isNoContent());
        asAdmin(delete(BASE + "/" + PROBE + "/row-policies/team-x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        asAdmin(get(BASE + "/" + PROBE + "/row-policies"))
                .andExpect(jsonPath("$.policies['team-x']").doesNotExist());
    }

    @Test
    void thePolicyApiRequiresTableGrant() throws Exception {
        mockMvc.perform(get(BASE + "/" + PROBE + "/row-policies").header(HttpHeaders.AUTHORIZATION, bearer(token("viewer"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(put(BASE + "/" + PROBE + "/row-policies/team-x")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token("viewer")))
                        .contentType(MediaType.APPLICATION_JSON).content(projectIn("P1")))
                .andExpect(status().isForbidden());
        asAdmin(get(BASE + "/no_such_table/row-policies"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void anInvalidPolicyIsRefused() throws Exception {
        // A field the table does not store.
        putPolicy("team-x", "{\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"nope\"},"
                + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"P1\"}]}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
        // A parameter has nothing to bind to.
        putPolicy("team-x", "{\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"project\"},"
                + "{\"type\":\"param\",\"name\":\"p\"}]}}")
                .andExpect(status().isUnprocessableContent());
        // An aggregate is meaningless per row.
        putPolicy("team-x", "{\"filter\":{\"op\":\"gt\",\"args\":[{\"type\":\"fn\",\"name\":\"sum\",\"args\":"
                + "[{\"type\":\"field\",\"name\":\"n\"}]},{\"type\":\"value\",\"value_type\":\"long\",\"value\":\"1\"}]}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("aggregate")));
        // A policy is a filter; there is no unrestricted shape (a reader with no policy already sees every row).
        putPolicy("team-x", "{}").andExpect(status().isUnprocessableContent());
        putPolicy("team-x", "{\"all_rows\":true}").andExpect(status().isBadRequest());
        // A bundle name is not a provider role.
        putPolicy("READ_ONLY_ANALYTIC", projectIn("P1")).andExpect(status().isUnprocessableContent());
        // An unknown member is a binding failure (400, per the spec), named.
        putPolicy("team-x", "{\"where\":{}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        asAdmin(get(BASE + "/" + PROBE + "/row-policies")).andExpect(jsonPath("$.policies").isEmpty());
    }

    // --- enforcement ---------------------------------------------------------------------------------------

    @Test
    void readGranteeIsConfinedOnEverySurface() throws Exception {
        putPolicy("team-x", projectIn("P1")).andExpect(status().isOk());
        final String confined = token("team-x");

        execute(confined, "{\"entity\":\"" + PROBE + "\",\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"field\","
                + "\"name\":\"project\"}},{\"expr\":{\"type\":\"field\",\"name\":\"n\"}}],"
                + "\"page\":{\"type\":\"offset\",\"limit\":100,\"include_total\":true}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[*].project", containsInAnyOrder("P1", "P1")))
                .andExpect(jsonPath("$.totalCount").value(2));

        sql(confined, "SELECT count(*) AS c FROM " + PROBE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].c").value(2));

        mockMvc.perform(post("/v1/queries/execute-sql-csv")
                        .header(HttpHeaders.AUTHORIZATION, bearer(confined))
                        .contentType(MediaType.APPLICATION_JSON).accept("text/csv")
                        .content("{\"sql\":\"SELECT project FROM " + PROBE + " ORDER BY n\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("project\r\nP1\r\nP1\r\n"));

        // The same table, unconfined: every row.
        sql(token("viewer"), "SELECT count(*) AS c FROM " + PROBE).andExpect(jsonPath("$.rows[0].c").value(4));
    }

    @Test
    void everyReferenceOfCompositeStatementIsFiltered() throws Exception {
        putPolicy("team-x", projectIn("P1")).andExpect(status().isOk());
        final String confined = token("team-x");

        sql(confined, "SELECT count(*) AS c FROM (SELECT n FROM " + PROBE + " UNION ALL SELECT n FROM " + PROBE + ") t")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].c").value(4));
        sql(confined, "SELECT count(*) AS c FROM " + PROBE + " a JOIN " + PROBE + " b ON a.n = b.n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].c").value(2));
        sql(confined, "SELECT count(*) AS c FROM " + PROBE + " WHERE n IN (SELECT n FROM " + PROBE + ")")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].c").value(2));
    }

    @Test
    void rolesCombine() throws Exception {
        putPolicy("team-x", projectIn("P1")).andExpect(status().isOk());
        putPolicy("team-y", projectIn("P2")).andExpect(status().isOk());

        // Two confining roles: the union of their rows.
        sql(token("team-x", "team-y"), "SELECT count(*) AS c FROM " + PROBE)
                .andExpect(jsonPath("$.rows[0].c").value(3));
        // A role that cannot read the table counts for nothing, policy or not.
        sql(token("team-x", "team-z"), "SELECT count(*) AS c FROM " + PROBE)
                .andExpect(jsonPath("$.rows[0].c").value(2));
        // A role that reads the table and has no policy there lifts the confinement: `viewer` reads globally.
        sql(token("team-x", "viewer"), "SELECT count(*) AS c FROM " + PROBE)
                .andExpect(jsonPath("$.rows[0].c").value(4));
        // Giving that broad role a policy of its own confines it like any other.
        putPolicy("viewer", projectIn("P3")).andExpect(status().isOk());
        sql(token("team-x", "viewer"), "SELECT count(*) AS c FROM " + PROBE)
                .andExpect(jsonPath("$.rows[0].c").value(3));
    }

    @Test
    void policyGrantsNoRead() throws Exception {
        putPolicy("team-z", projectIn("P1")).andExpect(status().isOk());

        sql(token("team-z"), "SELECT count(*) AS c FROM " + PROBE).andExpect(status().isBadRequest());
    }

    @Test
    void permissionsReportTheConfinement() throws Exception {
        putPolicy("team-x", projectIn("P1")).andExpect(status().isOk());

        mockMvc.perform(get(BASE + "/" + PROBE).header(HttpHeaders.AUTHORIZATION, bearer(token("team-x"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.read").value(true))
                .andExpect(jsonPath("$.permissions.rows_restricted").value(true));
        mockMvc.perform(get(BASE + "/" + PROBE).header(HttpHeaders.AUTHORIZATION, bearer(token("team-x", "viewer"))))
                .andExpect(jsonPath("$.permissions.rows_restricted").value(false));
    }

    @Test
    void hostileValueStaysLiteral() throws Exception {
        putPolicy("team-x", "{\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"project\"},"
                + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"P1') OR 1 OR ('\"}]}}")
                .andExpect(status().isOk());

        sql(token("team-x"), "SELECT count(*) AS c FROM " + PROBE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].c").value(0));
    }

    @Test
    void theFilterCannotBeClearedOrBypassedFromQueryText() throws Exception {
        putPolicy("team-x", projectIn("P1")).andExpect(status().isOk());
        final String confined = token("team-x");

        sql(confined, "SELECT count(*) AS c FROM " + PROBE + " SETTINGS additional_table_filters = {}")
                .andExpect(status().isBadRequest());
        sql(confined, "SELECT count(*) AS c FROM merge('default', '^" + PROBE + "$')")
                .andExpect(status().isBadRequest());
    }

    // --- governance --------------------------------------------------------------------------------------

    @Test
    void policyColumnCannotBeDroppedOrRenamed() throws Exception {
        putPolicy("team-x", projectIn("P1")).andExpect(status().isOk());

        asAdmin(patch(BASE + "/" + PROBE + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rename\":[{\"from\":\"project\",\"to\":\"proj\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("column_in_row_policy"))
                .andExpect(jsonPath("$.message").value(containsString("team-x")));

        asAdmin(delete(BASE + "/" + PROBE + "/row-policies/team-x")).andExpect(status().isNoContent());
        asAdmin(patch(BASE + "/" + PROBE + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rename\":[{\"from\":\"project\",\"to\":\"proj\"}]}"))
                .andExpect(status().isOk());
    }

    @Test
    void confinedAuthorCannotArmOrPreviewPipelineOverItsTable() throws Exception {
        // `registry` reads globally, so it is confined only by a policy of its own.
        putPolicy("registry", projectIn("P1")).andExpect(status().isOk());
        asAdmin(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + PIPELINE + "\",\"kind\":\"aggregate\",\"target\":\"" + ROLLUP + "\","
                                + "\"inputs\":[\"" + PROBE + "\"]}"))
                .andExpect(status().isCreated());

        final String confinedAuthor = JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "rls-author", "roles", List.of("registry"), "email", "author@test"));
        mockMvc.perform(patch("/v1/pipelines/" + PIPELINE).header(HttpHeaders.AUTHORIZATION, bearer(confinedAuthor))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("row_policy_confined"))
                .andExpect(jsonPath("$.message").value(containsString(PROBE)));
        mockMvc.perform(post("/v1/pipelines/" + PIPELINE + "/preview").header(HttpHeaders.AUTHORIZATION, bearer(confinedAuthor)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("row_policy_confined"));

        // An unconfined author reaches the gate, which then judges the incomplete draft.
        asAdmin(patch("/v1/pipelines/" + PIPELINE).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isUnprocessableContent());
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private void createSource(String table) throws Exception {
        asAdmin(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + table + "\",\"description\":\"row policy probe\",\"type\":\"source\"}"))
                .andExpect(status().isCreated());
        asAdmin(post(BASE + "/" + table + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"source_name\":\"project\",\"name\":\"project\",\"type\":\"string\"},"
                                + "{\"source_name\":\"n\",\"name\":\"n\",\"type\":\"long\"}],"
                                // `project` stays out of the ordering key, which is immutable, so the schema
                                // guard test exercises the row-policy refusal rather than that one.
                                + "\"ordering_key\":[\"n\"]}"))
                .andExpect(status().isOk());
    }

    private ResultActions putPolicy(String role, String body) throws Exception {
        return asAdmin(put(BASE + "/" + PROBE + "/row-policies/" + role)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions execute(String token, String query) throws Exception {
        return mockMvc.perform(post("/v1/queries/execute").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(query));
    }

    private ResultActions sql(String token, String statement) throws Exception {
        return mockMvc.perform(post("/v1/queries/execute-sql").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":\"" + statement.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"));
    }

    private ResultActions asAdmin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(adminToken())));
    }

    private static String projectIn(String... projects) {
        final StringBuilder items = new StringBuilder();
        for (final String project : projects) {
            if (!items.isEmpty()) {
                items.append(',');
            }
            items.append("{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"").append(project).append("\"}");
        }
        return "{\"filter\":{\"op\":\"in\",\"args\":[{\"type\":\"field\",\"name\":\"project\"},"
                + "{\"type\":\"array\",\"items\":[" + items + "]}]}}";
    }

    private static String adminToken() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "rls-admin", "roles", List.of("admin"), "email", "admin@test"));
    }

    /** A caller from the readonly issuer, where {@code viewer} is READ_ONLY_ADMIN and every other role is unmapped. */
    private static String token(String... roles) {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "rls-" + String.join("-", roles), "roles", List.of(roles), "email", "rls@test"));
    }
}
