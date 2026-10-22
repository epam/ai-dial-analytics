package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A grant change made through the access or row-policy endpoints is an {@code Update} of the table, and its
 * before/after — the revision read at that revision and the one before — must show what changed to a caller
 * who may read grants, and nothing of the grants to one who may not. A write that changes no role set and no
 * policy records no activity at all.
 *
 * <p>The access case runs on an active enrichment, the shape of the reported bug: two {@code Update / Table}
 * rows from two access saves, each of whose detail pages showed no difference because the revision read did
 * not carry the lists.
 */
class TableGrantRevisionIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/tables";
    private static final String SOURCE = "grant_rev_source";
    private static final String ENRICHMENT = "grant_rev_enrichment";

    @BeforeEach
    void createTables() throws Exception {
        perform(post(BASE), fullAdmin(), "{\"name\":\"" + SOURCE + "\",\"type\":\"source\"}")
                .andExpect(status().isCreated());
        perform(post(BASE + "/" + SOURCE + "/schema"), fullAdmin(),
                "{\"columns\":[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},"
                        + "{\"source_name\":\"project\",\"name\":\"project\",\"type\":\"string\"}],"
                        + "\"ordering_key\":[\"k\"]}")
                .andExpect(status().isOk());
        perform(post(BASE), fullAdmin(),
                "{\"name\":\"" + ENRICHMENT + "\",\"type\":\"enrichment\",\"source_table\":\"" + SOURCE + "\"}")
                .andExpect(status().isCreated());
        perform(post(BASE + "/" + ENRICHMENT + "/schema"), fullAdmin(),
                "{\"grain_key\":\"k\",\"columns\":[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @AfterEach
    void dropTables() throws Exception {
        mockMvc.perform(delete(BASE + "/" + ENRICHMENT).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
        mockMvc.perform(delete(BASE + "/" + SOURCE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
    }

    @Test
    void anAccessChangeIsVisibleInTheRevisionDiff() throws Exception {
        final int before = tableUpdates(ENRICHMENT).size();
        perform(put(BASE + "/" + ENRICHMENT + "/access"), fullAdmin(), "{\"write\":[\"team-a\",\"team-b\"]}")
                .andExpect(status().isOk());

        final List<Integer> updates = tableUpdates(ENRICHMENT);
        assertThat(updates).hasSize(before + 1);
        final int revision = updates.getFirst();

        snapshot(ENRICHMENT, revision - 1, fullAdmin())
                .andExpect(jsonPath("$.access.write").isEmpty())
                .andExpect(jsonPath("$.row_policies").isEmpty());
        snapshot(ENRICHMENT, revision, fullAdmin())
                .andExpect(jsonPath("$.access.write[0]").value("team-a"))
                .andExpect(jsonPath("$.access.write[1]").value("team-b"))
                .andExpect(jsonPath("$.access.get").isEmpty());
    }

    @Test
    void rowPolicyChangeIsVisibleInTheRevisionDiff() throws Exception {
        perform(put(BASE + "/" + SOURCE + "/row-policies/team-x"), fullAdmin(), projectIn("P1"))
                .andExpect(status().isOk());
        final int created = tableUpdates(SOURCE).getFirst();
        perform(delete(BASE + "/" + SOURCE + "/row-policies/team-x"), fullAdmin(), "")
                .andExpect(status().isNoContent());
        final int removed = tableUpdates(SOURCE).getFirst();
        assertThat(removed).isGreaterThan(created);

        snapshot(SOURCE, created - 1, fullAdmin()).andExpect(jsonPath("$.row_policies").isEmpty());
        snapshot(SOURCE, created, fullAdmin())
                .andExpect(jsonPath("$.row_policies['team-x'].filter.op").value("in"))
                .andExpect(jsonPath("$.row_policies['team-x'].filter.args[0].name").value("project"));
        snapshot(SOURCE, removed, fullAdmin()).andExpect(jsonPath("$.row_policies").isEmpty());
    }

    @Test
    void callerWithoutTheGrantPrivilegeReadsNoGrantMembers() throws Exception {
        perform(put(BASE + "/" + ENRICHMENT + "/access"), fullAdmin(), "{\"write\":[\"team-a\"]}")
                .andExpect(status().isOk());
        final int revision = tableUpdates(ENRICHMENT).getFirst();

        snapshot(ENRICHMENT, revision, viewer())
                .andExpect(jsonPath("$.name").value(ENRICHMENT))
                .andExpect(jsonPath("$.access").doesNotExist())
                .andExpect(jsonPath("$.row_policies").doesNotExist());
        // The live reads carry neither member for anyone, the grant holder included.
        mockMvc.perform(get(BASE + "/" + ENRICHMENT).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access").doesNotExist())
                .andExpect(jsonPath("$.row_policies").doesNotExist());
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + ENRICHMENT + "')].access").isEmpty());
    }

    @Test
    void reorderingRolesRecordsNoActivity() throws Exception {
        perform(put(BASE + "/" + ENRICHMENT + "/access"), fullAdmin(), "{\"write\":[\"team-a\",\"team-b\"]}")
                .andExpect(status().isOk());
        final int before = tableUpdates(ENRICHMENT).size();

        perform(put(BASE + "/" + ENRICHMENT + "/access"), fullAdmin(), "{\"write\":[\"team-b\",\"team-a\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.write[0]").value("team-a"));

        assertThat(tableUpdates(ENRICHMENT)).hasSize(before);
        mockMvc.perform(get(BASE + "/" + ENRICHMENT + "/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(jsonPath("$.write[0]").value("team-a"))
                .andExpect(jsonPath("$.write[1]").value("team-b"));
    }

    @Test
    void savingOneChangedListKeepsStoredOrderOfReorderedOne() throws Exception {
        perform(put(BASE + "/" + ENRICHMENT + "/access"), fullAdmin(), "{\"write\":[\"team-a\",\"team-b\"]}")
                .andExpect(status().isOk());

        perform(put(BASE + "/" + ENRICHMENT + "/access"), fullAdmin(),
                "{\"write\":[\"team-b\",\"team-a\"],\"get\":[\"team-c\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.get[0]").value("team-c"))
                .andExpect(jsonPath("$.write[0]").value("team-a"))
                .andExpect(jsonPath("$.write[1]").value("team-b"));

        final List<Integer> updates = tableUpdates(ENRICHMENT);
        snapshot(ENRICHMENT, updates.getFirst(), fullAdmin())
                .andExpect(jsonPath("$.access.write[0]").value("team-a"))
                .andExpect(jsonPath("$.access.get[0]").value("team-c"));
    }

    @Test
    void repeatingTheSameRowPolicyPutRecordsOneActivity() throws Exception {
        final int before = tableUpdates(SOURCE).size();

        perform(put(BASE + "/" + SOURCE + "/row-policies/team-x"), fullAdmin(), projectIn("P1"))
                .andExpect(status().isOk());
        perform(put(BASE + "/" + SOURCE + "/row-policies/team-x"), fullAdmin(), projectIn("P1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter.op").value("in"));

        assertThat(tableUpdates(SOURCE)).hasSize(before + 1);
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** The revisions of the table's {@code Update / Table} activities, newest first. */
    private List<Integer> tableUpdates(String table) throws Exception {
        final String feed = mockMvc.perform(post("/v1/activities")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":["
                                + "{\"column\":\"resourceId\",\"operator\":\"eq\",\"value\":\"" + table + "\"},"
                                + "{\"column\":\"resourceType\",\"operator\":\"eq\",\"value\":\"Table\"},"
                                + "{\"column\":\"activityType\",\"operator\":\"eq\",\"value\":\"Update\"}],"
                                + "\"sorts\":[{\"column\":\"revision\",\"direction\":\"DESC\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(feed, "$.data[*].revision");
    }

    private ResultActions snapshot(String table, int revision, String token) throws Exception {
        return mockMvc.perform(get(BASE + "/" + table + "/revision/" + revision)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mockMvc.perform(request
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String projectIn(String project) {
        return "{\"filter\":{\"op\":\"in\",\"args\":[{\"type\":\"field\",\"name\":\"project\"},"
                + "{\"type\":\"array\",\"items\":[{\"type\":\"value\",\"value_type\":\"string\",\"value\":\""
                + project + "\"}]}]}}";
    }

    private static String fullAdmin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }

    /** {@code READ_ONLY_ADMIN}: may read every table and the audit feed, but not grants. */
    private static String viewer() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "viewer-user", "roles", List.of("viewer"), "email", "viewer@test"));
    }
}
