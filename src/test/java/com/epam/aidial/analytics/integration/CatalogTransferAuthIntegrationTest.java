package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catalog transfer authorization in {@code oidc} mode: per-table visibility and sensitive clearance on export, access
 * exported and imported only on request and only with {@code TABLE_GRANT}, and the gates import repeats because it
 * calls the create services in-process.
 */
class CatalogTransferAuthIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String PLAIN = "cxa_plain";
    private static final String SECRET = "cxa_secret";
    private static final String HIDDEN = "cxa_hidden";
    private static final String LABELS = "cxa_plain_labels";
    private static final String COLUMNS = "cxa_columns";
    private static final String EXPORTER = "exporter";

    @BeforeEach
    void createTables() throws Exception {
        dropTables();
        table(PLAIN, "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"}]");
        table(SECRET, "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},"
                + "{\"source_name\":\"s\",\"name\":\"s\",\"type\":\"string\",\"sensitive\":true}]");
        table(HIDDEN, "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"}]");
        for (final String table : List.of(PLAIN, SECRET)) {
            grant(table, EXPORTER, "get");
        }
    }

    @AfterEach
    void dropTables() throws Exception {
        for (final String table : List.of(LABELS, PLAIN, SECRET, HIDDEN, COLUMNS)) {
            mockMvc.perform(delete("/v1/tables/" + table).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
        }
    }

    @Test
    void exportIsGatedPerTableAndNeverCarriesGrants() throws Exception {
        export(caller(EXPORTER), PLAIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0].access").doesNotExist());
        export(caller(EXPORTER), SECRET)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"));
        export(caller(EXPORTER), HIDDEN)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        export(fullAdmin(), PLAIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_omitted").doesNotExist())
                .andExpect(jsonPath("$.tables[0].access").doesNotExist());
    }

    @Test
    void exportRefusesPolicyHiddenColumnBeforeUnentitledSensitiveOne() throws Exception {
        table(COLUMNS, "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},{\"source_name\":\"c\",\"name\":\"c\",\"type\":\"string\"},"
                + "{\"source_name\":\"s\",\"name\":\"s\",\"type\":\"string\",\"sensitive\":true}]");
        entry(COLUMNS, EXPORTER, "\"privileges\":[\"get\"],\"columns\":[\"k\",\"s\"]");
        export(caller(EXPORTER), COLUMNS)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("column_not_entitled"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("'c'")));

        // A column both hidden and unentitled-sensitive is column_not_entitled; so is a bundle mixing a table whose
        // sensitive column is unentitled with one whose column is hidden, whichever is selected first.
        entry(COLUMNS, EXPORTER, "\"privileges\":[\"get\"],\"columns\":[\"k\"]");
        export(caller(EXPORTER), COLUMNS).andExpect(jsonPath("$.error").value("column_not_entitled"));
        for (final List<String> order : List.of(List.of(SECRET, COLUMNS), List.of(COLUMNS, SECRET))) {
            send(post("/v1/catalog/export"), caller(EXPORTER), "{\"components\":[{\"type\":\"table\",\"name\":\"" + order.get(0) + "\"},"
                    + "{\"type\":\"table\",\"name\":\"" + order.get(1) + "\"}]}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("column_not_entitled"));
        }

        // The bundle never carries access entries, row policies or column policies.
        final String bundle = export(fullAdmin(), COLUMNS).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(bundle).doesNotContain("column_policies").doesNotContain("row_policies").doesNotContain(EXPORTER);
    }

    @Test
    void importRepeatsTheDefineSchemaGate() throws Exception {
        final String bundle = export(fullAdmin(), PLAIN).andReturn().getResponse().getContentAsString();
        mockMvc.perform(delete("/v1/tables/" + PLAIN).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isNoContent());

        mockMvc.perform(multipart("/v1/catalog/import")
                        .file(new MockMultipartFile("file", "b.json", MediaType.APPLICATION_JSON_VALUE,
                                bundle.getBytes(StandardCharsets.UTF_8)))
                        .param("resolution_policy", "SKIP_IF_EXISTS")
                        .header(HttpHeaders.AUTHORIZATION, bearer(caller("importer"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(get("/v1/tables/" + PLAIN).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isNotFound());
    }

    @Test
    void importPrevFollowsTheCallersVisibility() throws Exception {
        for (final String table : List.of(PLAIN, SECRET)) {
            grant(table, "importer", "get");
        }
        final String bundle = send(post("/v1/catalog/export"), fullAdmin(), "{\"components\":["
                + "{\"type\":\"table\",\"name\":\"" + PLAIN + "\"},{\"type\":\"table\",\"name\":\"" + SECRET + "\"},"
                + "{\"type\":\"table\",\"name\":\"" + HIDDEN + "\"}]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        final String preview = mockMvc.perform(multipart("/v1/catalog/import/preview")
                        .file(new MockMultipartFile("file", "b.json", MediaType.APPLICATION_JSON_VALUE,
                                bundle.getBytes(StandardCharsets.UTF_8)))
                        .param("resolution_policy", "SKIP_IF_EXISTS")
                        .header(HttpHeaders.AUTHORIZATION, bearer(caller("importer"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        final Map<String, JsonNode> entries = new HashMap<>();
        JsonMapper.builder().build().readTree(preview).get("tables")
                .forEach(entry -> entries.put(entry.get("name").asString(), entry));
        // The comparison is of the full objects, whatever prev shows.
        entries.values().forEach(entry -> assertThat(entry.get("differs").asBoolean()).isFalse());
        final JsonNode plain = entries.get(PLAIN).get("prev");
        assertThat(plain.isObject()).isTrue();
        assertThat(plain.path("access").isMissingNode() || plain.path("access").isNull()).isTrue();
        final List<String> secretColumns = new ArrayList<>();
        entries.get(SECRET).get("prev").get("schema").get("columns")
                .forEach(col -> secretColumns.add(col.get("name").asString()));
        assertThat(secretColumns).contains("k").doesNotContain("s");
        final JsonNode hidden = entries.get(HIDDEN).path("prev");
        assertThat(hidden.isMissingNode() || hidden.isNull()).isTrue();
    }

    @Test
    void importPrevOmitsPolicyHiddenColumn() throws Exception {
        table(COLUMNS, "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},{\"source_name\":\"a\",\"name\":\"a\",\"type\":\"string\"},"
                + "{\"source_name\":\"c\",\"name\":\"c\",\"type\":\"string\"}]");
        entry(COLUMNS, "importer", "\"privileges\":[\"get\"],\"columns\":[\"a\",\"k\"]");
        final String bundle = export(fullAdmin(), COLUMNS).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        final String preview = mockMvc.perform(multipart("/v1/catalog/import/preview")
                        .file(new MockMultipartFile("file", "b.json", MediaType.APPLICATION_JSON_VALUE,
                                bundle.getBytes(StandardCharsets.UTF_8)))
                        .param("resolution_policy", "SKIP_IF_EXISTS")
                        .header(HttpHeaders.AUTHORIZATION, bearer(caller("importer"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        final JsonNode entry = JsonMapper.builder().build().readTree(preview).get("tables").get(0);
        assertThat(entry.get("name").asString()).isEqualTo(COLUMNS);
        // differs compares the full objects, so the hidden column does not make the table differ.
        assertThat(entry.get("differs").asBoolean()).isFalse();
        final List<String> columns = new ArrayList<>();
        entry.get("prev").get("schema").get("columns").forEach(col -> columns.add(col.get("name").asString()));
        assertThat(columns).contains("a", "k").doesNotContain("c");
    }

    /** An enrichment of {@code PLAIN} carrying one sensitive column; it takes no read-side grant of its own. */
    private void labels() throws Exception {
        send(post("/v1/tables"), fullAdmin(), "{\"name\":\"" + LABELS + "\",\"type\":\"enrichment\",\"source_table\":\"" + PLAIN + "\"}")
                .andExpect(status().isCreated());
        send(post("/v1/tables/" + LABELS + "/schema"), fullAdmin(), "{\"grain_key\":\"k\",\"columns\":["
                + "{\"source_name\":\"note\",\"name\":\"note\",\"type\":\"string\",\"sensitive\":true}]}")
                .andExpect(status().isOk());
    }

    @Test
    void exportOfEverythingIncludesEnrichmentsOfGettableSourcesUnderTheirClearance() throws Exception {
        labels();
        mockMvc.perform(delete("/v1/tables/" + SECRET + "/access?role=" + EXPORTER).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isNoContent());

        // The exporter may get PLAIN, so it gets PLAIN's enrichment too, whose sensitive column needs PLAIN's clearance.
        send(post("/v1/catalog/export"), caller(EXPORTER), "{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("sensitive_column_not_entitled"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(LABELS)));

        grant(PLAIN, EXPORTER, "get", "read", "sensitive");
        send(post("/v1/catalog/export"), caller(EXPORTER), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[*].table.name").value(org.hamcrest.Matchers.hasItems(PLAIN, LABELS)));
    }

    @Test
    void importedEnrichmentOfGrantedSourceIsReadableAtOnce() throws Exception {
        labels();
        grant(PLAIN, "readers", "read");
        final String bundle = export(fullAdmin(), LABELS).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(delete("/v1/tables/" + LABELS).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isNoContent());

        mockMvc.perform(multipart("/v1/catalog/import")
                        .file(new MockMultipartFile("file", "b.json", MediaType.APPLICATION_JSON_VALUE,
                                bundle.getBytes(StandardCharsets.UTF_8)))
                        .param("resolution_policy", "SKIP_IF_EXISTS")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/tables/" + LABELS).header(HttpHeaders.AUTHORIZATION, bearer(caller("readers"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.read").value(true));
    }

    // --- access ---------------------------------------------------------------------------------------

    @Test
    void exportingAccessNeedsTableGrantBeforeTheSelection() throws Exception {
        final String withAccess = "{\"components\":[{\"type\":\"table\",\"name\":\"" + PLAIN + "\"}],\"include_access\":true}";
        for (final String path : List.of("/v1/catalog/export", "/v1/catalog/export/preview")) {
            send(post(path), caller(EXPORTER), withAccess)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("forbidden"));
        }
        send(post("/v1/catalog/export"), caller(EXPORTER),
                "{\"components\":[{\"type\":\"pipeline\",\"name\":\"does_not_exist\"}],\"include_access\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        send(post("/v1/catalog/export"), roles(EXPORTER, "granter"), withAccess)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access." + PLAIN + "." + EXPORTER + ".privileges[0]").value("get"));
        // Without the flag nothing about access changes.
        export(caller(EXPORTER), PLAIN).andExpect(status().isOk()).andExpect(jsonPath("$.access").doesNotExist());
    }

    @Test
    void importingAccessNeedsTableGrantBeforeTheCollisionCheck() throws Exception {
        final String bundle = send(post("/v1/catalog/export"), fullAdmin(),
                "{\"components\":[{\"type\":\"table\",\"name\":\"" + PLAIN + "\"}],\"include_access\":true}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        for (final String path : List.of("/v1/catalog/import", "/v1/catalog/import/preview")) {
            upload(path, bundle, caller("importer"), "FAIL_IF_EXISTS", true)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("forbidden"));
        }
        upload("/v1/catalog/import", bundle, caller("importer"), "FAIL_IF_EXISTS", false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("catalog_import_conflict"));
        // An access-only import still needs the endpoint's TABLE_CREATE.
        upload("/v1/catalog/import/preview", bundle, caller("granter"), "SKIP_IF_EXISTS", true)
                .andExpect(status().isForbidden());
        upload("/v1/catalog/import/preview", bundle, roles("importer", "granter"), "SKIP_IF_EXISTS", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[0].role").value(EXPORTER))
                .andExpect(jsonPath("$.access[0].import_action").value("SKIP"));
    }

    @Test
    void anImportedRowPolicyThatConfinesTheImporterIsReportedAtArmTime() throws Exception {
        send(post("/v1/pipelines"), fullAdmin(), "{\"name\":\"cxa-draft\",\"kind\":\"aggregate\",\"target\":\"" + HIDDEN + "\","
                + "\"inputs\":[\"" + PLAIN + "\"]}")
                .andExpect(status().isCreated());
        final String exported = send(post("/v1/catalog/export"), fullAdmin(),
                "{\"components\":[{\"type\":\"pipeline\",\"name\":\"cxa-draft\"}],\"include_access\":true}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(delete("/v1/pipelines/cxa-draft").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isNoContent());
        final ObjectNode bundle = (ObjectNode) JsonMapper.builder().build()
                .readTree(exported);
        // `registry` reads globally, so only a row policy of its own confines it.
        ((ObjectNode) bundle.get("access").get(PLAIN)).set("registry", JsonMapper.builder().build()
                .readTree("{\"privileges\":[\"read\"],\"rows\":{\"where\":\"k = 'x'\"}}"));
        final String importer = roles("registry", "importer", "granter");

        upload("/v1/catalog/import/preview", bundle.toString(), importer, "SKIP_IF_EXISTS", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[0].armable").value(false))
                .andExpect(jsonPath("$.pipelines[0].arm_problems[*]").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("a row policy confines the caller's reads of table '" + PLAIN + "'"))));
        // When another entry fails, none is applied: arming judges the target without the bundle's access.
        ((ObjectNode) bundle.get("access").get(PLAIN)).set("*", JsonMapper.builder().build().readTree("{\"privileges\":[\"write\"]}"));
        upload("/v1/catalog/import/preview", bundle.toString(), importer, "SKIP_IF_EXISTS", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access[?(@.role == '*')].import_action").value(org.hamcrest.Matchers.hasItem("FAIL")))
                .andExpect(jsonPath("$.access[*].ineffective_grants[*]").isEmpty())
                .andExpect(jsonPath("$.pipelines[0].arm_problems[*]").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("row policy")))));
        upload("/v1/catalog/import/preview", bundle.toString(), importer, "SKIP_IF_EXISTS", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelines[0].arm_problems[*]").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("row policy")))));
    }

    private ResultActions upload(String path, String bundle, String token, String policy, boolean includeAccess) throws Exception {
        return mockMvc.perform(multipart(path)
                .file(new MockMultipartFile("file", "b.json", MediaType.APPLICATION_JSON_VALUE, bundle.getBytes(StandardCharsets.UTF_8)))
                .param("resolution_policy", policy)
                .param("include_access", String.valueOf(includeAccess))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    /** A caller on the full issuer carrying {@code roles}, so holding their mappings together. */
    private static String roles(String... roles) {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", String.join("-", roles) + "-user", "roles", List.of(roles), "email", "multi@test"));
    }

    private void table(String name, String columns) throws Exception {
        send(post("/v1/tables"), fullAdmin(), "{\"name\":\"" + name + "\",\"type\":\"source\"}")
                .andExpect(status().isCreated());
        send(post("/v1/tables/" + name + "/schema"), fullAdmin(), "{\"columns\":" + columns + ",\"ordering_key\":[\"k\"]}")
                .andExpect(status().isOk());
    }

    /** Replaces {@code role}'s entry on {@code table} with {@code privileges}, at the table's current generation. */
    private void grant(String table, String role, String... privileges) throws Exception {
        final String access = mockMvc.perform(get("/v1/tables/" + table + "/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        send(put("/v1/tables/" + table + "/access?role=" + role), fullAdmin(),
                "{\"generation\":" + JsonMapper.builder().build().readTree(access).get("generation").asLong()
                        + ",\"privileges\":[\"" + String.join("\",\"", privileges) + "\"]}")
                .andExpect(status().isOk());
    }

    /** Replaces {@code role}'s whole entry on {@code table} with {@code members}, at the table's current generation. */
    private void entry(String table, String role, String members) throws Exception {
        final String access = mockMvc.perform(get("/v1/tables/" + table + "/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        send(put("/v1/tables/" + table + "/access?role=" + role), fullAdmin(),
                "{\"generation\":" + JsonMapper.builder().build().readTree(access).get("generation").asLong() + "," + members + "}")
                .andExpect(status().isOk());
    }

    private ResultActions export(String token, String table) throws Exception {
        return send(post("/v1/catalog/export"), token,
                "{\"components\":[{\"type\":\"table\",\"name\":\"" + table + "\"}]}");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String fullAdmin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }

    /** A caller on the full issuer holding only {@code role}'s mapping, plus whatever tables grant that role. */
    private static String caller(String role) {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", role + "-user", "roles", List.of(role), "email", role + "@test"));
    }
}
