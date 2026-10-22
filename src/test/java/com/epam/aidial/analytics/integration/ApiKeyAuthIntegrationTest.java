package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.epam.aidial.analytics.web.security.TestSecurityConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end API-key authentication over the full Spring Boot application in {@code oidc} mode with
 * {@code security.api-key.enabled=true}. Keys are validated against an in-JVM stub of DIAL Core's
 * {@code /v1/user/info}, so no live Core is needed; the stub also counts calls per key to prove
 * caching. A single JWT provider is configured (verified via {@link TestSecurityConfig}) so the
 * Bearer path — which takes precedence over the {@code Api-Key} header — can be exercised too.
 *
 * <p>Requires a running Docker daemon (PostgreSQL + ClickHouse Testcontainers): the positive project-key
 * mutation issues a real {@code CREATE TABLE}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "poll.enabled=false",
        "security.mode=oidc",
        "security.defaults.email-claim=email",
        "security.defaults.principal-claim=sub",
        "security.defaults.roles-mapping={\"admin\":[\"FULL_ADMIN\"]}",
        "providers.full.issuer=" + ApiKeyAuthIntegrationTest.FULL_ISSUER,
        "providers.full.jwk-set-uri=https://issuer.test/full/jwks",
        "providers.full.audiences=" + ApiKeyAuthIntegrationTest.AUDIENCE,
        "providers.full.role-claims=roles",
        "providers.full.roles-mapping={\"admin\":[\"FULL_ADMIN\"]}",
        "security.api-key.enabled=true",
        "security.api-key.startup-probe=false",
        "security.api-key.roles-mapping={\"admin\":[\"FULL_ADMIN\"],\"viewer\":[\"READ_ONLY_ADMIN\"]}",
})
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class ApiKeyAuthIntegrationTest {

    static final String AUDIENCE = "adas-test";
    static final String FULL_ISSUER = "https://issuer.test/full";

    private static final String BASE = "/v1/tables";
    private static final String PROBE_TABLE = "api_key_auth_probe";
    private static final String ADMIN_KEY = "admin-key";
    private static final String VIEWER_KEY = "viewer-key";
    private static final String JWT_ROOTED_KEY = "jwt-rooted-key";
    private static final String CACHE_PROBE_KEY = "cache-probe-key";

    private static HttpServer coreServer;
    private static final Map<String, AtomicInteger> coreCallsByKey = new ConcurrentHashMap<>();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        ClickHouseContainerSupport.registerProperties(registry);
        int port = startCoreServer();
        registry.add("security.api-key.core-url", () -> "http://localhost:" + port);
    }

    /**
     * Tiny stub of DIAL Core {@code /v1/user/info}: maps known keys to project-key or JWT-rooted
     * responses and rejects everything else with 401, while counting calls per key so the caching
     * test can assert Core was hit once. Deliberately labels the JSON body {@code application/octet-stream}
     * to mirror the real Core content type.
     */
    private static int startCoreServer() {
        try {
            coreServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start Core stub server", e);
        }
        coreServer.createContext("/v1/user/info", exchange -> {
            String apiKey = exchange.getRequestHeaders().getFirst("Api-Key");
            coreCallsByKey.computeIfAbsent(apiKey == null ? "" : apiKey, k -> new AtomicInteger()).incrementAndGet();
            String body = switch (apiKey == null ? "" : apiKey) {
                case ADMIN_KEY, CACHE_PROBE_KEY -> "{\"project\":\"acme\",\"roles\":[\"admin\"]}";
                case VIEWER_KEY -> "{\"project\":\"acme\",\"roles\":[\"viewer\"]}";
                case JWT_ROOTED_KEY -> "{\"userClaims\":{\"sub\":\"u1\",\"email\":\"u1@test\"},\"roles\":[\"admin\"]}";
                default -> null;
            };
            if (body == null) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_OCTET_STREAM_VALUE);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(bytes);
            }
        });
        coreServer.start();
        return coreServer.getAddress().getPort();
    }

    @AfterAll
    static void stopCoreServer() {
        if (coreServer != null) {
            coreServer.stop(0);
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        // Best-effort removal of the probe table the admin test creates; delete is FULL_ADMIN-gated,
        // so it carries the admin project-key.
        mockMvc.perform(delete(BASE + "/" + PROBE_TABLE).header("Api-Key", ADMIN_KEY));
    }

    private static String fullAdminJwt() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }

    private static String createBody() {
        // Create is identity-only now; a columns/keys body would be rejected (422) before the auth test.
        return "{\"name\":\"" + PROBE_TABLE + "\",\"description\":\"api-key probe\",\"type\":\"source\"}";
    }

    @Test
    void projectKeyAdminCanReadAndMutate() throws Exception {
        mockMvc.perform(get(BASE).header("Api-Key", ADMIN_KEY))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE)
                        .header("Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(PROBE_TABLE));
    }

    @Test
    void projectKeyViewerCanReadButNotMutate() throws Exception {
        mockMvc.perform(get(BASE).header("Api-Key", VIEWER_KEY))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE)
                        .header("Api-Key", VIEWER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void jwtRootedKeyResolvesViaDefaultsMapping() throws Exception {
        mockMvc.perform(get(BASE).header("Api-Key", JWT_ROOTED_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void invalidApiKeyReturns401() throws Exception {
        mockMvc.perform(get(BASE).header("Api-Key", "bogus-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void authorizationHeaderTakesPrecedenceOverApiKey() throws Exception {
        // A valid full-admin JWT plus a garbage Api-Key still authenticates via the JWT.
        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fullAdminJwt())
                        .header("Api-Key", "bogus-key"))
                .andExpect(status().isOk());
    }

    @Test
    void repeatedValidKeyHitsCoreOnce() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get(BASE).header("Api-Key", CACHE_PROBE_KEY))
                    .andExpect(status().isOk());
        }

        assertThat(coreCallsByKey.get(CACHE_PROBE_KEY).get()).isEqualTo(1);
    }

    @Test
    void basicCredentialsAreNotAnHttpCredentialForm() throws Exception {
        // The Flight endpoint accepts an API key as `Basic api-key:<key>`; HTTP deliberately does not. This
        // is the boundary that keeps that credential form off this surface, and it is worth pinning: the
        // API-key filter defers to the bearer path on ANY non-blank Authorization header, so the header
        // both fails to authenticate on its own and suppresses a valid Api-Key header presented with it.
        final String basic = "Basic " + Base64.getEncoder()
                .encodeToString(("api-key:" + ADMIN_KEY).getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, basic))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));

        mockMvc.perform(get(BASE)
                        .header(HttpHeaders.AUTHORIZATION, basic)
                        .header("Api-Key", ADMIN_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void publicActuatorInfoNeedsNoKey() throws Exception {
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }
}
