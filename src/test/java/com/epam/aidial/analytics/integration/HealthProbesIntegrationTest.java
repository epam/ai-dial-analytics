package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The actuator in its default layout, on the API port, called over a real socket without a credential in
 * {@code oidc} mode: the probe groups, build info and the Prometheus scrape answer there, liveness reports the
 * application state alone (a one-member group renders as a bare {@code status}), and readiness adds the
 * PostgreSQL catalog. {@link ManagementPortIntegrationTest} covers the opt-in separate port.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "clickhouse.migrations.enabled=false",
        // No ClickHouse here: the startup engine check finds nothing to reach and the service starts unverified.
        "clickhouse.port=1",
        "sync.validate-schema=false",
        "poll.enabled=false",
        "security.mode=oidc",
        "providers.full.issuer=https://issuer.test/full",
        "providers.full.jwk-set-uri=https://issuer.test/full/jwks",
})
@Import(TestSecurityConfig.class)
class HealthProbesIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    @Test
    void actuatorSharesTheApiPortByDefault() {
        assertThat(environment.getRequiredProperty("local.management.port", Integer.class)).isEqualTo(serverPort());
    }

    @Test
    void livenessIsPublicAndCarriesNoDependency() throws Exception {
        HttpResponse<String> response = get(serverPort(), "/actuator/health/liveness");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.has("components")).isFalse();
    }

    @Test
    void readinessIsPublicAndIncludesTheCatalogDatabase() throws Exception {
        HttpResponse<String> response = get(serverPort(), "/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.path("components").path("readinessState").path("status").asText()).isEqualTo("UP");
        assertThat(body.path("components").path("db").path("status").asText()).isEqualTo("UP");
    }

    @Test
    void infoAndPrometheusArePublicOnTheApiPort() throws Exception {
        assertThat(get(serverPort(), "/actuator/info").statusCode()).isEqualTo(200);

        HttpResponse<String> prometheus = get(serverPort(), "/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("jvm_");
    }

    private int serverPort() {
        return environment.getRequiredProperty("local.server.port", Integer.class);
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
