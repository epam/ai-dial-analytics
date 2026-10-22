package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.epam.aidial.analytics.web.security.TestSecurityConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security behavior of the table-management API ({@code /v1/tables}) in {@code oidc} mode, exercised
 * over the full Spring Boot application. Every endpoint names the privilege it requires; the URL layer
 * only authenticates, so a filtered collection answers an empty {@code 200} rather than {@code 403}. The
 * three providers it asserts over —
 * two JWT providers (one granting {@code FULL_ADMIN}, one {@code READ_ONLY_ADMIN}) whose tokens are
 * minted locally and verified against an HMAC secret via {@link TestSecurityConfig}, and one opaque
 * provider introspected against the in-JVM userinfo stub of {@link OpaqueUserInfoServerSupport} — are
 * configured by {@link AbstractOidcAuthIntegrationTest}, so no live JWKS or IdP is needed.
 *
 * <p>Requires a running Docker daemon (PostgreSQL + ClickHouse Testcontainers): the positive mutation
 * path issues a real {@code CREATE TABLE} through the writer credential.
 */
class TableAuthIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/tables";
    private static final String PROBE_TABLE = "auth_probe";

    @AfterEach
    void cleanUp() throws Exception {
        // Best-effort removal of the probe table the full-admin test creates so it never leaks into the
        // shared catalog; delete is itself FULL_ADMIN-gated, so it carries the admin token.
        mockMvc.perform(delete(BASE + "/" + PROBE_TABLE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())));
    }

    private static String fullAdminToken() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }

    private static String readOnlyToken() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "readonly-user", "roles", List.of("viewer"), "email", "readonly@test"));
    }

    /** A valid identity-only {@link com.epam.aidial.analytics.web.dto.table.CreateTableRequest} body (create
     * is identity-only now), so a caller without the endpoint's privilege fails at that check (403) rather
     * than at
     * bean validation (400), and an admin create succeeds (201, PENDING). */
    private static String createBody() {
        return "{\"name\":\"" + PROBE_TABLE + "\",\"description\":\"auth probe\",\"type\":\"source\"}";
    }

    @Test
    void noTokenReturns401ErrorView() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.method").value("GET"));
    }

    @Test
    void malformedTokenReturns401() throws Exception {
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer("not.a.jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void wrongAudienceReturns401() throws Exception {
        String token = JwtTestTokens.token(FULL_ISSUER, "someone-else",
                Map.of("sub", "full-user", "roles", List.of("admin")));
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void unknownIssuerReturns401() throws Exception {
        String token = JwtTestTokens.token("https://issuer.test/rogue", AUDIENCE,
                Map.of("sub", "x", "roles", List.of("admin")));
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        String token = JwtTestTokens.expiredToken(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin")));
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void readOnlyCanReadButCannotMutate() throws Exception {
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken())))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, bearer(readOnlyToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void fullAdminCanReadAndMutate() throws Exception {
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken())))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(PROBE_TABLE));
    }

    @Test
    void opaqueTokenIsIntrospectedAndCanRead() throws Exception {
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(OpaqueUserInfoServerSupport.OPAQUE_READONLY_TOKEN)))
                .andExpect(status().isOk());
    }

    @Test
    void opaqueReadOnlyTokenCannotMutate() throws Exception {
        mockMvc.perform(post(BASE)
                        .header(HttpHeaders.AUTHORIZATION, bearer(OpaqueUserInfoServerSupport.OPAQUE_READONLY_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void invalidOpaqueTokenReturns401() throws Exception {
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer("bogus-opaque-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void publicActuatorInfoNeedsNoToken() throws Exception {
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void validTokenWithNoMappedPrivilegeIsAuthenticatedAndSeesNothing() throws Exception {
        // A valid token whose only role maps to no privilege is authenticated (carrying the raw provider
        // role) and may still be authorized for one table by a matching grant. Authorization is then per
        // endpoint: a filtered listing answers 200 with nothing in it, and a write is 403.
        String token = JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "nobody", "roles", List.of("unmapped-role"), "email", "nobody@test"));

        // The catalog listing is filtered, not refused.
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables").isEmpty());

        // Per-table write endpoint (relaxed to authenticated + method security): no table access → 403.
        mockMvc.perform(post(BASE + "/" + PROBE_TABLE + "/rows")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"k\":\"v\"}]}"))
                .andExpect(status().isForbidden());
    }
}
