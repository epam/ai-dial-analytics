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
 * Authorization of the data-sync backfill surface in {@code oidc} mode: listing runs is a read, open to a
 * read-only admin by the {@code /v1/**} rule, while triggering stays {@code @FullAdminOnly}.
 *
 * <p>No full-admin trigger is exercised, deliberately: the context is shared with the other auth tests and
 * backfill is enabled in it, so a trigger would start a real run in the singleton service and leak its in-flight
 * slot and registry entry into every test after it.
 *
 * <p>Requires a running Docker daemon (the shared PostgreSQL + ClickHouse Testcontainers context).
 */
class BackfillAuthIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/data-sync/backfill";

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
    void readOnlyCanListButCannotTrigger() throws Exception {
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs").isArray());

        // A strictly valid past one-day range, so the refusal comes from authorization, not from reading the body.
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-06-01\",\"to\":\"2026-06-01\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void fullAdminCanList() throws Exception {
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs").isArray());
    }
}
