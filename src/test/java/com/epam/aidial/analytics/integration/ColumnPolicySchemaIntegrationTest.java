package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
 * "Column policies follow a rename and refuse an emptying drop", end to end: the stored lists after a rename, a drop,
 * a combined {@code PATCH}, an enrichment column rename and an enrichment delete, which generation moves, the refusals
 * and that a refusal issues no DDL, and the audit revision a source records for a rewrite caused by its enrichment.
 */
class ColumnPolicySchemaIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/tables";
    private static final String TABLE = "cs_probe";
    private static final String LABELS = "cs_probe_labels";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void createProbe() throws Exception {
        dropProbe();
        send(post(BASE), "{\"name\":\"" + TABLE + "\",\"type\":\"source\"}").andExpect(status().isCreated());
        send(post(BASE + "/" + TABLE + "/schema"), "{\"columns\":[" + column("k") + "," + column("a") + "," + column("b") + ","
                + column("c") + "," + column("project") + "," + column("z") + "],\"ordering_key\":[\"k\"]}").andExpect(status().isOk());
    }

    @AfterEach
    void dropProbe() throws Exception {
        for (final String table : List.of(LABELS, TABLE)) {
            mockMvc.perform(delete(BASE + "/" + table).header(HttpHeaders.AUTHORIZATION, bearer(admin())));
        }
    }

    @Test
    void renameRewritesTheListAndMovesTheGeneration() throws Exception {
        putEntry("x", "\"privileges\":[\"read\"],\"columns\":[\"a\",\"b\"]");
        final long before = generation(TABLE);
        patchSchema("{\"rename\":[{\"from\":\"b\",\"to\":\"b2\"}]}").andExpect(status().isOk());

        expectColumns("x", "a", "b2");
        assertThat(generation(TABLE)).isEqualTo(before + 1);
        mockMvc.perform(get(BASE + "/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(caller("x"))))
                .andExpect(jsonPath("$.columns[*].name", hasItem("b2")))
                .andExpect(jsonPath("$.columns.length()").value(2));
    }

    @Test
    void dropPrunesTheListAndTouchFreeChangeLeavesTheGeneration() throws Exception {
        putEntry("x", "\"privileges\":[\"read\"],\"columns\":[\"a\",\"b\"]");
        final long before = generation(TABLE);
        patchSchema("{\"drop\":[\"z\"]}").andExpect(status().isOk());
        assertThat(generation(TABLE)).isEqualTo(before);

        patchSchema("{\"drop\":[\"b\"]}").andExpect(status().isOk());
        expectColumns("x", "a");
        assertThat(generation(TABLE)).isEqualTo(before + 1);
    }

    @Test
    void droppingColumnAndAddingNewOneUnderItsNameDoesNotListTheNewOne() throws Exception {
        putEntry("x", "\"privileges\":[\"read\"],\"columns\":[\"a\",\"c\"]");
        patchSchema("{\"drop\":[\"c\"],\"add\":[{\"source_name\":\"c_new\",\"name\":\"c\",\"type\":\"string\"}]}").andExpect(status().isOk());

        expectColumns("x", "a");
        mockMvc.perform(get(BASE + "/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(caller("x"))))
                .andExpect(jsonPath("$.columns[*].name", not(hasItem("c"))));
    }

    @Test
    void droppingColumnAndRenamingAnotherToItsNameKeepsTheRenamedOne() throws Exception {
        putEntry("x", "\"privileges\":[\"read\"],\"columns\":[\"a\",\"b\",\"c\"]");
        patchSchema("{\"drop\":[\"c\"],\"rename\":[{\"from\":\"b\",\"to\":\"c\"}]}").andExpect(status().isOk());
        expectColumns("x", "a", "c");
    }

    @Test
    void renameNeverRefillsListTheDropEmptied() throws Exception {
        putEntry("x", "\"privileges\":[\"read\"],\"columns\":[\"c\"]");
        patchSchema("{\"drop\":[\"c\"],\"rename\":[{\"from\":\"b\",\"to\":\"c\"}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("column_in_column_policy"));
        assertThat(ClickHouseProbe.showCreateTable(TABLE)).contains("`b`").contains("`c`");
        expectColumns("x", "c");
    }

    @Test
    void anEmptyingDropIsRefusedBeforeAnyAlter() throws Exception {
        putEntry("x", "\"privileges\":[\"read\"],\"columns\":[\"b\"]");
        final long before = generation(TABLE);
        patchSchema("{\"drop\":[\"b\"]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("column_in_column_policy"))
                .andExpect(jsonPath("$.message").value(containsString("b")))
                .andExpect(jsonPath("$.message").value(containsString("x")));
        assertThat(ClickHouseProbe.showCreateTable(TABLE)).contains("`b`");
        expectColumns("x", "b");
        assertThat(generation(TABLE)).isEqualTo(before);
    }

    @Test
    void theRowPolicyGuardWinsWhenBothApply() throws Exception {
        putEntry("x", "\"privileges\":[\"read\"],\"rows\":{\"where\":\"project = 'P1'\"}");
        putEntry("y", "\"privileges\":[\"read\"],\"columns\":[\"project\"]");
        patchSchema("{\"drop\":[\"project\"]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("column_in_row_policy"));
    }

    @Test
    void anEnrichmentColumnRenameRewritesTheSourcesListInTheSameRevision() throws Exception {
        labels();
        putEntry("x", "\"privileges\":[\"read\"],\"columns\":[\"a\",\"" + LABELS + ".label\",\"" + LABELS + ".note\"]");
        final long source = generation(TABLE);
        final long enrichment = generation(LABELS);

        send(patch(BASE + "/" + LABELS + "/schema"), "{\"rename\":[{\"from\":\"label\",\"to\":\"title\"}]}").andExpect(status().isOk());
        expectColumns("x", "a", LABELS + ".note", LABELS + ".title");
        assertThat(generation(TABLE)).isEqualTo(source + 1);
        assertThat(generation(LABELS)).isEqualTo(enrichment);
        final int renamed = jdbc.queryForObject("SELECT max(rev) FROM column_mapping_aud WHERE table_name = ?", Integer.class, LABELS);
        assertThat(tableUpdates(TABLE).getFirst()).isEqualTo(renamed);
        snapshot(TABLE, renamed).andExpect(jsonPath("$.roles.x.columns[2]").value(LABELS + ".title"));

        // A drop of an enrichment column prunes the source's list the same way.
        send(patch(BASE + "/" + LABELS + "/schema"), "{\"drop\":[\"note\"]}").andExpect(status().isOk());
        expectColumns("x", "a", LABELS + ".title");
        assertThat(generation(TABLE)).isEqualTo(source + 2);
        final int dropped = jdbc.queryForObject("SELECT max(rev) FROM column_mapping_aud WHERE table_name = ?", Integer.class, LABELS);
        assertThat(tableUpdates(TABLE).getFirst()).isEqualTo(dropped);
    }

    @Test
    void enrichmentDeleteThatWouldEmptyListIsRefusedAndOtherwisePrunes() throws Exception {
        labels();
        putEntry("x", "\"privileges\":[\"read\"],\"columns\":[\"" + LABELS + ".label\"]");
        send(delete(BASE + "/" + LABELS), "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("column_in_column_policy"));
        assertThat(ClickHouseProbe.showCreateTable(LABELS)).contains("label");
        mockMvc.perform(get(BASE + "/" + LABELS).header(HttpHeaders.AUTHORIZATION, bearer(admin()))).andExpect(status().isOk());

        putEntry("x", "\"privileges\":[\"read\"],\"columns\":[\"a\",\"" + LABELS + ".label\"]");
        final long source = generation(TABLE);
        send(delete(BASE + "/" + LABELS), "").andExpect(status().isNoContent());
        expectColumns("x", "a");
        assertThat(generation(TABLE)).isEqualTo(source + 1);
        snapshot(TABLE, tableUpdates(TABLE).getFirst()).andExpect(jsonPath("$.roles.x.columns.length()").value(1));
    }

    // --- helpers --------------------------------------------------------------------------------------

    private void labels() throws Exception {
        send(post(BASE), "{\"name\":\"" + LABELS + "\",\"type\":\"enrichment\",\"source_table\":\"" + TABLE + "\"}").andExpect(status().isCreated());
        send(post(BASE + "/" + LABELS + "/schema"), "{\"grain_key\":\"k\",\"columns\":[" + column("label") + "," + column("note") + "]}")
                .andExpect(status().isOk());
    }

    private static String column(String name) {
        return "{\"source_name\":\"" + name + "\",\"name\":\"" + name + "\",\"type\":\"string\"}";
    }

    private void expectColumns(String role, String... columns) throws Exception {
        final List<String> stored = JsonPath.read(mockMvc.perform(get(BASE + "/" + TABLE + "/access/" + role)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.columns");
        assertThat(stored).containsExactly(columns);
    }

    private void putEntry(String role, String members) throws Exception {
        send(put(BASE + "/" + TABLE + "/access/" + role), "{\"generation\":" + generation(TABLE) + "," + members + "}").andExpect(status().isOk());
    }

    private long generation(String table) throws Exception {
        return ((Number) JsonPath.read(mockMvc.perform(get(BASE + "/" + table + "/access").header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.generation")).longValue();
    }

    private ResultActions patchSchema(String body) throws Exception {
        return send(patch(BASE + "/" + TABLE + "/schema"), body);
    }

    /** The revisions of the table's {@code Update / Table} activities, newest first. */
    private List<Integer> tableUpdates(String table) throws Exception {
        final String feed = send(post("/v1/activities"), "{\"pageSize\":1000,\"filters\":["
                + "{\"column\":\"resourceId\",\"operator\":\"eq\",\"value\":\"" + table + "\"},"
                + "{\"column\":\"resourceType\",\"operator\":\"eq\",\"value\":\"Table\"},"
                + "{\"column\":\"activityType\",\"operator\":\"eq\",\"value\":\"Update\"}],"
                + "\"sorts\":[{\"column\":\"revision\",\"direction\":\"DESC\"}]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(feed, "$.data[*].revision");
    }

    private ResultActions snapshot(String table, int revision) throws Exception {
        return mockMvc.perform(get(BASE + "/" + table + "/revision/" + revision).header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(status().isOk());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(admin())).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String admin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE, Map.of("sub", "cs-admin", "roles", List.of("admin"), "email", "admin@test"));
    }

    private static String caller(String role) {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE, Map.of("sub", role + "-user", "roles", List.of(role), "email", role + "@test"));
    }
}
