package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.epam.aidial.analytics.web.security.TestSecurityConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization of {@code /v1/settings/sessions-source} in {@code oidc} mode: every bundle reads, only
 * {@code FULL_ADMIN} writes, a raw-privilege mapping without {@code SETTINGS_READ} reads nothing, and validation
 * sees the catalog as the writer does. Privileges come from {@code roles-mapping}s, so the bundle contents and the
 * {@code SETTINGS_WRITE -> SETTINGS_READ} implication under test are the parser's.
 *
 * <p>Only PostgreSQL is required: validation reads the catalog, never ClickHouse.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "clickhouse.migrations.enabled=false",
        "sync.validate-schema=false",
        "poll.enabled=false",
        "security.mode=oidc",
        "security.defaults.email-claim=email",
        "security.defaults.principal-claim=sub",
        "providers.test.issuer=" + SessionsSourceSettingAuthIntegrationTest.ISSUER,
        "providers.test.jwk-set-uri=https://issuer.test/settings/jwks",
        "providers.test.audiences=" + SessionsSourceSettingAuthIntegrationTest.AUDIENCE,
        "providers.test.role-claims=roles",
        "providers.test.roles-mapping={\"admin\":[\"FULL_ADMIN\"],\"viewer\":[\"READ_ONLY_ADMIN\"],"
                + "\"analyst\":[\"READ_ONLY_ANALYTIC\"],\"sensitive\":[\"READ_ONLY_SENSITIVE_ANALYTIC\"],"
                + "\"tableonly\":[\"TABLE_READ\"],\"settingswriter\":[\"SETTINGS_WRITE\",\"TABLE_READ\"]}",
})
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class SessionsSourceSettingAuthIntegrationTest {

    static final String AUDIENCE = "adas-test";
    static final String ISSUER = "https://issuer.test/settings";

    private static final String URL = "/v1/settings/sessions-source";

    private static final String VALID = """
            {"base_table":"dial_usage_log","roles":{"session_id":"project_id","time":"request_time"}}""";

    /** Binds a role to a sensitive enrichment column, which only a cleared writer can see. */
    private static final String SENSITIVE = """
            {"base_table":"dial_usage_log","roles":{"session_id":"project_id","time":"request_time",
             "title":"dial_usage_log_payload.request_body"}}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    @BeforeEach
    @AfterEach
    void ownTheSetting() {
        jdbc.update("DELETE FROM instance_setting");
    }

    @Test
    void everyBundleReads() throws Exception {
        mockMvc.perform(authored(put(URL), token("admin")).content(VALID)).andExpect(status().isOk());
        for (final String role : List.of("admin", "viewer", "analyst", "sensitive")) {
            mockMvc.perform(authored(get(URL), token(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.base_table").value("dial_usage_log"));
        }
    }

    @Test
    void onlyTheFullAdministratorWrites() throws Exception {
        for (final String role : List.of("viewer", "analyst", "sensitive", "tableonly")) {
            mockMvc.perform(authored(put(URL), token(role)).content(VALID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("forbidden"));
        }
        mockMvc.perform(authored(get(URL), token("admin"))).andExpect(status().isNotFound());

        mockMvc.perform(authored(put(URL), token("admin")).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated_by").value("admin@test"));
    }

    @Test
    void rawMappingWithoutTheSettingsReadIsForbidden() throws Exception {
        mockMvc.perform(authored(get(URL), token("tableonly")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void theSettingsWriteImpliesTheRead() throws Exception {
        mockMvc.perform(authored(put(URL), token("settingswriter")).content(VALID)).andExpect(status().isOk());
        mockMvc.perform(authored(get(URL), token("settingswriter")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated_by").value("settingswriter@test"));
    }

    @Test
    void fieldHiddenFromTheWriterIsReportedAsUnknown() throws Exception {
        final String hidden = mockMvc.perform(authored(put(URL), token("settingswriter")).content(SENSITIVE))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andReturn().getResponse().getContentAsString();
        final String absent = mockMvc.perform(authored(put(URL), token("settingswriter"))
                        .content(SENSITIVE.replace("dial_usage_log_payload.request_body", "dial_usage_log_payload.no_such")))
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString();
        assertThat(hidden.replace("request_body", "no_such")).isEqualTo(absent);

        // The full administrator holds the clearance, so the same binding validates.
        mockMvc.perform(authored(put(URL), token("admin")).content(SENSITIVE)).andExpect(status().isOk());
    }

    @Test
    void settingActivitiesAreInTheFeedForAuditReaders() throws Exception {
        mockMvc.perform(authored(put(URL), token("admin")).content(VALID)).andExpect(status().isOk());
        mockMvc.perform(authored(post("/v1/activities"), token("viewer"))
                        .content("{\"filters\":[{\"column\":\"resourceType\",\"operator\":\"eq\",\"value\":\"Setting\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].resourceType").value("Setting"))
                .andExpect(jsonPath("$.data[0].resourceId").value("sessions-source"))
                .andExpect(jsonPath("$.data[0].initiatedEmail").value("admin@test"));
    }

    private static MockHttpServletRequestBuilder authored(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType("application/json");
    }

    private static String token(String role) {
        return JwtTestTokens.token(ISSUER, AUDIENCE, Map.of("sub", role, "roles", List.of(role), "email", role + "@test"));
    }
}
