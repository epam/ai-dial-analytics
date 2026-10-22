package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Kubernetes probe groups, called without a credential in {@code oidc} mode: both are served outside a
 * cluster too, liveness reports the application state alone (a one-member group renders as a bare
 * {@code status}), and readiness adds the PostgreSQL catalog.
 *
 * <p>Requires Docker.
 */
class HealthProbesIntegrationTest extends AbstractOidcAuthIntegrationTest {

    @Test
    void livenessIsPublicAndCarriesNoDependency() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void readinessIsPublicAndIncludesTheCatalogDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }
}
