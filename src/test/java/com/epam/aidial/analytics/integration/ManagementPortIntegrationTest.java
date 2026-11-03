package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.TestSecurityConfig;
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
 * The opt-in separate management port ({@code management.server.port} set, here to a random port), called over
 * real sockets without a credential in {@code oidc} mode: the probe groups and the Prometheus scrape move to the
 * management port, the API port serves no actuator path, and the management port serves no API path.
 * {@link HealthProbesIntegrationTest} covers the probe bodies in the default layout.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "clickhouse.migrations.enabled=false",
        "sync.validate-schema=false",
        "poll.enabled=false",
        "security.mode=oidc",
        "providers.full.issuer=https://issuer.test/full",
        "providers.full.jwk-set-uri=https://issuer.test/full/jwks",
})
@Import(TestSecurityConfig.class)
class ManagementPortIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
    }

    @Test
    void managementPortIsSeparateFromTheApiPort() {
        assertThat(managementPort()).isNotEqualTo(serverPort());
    }

    @Test
    void probesArePublicOnTheManagementPort() throws Exception {
        assertThat(get(managementPort(), "/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get(managementPort(), "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void infoAndPrometheusArePublicOnTheManagementPort() throws Exception {
        assertThat(get(managementPort(), "/actuator/info").statusCode()).isEqualTo(200);

        HttpResponse<String> prometheus = get(managementPort(), "/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("jvm_");
    }

    @Test
    void apiPortServesNoActuatorPath() throws Exception {
        assertThat(get(serverPort(), "/actuator/health/liveness").statusCode()).isEqualTo(404);
        assertThat(get(serverPort(), "/actuator/prometheus").statusCode()).isEqualTo(404);
    }

    @Test
    void managementPortServesNoApiPath() throws Exception {
        assertThat(get(managementPort(), "/v1/tables").statusCode()).isNotEqualTo(200);
    }

    private int serverPort() {
        return environment.getRequiredProperty("local.server.port", Integer.class);
    }

    private int managementPort() {
        return environment.getRequiredProperty("local.management.port", Integer.class);
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
