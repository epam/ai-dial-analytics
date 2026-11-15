package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A role carried as one percent-encoded path segment, over a real socket, so Tomcat, the request firewall and Spring
 * MVC's path matching all run (see {@code openspec/specs/authentication/spec.md}, "Encoded path separators and firewall
 * refusals"): {@code %2F} and {@code %25} reach the handler decoded, the firewall's other refusals are an
 * {@code ErrorView} {@code 400 bad_request}, and no encoded character acts as a path separator. Subclasses run it in
 * each security mode. The table is created {@code PENDING}, so only PostgreSQL is needed.
 *
 * <p>Requires Docker.
 */
// Each subclass's context (a real Tomcat, its own properties) is never reused, so it is closed rather than held in the
// test context cache, where it would only add to the suite's heap pressure.
@DirtiesContext
abstract class AbstractEncodedPathIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Autowired
    private TableDefinitionRepository definitions;

    private final String table = "encoded_path_" + getClass().getSimpleName().toLowerCase().replace("integrationtest", "");

    /** The {@code Authorization} header value of a caller holding every privilege, or null in {@code none} mode. */
    abstract String adminAuthorization();

    @BeforeEach
    void createTable() throws Exception {
        definitions.deleteById(table);
        assertThat(send("POST", "/v1/tables", "{\"name\":\"" + table + "\",\"type\":\"source\"}").statusCode()).isEqualTo(201);
    }

    @AfterEach
    void removeTable() {
        definitions.deleteById(table);
    }

    @Test
    void rolesWithSlashOrPercentRoundTripUnderTheirDecodedNames() throws Exception {
        final String access = "/v1/tables/" + table + "/access/";

        HttpResponse<String> put = send("PUT", access + "%2Fanalytics%2Fviewers", entry(generation()));
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        assertThat(json(put).path("role").asText()).isEqualTo("/analytics/viewers");
        put = send("PUT", access + "team%25ops", entry(generation()));
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
        assertThat(json(put).path("role").asText()).isEqualTo("team%ops");

        final JsonNode roles = json(send("GET", "/v1/tables/" + table + "/access", null)).path("roles");
        assertThat(roles.has("/analytics/viewers")).isTrue();
        assertThat(roles.has("team%ops")).isTrue();
        assertThat(json(send("GET", access + "%2Fanalytics%2Fviewers", null)).path("role").asText()).isEqualTo("/analytics/viewers");
        assertThat(json(send("GET", access + "%2fanalytics%2fviewers", null)).path("role").asText())
                .as("a lowercase encoded slash names the same entry").isEqualTo("/analytics/viewers");
        assertThat(json(send("GET", access + "team%25ops", null)).path("role").asText()).isEqualTo("team%ops");

        assertThat(send("DELETE", access + "%2Fanalytics%2Fviewers", null).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", access + "team%25ops", null).statusCode()).isEqualTo(204);
        assertThat(json(send("GET", "/v1/tables/" + table + "/access", null)).path("roles").size()).isZero();
    }

    @Test
    void roleTheFirewallRefusesIsErrorView400AndNothingIsStored() throws Exception {
        final HttpResponse<String> refused = send("PUT", "/v1/tables/" + table + "/access/a%3Bb", entry(generation()));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused).path("error").asText()).isEqualTo("bad_request");
        assertThat(json(refused).path("status").asInt()).isEqualTo(400);
        assertThat(send("PUT", "/v1/tables/" + table + "/access/a%00b", entry(generation())).statusCode())
                .as("an encoded NUL is refused, by the container or the firewall").isEqualTo(400);
        assertThat(json(send("GET", "/v1/tables/" + table + "/access", null)).path("roles").size()).isZero();
    }

    @Test
    void anEncodedSlashInAnyPathVariableIsAnsweredAsItsDecodedValue() throws Exception {
        assertThat(send("GET", "/v1/tables/a%2Fb", null).statusCode()).isEqualTo(404);
        for (final String family : new String[] {"/v1/pipelines/", "/v1/saved-queries/", "/v1/dashboards/"}) {
            final int encoded = send("GET", family + "a%2Fb", null).statusCode();
            assertThat(encoded).as(family).isEqualTo(send("GET", family + "nope", null).statusCode());
            assertThat(encoded).as(family).isBetween(400, 499);
        }
    }

    @Test
    void anEncodedTraversalStaysUnderItsPublicPath() throws Exception {
        final HttpResponse<String> probe = sendAnonymous("/actuator/health/%2F..%2Fenv");
        assertThat(probe.statusCode()).isEqualTo(404).isEqualTo(sendAnonymous("/actuator/health/no-such-component").statusCode());
        assertThat(probe.body()).doesNotContain("activeProfiles").doesNotContain("propertySources");

        final HttpResponse<String> swagger = sendAnonymous("/swagger-ui/%2F..%2F..%2Fapplication.yml");
        assertThat(swagger.statusCode()).isBetween(400, 499);
        assertThat(swagger.body()).doesNotContain("security:");
    }

    private long generation() throws Exception {
        return json(send("GET", "/v1/tables/" + table + "/access", null)).path("generation").asLong();
    }

    private static String entry(long generation) {
        return "{\"generation\":" + generation + ",\"privileges\":[\"read\"]}";
    }

    private static JsonNode json(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String body) throws IOException, InterruptedException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (adminAuthorization() != null) {
            request.header("Authorization", adminAuthorization());
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendAnonymous(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int port() {
        return environment.getRequiredProperty("local.server.port", Integer.class);
    }
}
