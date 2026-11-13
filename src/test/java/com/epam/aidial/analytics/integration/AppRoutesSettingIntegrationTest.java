package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /v1/settings/app-routes} in {@code none} security mode: storage, order, whole-document replace and body
 * validation (capability {@code instance-settings}). The guard against dropping a pair an enabled pipeline calls is
 * covered in {@link AppRoutePipelineIntegrationTest}, and authorization in
 * {@link SessionsSourceSettingAuthIntegrationTest}.
 */
class AppRoutesSettingIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String URL = "/v1/settings/app-routes";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void ownTheSetting() {
        jdbc.update("DELETE FROM instance_setting");
    }

    private static String routes(String... pairs) {
        final StringBuilder body = new StringBuilder("{\"routes\":[");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"deployment\":\"").append(pairs[i]).append("\",\"route\":\"").append(pairs[i + 1])
                    .append("\"}");
        }
        return body.append("]}").toString();
    }

    @Test
    void absentBeforeTheFirstWrite() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("setting_not_found"));
    }

    @Test
    void storedAllowlistReadsBackInOrder() throws Exception {
        mockMvc.perform(put(URL).contentType(JSON)
                        .content(routes("insights-app", "insights", "triage-app", "v1/classify")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated_at").exists());
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes", hasSize(2)))
                .andExpect(jsonPath("$.routes[0].deployment").value("insights-app"))
                .andExpect(jsonPath("$.routes[1].route").value("v1/classify"));
    }

    @Test
    void replaceOverwritesThePreviousAllowlist() throws Exception {
        mockMvc.perform(put(URL).contentType(JSON).content(routes("insights-app", "insights"))).andExpect(status().isOk());
        mockMvc.perform(put(URL).contentType(JSON).content(routes("triage-app", "classify"))).andExpect(status().isOk());
        mockMvc.perform(get(URL))
                .andExpect(jsonPath("$.routes", hasSize(1)))
                .andExpect(jsonPath("$.routes[0].deployment").value("triage-app"));
    }

    @Test
    void anEmptyListIsStored() throws Exception {
        mockMvc.perform(put(URL).contentType(JSON).content("{\"routes\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes", hasSize(0)));
    }

    @Test
    void malformedBodiesAreRefusedNamingTheEntry() throws Exception {
        expectInvalid("{}", "routes");
        expectInvalid(routes("insights-app", "../admin"), "routes[0].route");
        expectInvalid(routes("insights-app", "insights", "insights-app", "insights"), "routes[1]");
        expectInvalid("{\"routes\":[{\"route\":\"insights\"}]}", "routes[0].deployment");
        expectInvalid(routes("insights app", "insights"), "routes[0].deployment");
        expectInvalid(routes("insights-app?x=1", "insights"), "routes[0].deployment");
        mockMvc.perform(get(URL)).andExpect(status().isNotFound());
    }

    @Test
    void deploymentIdMayCarrySlash() throws Exception {
        mockMvc.perform(put(URL).contentType(JSON).content(routes("team/insights-app", "insights")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].deployment").value("team/insights-app"));
    }

    @Test
    void pairsDifferingOnlyByCaseAreDistinct() throws Exception {
        mockMvc.perform(put(URL).contentType(JSON).content(routes("insights-app", "insights", "insights-app", "Insights")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes", hasSize(2)));
    }

    @Test
    void serverStampedMembersAreRefused() throws Exception {
        mockMvc.perform(put(URL).contentType(JSON).content("{\"routes\":[],\"updated_by\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSessionsSourceSettingIsUnaffected() throws Exception {
        mockMvc.perform(put("/v1/settings/sessions-source").contentType(JSON).content(
                        "{\"base_table\":\"dial_usage_log\",\"roles\":{\"session_id\":\"project_id\",\"time\":\"request_time\"}}"))
                .andExpect(status().isOk());
        mockMvc.perform(put(URL).contentType(JSON).content(routes("insights-app", "insights"))).andExpect(status().isOk());
        mockMvc.perform(get("/v1/settings/sessions-source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base_table").value("dial_usage_log"));
    }

    @Test
    void anUnknownSettingNameIsNotFound() throws Exception {
        mockMvc.perform(get("/v1/settings/model-allowlist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    private void expectInvalid(String body, String naming) throws Exception {
        mockMvc.perform(put(URL).contentType(JSON).content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(containsString(naming)));
    }
}
