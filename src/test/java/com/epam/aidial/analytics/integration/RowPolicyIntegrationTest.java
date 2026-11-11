package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.exception.ColumnInRowPolicyException;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyService;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private static final String COUNT = "SELECT count(*) AS c FROM " + PROBE;

    @Autowired
    private TableDefinitionRepository definitions;
    @Autowired
    private ColumnMappingRepository columns;
    @Autowired
    private RowPolicyService rowPolicies;

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

    // --- caller values -----------------------------------------------------------------------------------

    @Test
    void callerValueConfinesEachCallerToItsOwnRows() throws Exception {
        putPolicy("team-x", "{\"filter\":" + callerIs("eq", "caller.user_hash") + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter.args[1].type").value("param"))
                .andExpect(jsonPath("$.filter.args[1].name").value("caller.user_hash"));
        // Stored with the parameter, never with one caller's value.
        asAdmin(get(BASE + "/" + PROBE + "/row-policies"))
                .andExpect(jsonPath("$.policies['team-x'].filter.args[1].name").value("caller.user_hash"));

        // One policy, two callers, two answers.
        sql(callerToken("P1", "team-x"), COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(2));
        sql(callerToken("P2", "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(1));
        sql(callerToken("nobody", "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(0));
        // The existing rule is untouched: a role that reads the table with no policy lifts the confinement.
        sql(callerToken("P1", "team-x", "viewer"), COUNT).andExpect(jsonPath("$.rows[0].c").value(4));
        // A hostile value is a literal like any other.
        sql(callerToken("P1') OR 1 OR ('", "team-x"), COUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].c").value(0));
        // So is one that tries to escape the quote instead of closing it.
        sql(callerToken("P1\\') OR 1 OR (\\'", "team-x"), COUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].c").value(0));
    }

    @Test
    void callerWithoutValueReadsNothingAlsoUnderNegation() throws Exception {
        // A row written for a caller with no identity stores an empty value. An unknown caller value must
        // match neither it nor anything else, and must not be turned into a match by a negation.
        asAdmin(post(BASE + "/" + PROBE + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"project\":\"\",\"n\":5}]}"))
                .andExpect(status().isOk());
        final String noValue = callerToken(null, "team-x");
        final String blankValue = callerToken("  ", "team-x");

        putPolicy("team-x", "{\"filter\":" + callerIs("eq", "caller.user_hash") + "}").andExpect(status().isOk());
        sql(noValue, COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(0));
        sql(blankValue, COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(0));

        putPolicy("team-x", "{\"filter\":" + callerIs("ne", "caller.user_hash") + "}").andExpect(status().isOk());
        sql(noValue, COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(0));
        // With a known value the negation means what its author wrote: every other row.
        sql(callerToken("P1", "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(3));

        putPolicy("team-x", "{\"filter\":{\"op\":\"not\",\"args\":[" + callerIs("eq", "caller.user_hash") + "]}}")
                .andExpect(status().isOk());
        sql(noValue, COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(0));
        sql(callerToken("P1", "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(3));
    }

    @Test
    void unknownValueLeavesTheOtherBranchOfAnOrWorking() throws Exception {
        // A token caller has a user hash and no project; one policy still serves it through the other branch.
        putPolicy("team-x", "{\"filter\":{\"op\":\"or\",\"args\":[" + callerIs("eq", "caller.project") + ","
                + callerIs("eq", "caller.user_hash") + "]}}")
                .andExpect(status().isOk());

        sql(callerToken("P1", "team-x"), COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(2));
        sql(callerToken(null, "team-x"), COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(0));
    }

    @Test
    void wildcardGrantAndPolicyGiveEveryCallerItsOwnRows() throws Exception {
        // One unit for everyone: `*` may read, confined to its own rows. No role is granted the read by name.
        asAdmin(put(BASE + "/" + PROBE + "/access").contentType(MediaType.APPLICATION_JSON).content("{\"read\":[\"*\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read[0]").value("*"));
        putPolicy("*", "{\"filter\":" + callerIs("eq", "caller.user_hash") + "}").andExpect(status().isOk());

        // A caller whose only role has nothing to do with the table, and one with no role at all.
        sql(callerToken("P1", "team-z"), COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(2));
        sql(callerToken("P2"), COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(1));
        mockMvc.perform(get(BASE + "/" + PROBE).header(HttpHeaders.AUTHORIZATION, bearer(callerToken("P2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.read").value(true))
                .andExpect(jsonPath("$.permissions.rows_restricted").value(true));
        // A role that reads the table on its own, with no policy, still reads every row.
        sql(callerToken("P1", "viewer"), COUNT).andExpect(jsonPath("$.rows[0].c").value(4));
        // A policy on a role adds its rows to the wildcard's.
        putPolicy("team-x", projectIn("P3")).andExpect(status().isOk());
        sql(callerToken("P1", "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(3));

        // Everyone may be let in to read, never to write.
        asAdmin(put(BASE + "/" + PROBE + "/access").contentType(MediaType.APPLICATION_JSON).content("{\"write\":[\"*\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void wildcardReadWithNoPolicyOfItsOwnLiftsRolePoliciesAndIsReported() throws Exception {
        putPolicy("team-x", projectIn("P1")).andExpect(status().isOk());
        sql(callerToken("P1", "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(2));

        // Every caller carries `*`, which now reads with no policy: by the rule, that lifts team-x's
        // confinement. The grant is accepted and says so.
        final String everyone = "{\"read\":[\"team-x\",\"team-y\",\"*\"]}";
        asAdmin(put(BASE + "/" + PROBE + "/access").contentType(MediaType.APPLICATION_JSON).content(everyone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ineffective_grants", hasSize(1)))
                .andExpect(jsonPath("$.ineffective_grants[0].role").value("*"))
                .andExpect(jsonPath("$.ineffective_grants[0].reason").value("lifts_row_policies"));
        sql(callerToken("P1", "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(4));

        // With a policy of its own, `*` confines again and the grant is no longer reported.
        putPolicy("*", projectIn("P2")).andExpect(status().isOk());
        asAdmin(put(BASE + "/" + PROBE + "/access").contentType(MediaType.APPLICATION_JSON).content(everyone))
                .andExpect(jsonPath("$.ineffective_grants").isEmpty());
        sql(callerToken("P1", "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(3));
    }

    @Test
    void storedPolicyIsJudgedAgainstTheSchemaItIsLeftWith() throws Exception {
        putPolicy("team-x", "{\"filter\":" + callerIs("eq", "caller.user_hash") + "}").andExpect(status().isOk());
        putPolicy("team-y", "{\"where\":\"n > 1\"}").andExpect(status().isOk());
        final TableDefinitionEntity definition = definitions.findById(PROBE).orElseThrow();
        rowPolicies.requireStillValid(definition);

        // A caller value compares only with a string, so this policy does not survive the retype; the other,
        // over a column left alone, is not what gets named.
        final ColumnMappingEntity project = columns.findByTableNameOrderByOrdinal(PROBE).stream()
                .filter(column -> "project".equals(column.getName())).findFirst().orElseThrow();
        project.setType(QueryFieldType.LONG);
        columns.save(project);
        try {
            assertThatThrownBy(() -> rowPolicies.requireStillValid(definition))
                    .isInstanceOf(ColumnInRowPolicyException.class)
                    .hasMessageContaining("team-x")
                    .hasMessageNotContaining("team-y");
        } finally {
            project.setType(QueryFieldType.STRING);
            columns.save(project);
        }
    }

    @Test
    void ownRowsPolicyOnTheUsageLogCoversItsPayloadEnrichment() throws Exception {
        // The payload table has no user column and needs none: it is only ever joined from the usage log, so
        // the usage log's policy decides which payload rows a caller can reach.
        final String usage = "dial_usage_log";
        final String payload = "dial_usage_log_payload";
        final String time = "'" + java.time.LocalDate.now(java.time.ZoneOffset.UTC) + " 00:00:00.000'";
        final String[][] rows = {
                {"00000000-0000-4000-8000-0000000000a1", "rls-hash-1", "body-1"},
                {"00000000-0000-4000-8000-0000000000a2", "rls-hash-2", "body-2"}};
        try (com.clickhouse.client.api.Client writer = clickHouseWriter()) {
            for (final String[] row : rows) {
                writer.queryAll("INSERT INTO " + usage + " (event_id, project_id, deployment, request_time, trace_id,"
                        + " core_span_id, user_hash) VALUES (toUUID('" + row[0] + "'),'rls-p','d'," + time + ",'rls-own','"
                        + row[0].substring(28) + "','" + row[1] + "')");
                writer.queryAll("INSERT INTO " + payload + " (event_id, request_time, trace_id, core_span_id,"
                        + " request_body) VALUES (toUUID('" + row[0] + "')," + time + ",'rls-own','" + row[0].substring(28)
                        + "','" + row[2] + "')");
            }
            // The payload columns are sensitive, and a clearance on a table is bounded by the one it reads.
            asAdmin(put(BASE + "/" + usage + "/access").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"read\":[\"team-x\"],\"sensitive\":[\"team-x\"]}")).andExpect(status().isOk());
            asAdmin(put(BASE + "/" + payload + "/access").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"read\":[\"team-x\"],\"sensitive\":[\"team-x\"]}")).andExpect(status().isOk());
            asAdmin(put(BASE + "/" + usage + "/row-policies/team-x").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"user_hash\"},"
                            + "{\"type\":\"param\",\"name\":\"caller.user_hash\"}]}}"))
                    .andExpect(status().isOk());

            final String query = "{\"entity\":\"" + usage + "\",\"mode\":\"row\",\"select\":[{\"expr\":{\"type\":\"field\","
                    + "\"name\":\"user_hash\"}},{\"expr\":{\"type\":\"field\",\"name\":\"" + payload + ".request_body\"}}],"
                    + "\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"trace_id\"},"
                    + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"rls-own\"}]},"
                    + "\"page\":{\"type\":\"offset\",\"limit\":100}}";
            execute(callerToken("rls-hash-1", "team-x"), query)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize(1)))
                    .andExpect(jsonPath("$.rows[0].user_hash").value("rls-hash-1"))
                    .andExpect(jsonPath("$.rows[0]['" + payload + ".request_body']").value("body-1"));
            execute(callerToken("rls-hash-2", "team-x"), query)
                    .andExpect(jsonPath("$.rows", hasSize(1)))
                    .andExpect(jsonPath("$.rows[0]['" + payload + ".request_body']").value("body-2"));
            execute(callerToken(null, "team-x"), query)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize(0)));
            sql(callerToken("rls-hash-1", "team-x"), "SELECT count(*) AS c FROM " + usage + " WHERE trace_id = 'rls-own'")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0].c").value(1));
            // Unconfined, both rows are there to be confined from.
            execute(adminToken(), query).andExpect(jsonPath("$.rows", hasSize(2)));
        } finally {
            asAdmin(delete(BASE + "/" + usage + "/row-policies/team-x"));
            // The derived table first: the source's clearance cannot be withdrawn from under it.
            // An absent key leaves its list untouched, so each is cleared by name.
            final String none = "{\"read\":[],\"sensitive\":[]}";
            asAdmin(put(BASE + "/" + payload + "/access").contentType(MediaType.APPLICATION_JSON).content(none));
            asAdmin(put(BASE + "/" + usage + "/access").contentType(MediaType.APPLICATION_JSON).content(none));
            try (com.clickhouse.client.api.Client writer = clickHouseWriter()) {
                writer.queryAll("ALTER TABLE " + usage + " DELETE WHERE trace_id = 'rls-own' SETTINGS mutations_sync = 1");
                writer.queryAll("ALTER TABLE " + payload + " DELETE WHERE trace_id = 'rls-own' SETTINGS mutations_sync = 1");
            }
        }
    }

    @Test
    void callerValueOutsideItsOnePositionIsRefused() throws Exception {
        final String field = "{\"type\":\"field\",\"name\":\"project\"}";
        final String caller = "{\"type\":\"param\",\"name\":\"caller.user_hash\"}";
        final String literal = "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"\"}";
        final List<String> refused = List.of(
                // The set is closed, and its names are exact.
                callerIs("eq", "caller.email"),
                callerIs("eq", "Caller.User_Hash"),
                // Only a string field: against another type the rendering would depend on the caller's value.
                "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"n\"}," + caller + "]}",
                // Only equality.
                callerIs("ge", "caller.user_hash"),
                // A function could turn an unknown value into the constant that hashless rows store.
                "{\"op\":\"eq\",\"args\":[" + field + ",{\"type\":\"fn\",\"name\":\"coalesce\",\"args\":[" + caller + ","
                        + literal + "]}]}",
                "{\"op\":\"in\",\"args\":[" + field + ",{\"type\":\"array\",\"items\":[" + caller + "]}]}",
                "{\"op\":\"eq\",\"args\":[" + caller + "," + literal + "]}",
                // A case branch is one more way to a constant.
                "{\"op\":\"eq\",\"args\":[" + field + ",{\"type\":\"case\",\"when\":[{\"when\":{\"op\":\"eq\",\"args\":["
                        + field + "," + literal + "]},\"then\":" + caller + "}],\"else\":" + literal + "}]}",
                // Twenty comparisons are small as stored and too long once each carries a worst-case value.
                "{\"op\":\"or\",\"args\":[" + String.join(",", java.util.Collections.nCopies(20, callerIs("eq", "caller.user_hash")))
                        + "]}");
        for (final String filter : refused) {
            putPolicy("team-x", "{\"filter\":" + filter + "}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("validation_error"));
        }

        asAdmin(get(BASE + "/" + PROBE + "/row-policies")).andExpect(jsonPath("$.policies").isEmpty());
    }

    @Test
    void callerValueAgainstAnEnumColumnIsRefused() throws Exception {
        final String table = "rls_enum_probe";
        try {
            asAdmin(post(BASE).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + table + "\",\"type\":\"source\"}")).andExpect(status().isCreated());
            asAdmin(post(BASE + "/" + table + "/schema").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"columns\":[{\"source_name\":\"id\",\"name\":\"id\",\"type\":\"string\"},"
                            + "{\"source_name\":\"kind\",\"name\":\"kind\",\"type\":\"enum\",\"enum_values\":[\"a\",\"b\"]}],"
                            + "\"ordering_key\":[\"id\"]}"))
                    .andExpect(status().isOk());

            asAdmin(put(BASE + "/" + table + "/row-policies/team-x").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"kind\"},"
                            + "{\"type\":\"param\",\"name\":\"caller.user_hash\"}]}}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("validation_error"));
        } finally {
            asAdmin(delete(BASE + "/" + table));
        }
    }

    @Test
    void overLongCallerValueIsUnknown() throws Exception {
        // Under `ne` the two outcomes differ: a known value that matches no row reads every row, an unknown
        // one reads none.
        putPolicy("team-x", "{\"filter\":" + callerIs("ne", "caller.user_hash") + "}").andExpect(status().isOk());

        sql(callerToken("x".repeat(512), "team-x"), COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(4));
        sql(callerToken("x".repeat(513), "team-x"), COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(0));
        // The bound is in bytes: 257 two-byte characters are 514 bytes.
        sql(callerToken("é".repeat(257), "team-x"), COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(0));
    }

    // --- a policy written as a SQL condition ---------------------------------------------------------------

    @Test
    void policyWrittenAsSqlConditionIsStoredAsItsFilter() throws Exception {
        putPolicy("team-x", "{\"where\":\"project IN ('P1', 'P2')\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter").exists())
                // The text is an input format: what is stored and returned is the filter.
                .andExpect(jsonPath("$.where").doesNotExist());
        sql(token("team-x"), COUNT).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].c").value(3));
        asAdmin(get(BASE + "/" + PROBE + "/row-policies"))
                .andExpect(jsonPath("$.policies['team-x'].filter").exists())
                .andExpect(jsonPath("$.policies['team-x'].where").doesNotExist());

        putPolicy("team-x", "{\"where\":\"project = caller.user_hash\"}")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"type\":\"param\"")))
                .andExpect(content().string(containsString("\"name\":\"caller.user_hash\"")));
        sql(callerToken("P1", "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(2));
        sql(callerToken(null, "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(0));

        putPolicy("team-x", "{\"where\":\"project = caller.user_hash OR n > 3\"}").andExpect(status().isOk());
        sql(callerToken("P1", "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(3));
        sql(callerToken(null, "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(1));

        // A literal stays a literal whatever it spells: nobody's value is substituted.
        putPolicy("team-x", "{\"where\":\"project = 'caller.user_hash'\"}")
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("param"))))
                .andExpect(content().string(containsString("\"value\":\"caller.user_hash\"")));
        sql(callerToken("P1", "team-x"), COUNT).andExpect(jsonPath("$.rows[0].c").value(0));
    }

    @Test
    void sqlConditionOutsideThePolicyScopeIsRefused() throws Exception {
        final List<String> refused = List.of(
                // One form or the other.
                "{\"where\":\"project = 'P1'\",\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"project\"},"
                        + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"P1\"}]}}",
                "{\"where\":\" \"}",
                "{\"where\":\"project = \"}",
                "{\"where\":\"nope = 'P1'\"}",
                // Another table is out of a policy's reach in SQL as it is in the filter grammar.
                "{\"where\":\"project IN (SELECT project FROM " + ROLLUP + ")\"}",
                // A condition only: no clause smuggled in after it, with or without balanced parentheses.
                "{\"where\":\"project = 'P1' LIMIT 1\"}",
                "{\"where\":\"project = 'P1') GROUP BY (project\"}",
                "{\"where\":\"project = 'P1') ORDER BY (n\"}",
                // A line comment does not swallow the closing parenthesis and let a clause through.
                "{\"where\":\"project = 'P1') OFFSET 5 ROWS --\"}",
                "{\"where\":\"project = 'P1') LIMIT 1 --\"}",
                // The placement rule of a caller value holds in SQL too.
                "{\"where\":\"coalesce(caller.user_hash, '') = project\"}",
                "{\"where\":\"n = caller.user_hash\"}",
                "{\"where\":\"project IN (caller.user_hash, 'P1')\"}",
                "{\"where\":\"project = caller.email\"}");
        for (final String body : refused) {
            putPolicy("team-x", body)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("validation_error"));
        }
        // A subquery and a clause are refused on the text alone, before any name is resolved, so the answer
        // does not tell whether a table the writer cannot see exists.
        for (final String condition : List.of(
                "project IN (SELECT project FROM " + ROLLUP + ")",
                "project IN (SELECT project FROM no_such_table)",
                "EXISTS (SELECT 1 FROM no_such_table)",
                // FINAL after an alias parses only once the front end has rewritten it.
                "project IN (SELECT project FROM " + ROLLUP + " AS o FINAL)",
                "project IN (SELECT project FROM no_such_table AS o FINAL)",
                // A dotted name is a column of an enrichment, existing or not.
                "\\\"no_such_enrichment.col\\\" = 'x'",
                "project = 'P1') WINDOW w AS (ORDER BY n",
                "project = 'P1') ORDER BY (n")) {
            putPolicy("team-x", "{\"where\":\"" + condition + "\"}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.message").value(containsString("no subquery and no other clause")));
        }
        asAdmin(get(BASE + "/" + PROBE + "/row-policies")).andExpect(jsonPath("$.policies").isEmpty());

        // An enrichment is never a statement's FROM, so its policies are written as a filter.
        asAdmin(put(BASE + "/dial_usage_log_payload/row-policies/team-x").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"where\":\"trace_id = 'x'\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("enrichment")));
        asAdmin(put(BASE + "/no_such_table/row-policies/team-x").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"where\":\"a = 1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void queryParameterNamedLikeCallerValueIsNotBoundToTheCaller() throws Exception {
        // The reserved names mean the caller only inside a row policy. A query has no parameters at all, and
        // one named like a caller value is refused like any other rather than filled in from the identity.
        execute(callerToken("P1", "viewer"), "{\"entity\":\"" + PROBE + "\",\"mode\":\"row\",\"select\":[{\"expr\":"
                + "{\"type\":\"field\",\"name\":\"n\"}}],\"filter\":" + callerIs("eq", "caller.user_hash") + ","
                + "\"page\":{\"type\":\"offset\",\"limit\":100}}")
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.message").value(containsString("param expressions are not supported")));
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

    /** A direct writer for fixtures in the two ingest tables, which no API inserts into. */
    private static com.clickhouse.client.api.Client clickHouseWriter() {
        final var container = ClickHouseContainerSupport.CLICKHOUSE;
        return new com.clickhouse.client.api.Client.Builder()
                .addEndpoint("http://" + container.getHost() + ":"
                        + container.getMappedPort(ClickHouseContainerSupport.HTTP_PORT) + "/")
                .setUsername(container.getUsername())
                .setPassword(container.getPassword())
                .setDefaultDatabase(container.getDatabaseName())
                .build();
    }

    /** {@code project <op> <caller value>}: the one shape a caller value is admitted in. */
    private static String callerIs(String op, String callerValue) {
        return "{\"op\":\"" + op + "\",\"args\":[{\"type\":\"field\",\"name\":\"project\"},"
                + "{\"type\":\"param\",\"name\":\"" + callerValue + "\"}]}";
    }

    /**
     * A readonly-issuer caller whose {@code caller.user_hash} is {@code value} — that issuer derives it from
     * the {@code email} claim unobfuscated — or who has none when {@code value} is {@code null}.
     */
    private static String callerToken(String value, String... roles) {
        final Map<String, Object> claims = new java.util.HashMap<>(Map.of("sub", "rls-caller", "roles", List.of(roles)));
        if (value != null) {
            claims.put("email", value);
        }
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE, claims);
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
