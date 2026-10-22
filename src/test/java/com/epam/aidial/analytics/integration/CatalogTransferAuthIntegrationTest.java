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
 * Catalog transfer authorization in {@code oidc} mode: per-table visibility and sensitive clearance on export,
 * grants never exported, and the gates import repeats because it calls the create services
 * in-process.
 */
class CatalogTransferAuthIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String PLAIN = "cxa_plain";
    private static final String SECRET = "cxa_secret";
    private static final String HIDDEN = "cxa_hidden";
    private static final String EXPORTER = "exporter";

    @BeforeEach
    void createTables() throws Exception {
        dropTables();
        table(PLAIN, "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"}]");
        table(SECRET, "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},"
                + "{\"source_name\":\"s\",\"name\":\"s\",\"type\":\"string\",\"sensitive\":true}]");
        table(HIDDEN, "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"}]");
        for (final String table : List.of(PLAIN, SECRET)) {
            send(put("/v1/tables/" + table + "/access"), fullAdmin(), "{\"get\":[\"" + EXPORTER + "\"]}")
                    .andExpect(status().isOk());
        }
    }

    @AfterEach
    void dropTables() throws Exception {
        for (final String table : List.of(PLAIN, SECRET, HIDDEN)) {
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
            send(put("/v1/tables/" + table + "/access"), fullAdmin(), "{\"get\":[\"importer\"]}")
                    .andExpect(status().isOk());
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

    private void table(String name, String columns) throws Exception {
        send(post("/v1/tables"), fullAdmin(), "{\"name\":\"" + name + "\",\"type\":\"source\"}")
                .andExpect(status().isCreated());
        send(post("/v1/tables/" + name + "/schema"), fullAdmin(), "{\"columns\":" + columns + ",\"ordering_key\":[\"k\"]}")
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
