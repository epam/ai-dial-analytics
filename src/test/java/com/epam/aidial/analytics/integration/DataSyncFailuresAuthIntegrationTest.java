package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization of the data-sync failures surface in {@code oidc} mode: listing is a read ({@code REGISTRY_READ}),
 * requeueing needs {@code DATA_SYNC_OPERATE}.
 *
 * <p>Requires a running Docker daemon (the shared PostgreSQL + ClickHouse Testcontainers context).
 */
class DataSyncFailuresAuthIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/data-sync/failures";

    private static String fullAdminToken() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }

    private static String readOnlyToken() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "readonly-user", "roles", List.of("viewer"), "email", "readonly@test"));
    }

    @Test
    void noTokenCannotList() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void readOnlyCanListButCannotRequeue() throws Exception {
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failures").isArray());

        mockMvc.perform(post(BASE + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjects\":[\"logs/none.log.gz\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void fullAdminCanRequeue() throws Exception {
        // A subject that matches nothing, so the shared context's failure ledger is left as it was.
        mockMvc.perform(post(BASE + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjects\":[\"logs/none.log.gz\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued").value(0));
    }
}
