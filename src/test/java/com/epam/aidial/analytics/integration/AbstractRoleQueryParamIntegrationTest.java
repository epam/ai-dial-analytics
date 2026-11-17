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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role addressed as an ordinary URL-encoded {@code role} query value, over a real socket, so Tomcat, the request
 * firewall and Spring MVC all run (see {@code openspec/specs/table-access-control/spec.md}, "Role-keyed access API"):
 * a role carrying {@code /}, {@code %} or {@code ;} round-trips, a missing role is {@code 400}, and an encoded
 * {@code /} in a path segment is refused {@code 400} as the container and firewall defaults refuse it (see
 * {@code openspec/specs/authentication/spec.md}, "Encoded path separators are refused"). Subclasses run it in
 * each security mode. The table is created {@code PENDING}, so only PostgreSQL is needed.
 *
 * <p>Requires Docker.
 */
// Each subclass's context (a real Tomcat, its own properties) is never reused, so it is closed rather than held in the
// test context cache, where it would only add to the suite's heap pressure.
@DirtiesContext
abstract class AbstractRoleQueryParamIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Autowired
    private TableDefinitionRepository definitions;

    private final String table = "role_param_" + getClass().getSimpleName().toLowerCase().replace("integrationtest", "");

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
    void rolesWithSlashPercentOrSemicolonRoundTrip() throws Exception {
        final List<String> roles = List.of("/analytics/viewers", "team%ops", "a;b");
        for (final String role : roles) {
            final HttpResponse<String> put = send("PUT", entryPath(role), entry(generation()));
            assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
            assertThat(json(put).path("role").asText()).isEqualTo(role);
        }
        final JsonNode listed = json(send("GET", accessPath(), null)).path("roles");
        for (final String role : roles) {
            assertThat(listed.has(role)).as(role).isTrue();
            assertThat(json(send("GET", entryPath(role), null)).path("role").asText()).isEqualTo(role);
            assertThat(send("DELETE", entryPath(role), null).statusCode()).as(role).isEqualTo(204);
        }
        assertThat(json(send("GET", accessPath(), null)).path("roles").size()).isZero();
    }

    @Test
    void missingRoleIsBadRequestAndNothingIsStored() throws Exception {
        final HttpResponse<String> put = send("PUT", accessPath(), entry(generation()));
        assertThat(put.statusCode()).isEqualTo(400);
        assertThat(json(put).path("error").asText()).isEqualTo("bad_request");
        final HttpResponse<String> delete = send("DELETE", accessPath(), null);
        assertThat(delete.statusCode()).isEqualTo(400);
        assertThat(json(delete).path("error").asText()).isEqualTo("bad_request");
        assertThat(send("PUT", accessPath() + "?role=%20", entry(generation())).statusCode()).as("a blank role").isEqualTo(400);
        assertThat(json(send("GET", accessPath(), null)).path("roles").size()).isZero();
        // The role is never a path segment.
        assertThat(send("GET", accessPath() + "/x", null).statusCode()).isEqualTo(404);
    }

    @Test
    void encodedSlashInPathSegmentIsRefused() throws Exception {
        assertThat(send("GET", "/v1/tables/a%2Fb", null).statusCode()).isEqualTo(400);
        assertThat(send("GET", "/v1/tables/" + table + "/access%2F", null).statusCode()).isEqualTo(400);
        assertThat(send("GET", "/v1/tables/a%25b", null).statusCode()).as("an encoded percent sign").isEqualTo(400);
        assertThat(send("GET", "/actuator/health/%2F..%2Fenv", null).statusCode()).isEqualTo(400);
    }

    @Test
    void firewallRefusalIsAnErrorView() throws Exception {
        final HttpResponse<String> refused = send("GET", "/v1/tables/a%3Bb", null);
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused).path("error").asText()).isEqualTo("bad_request");
        assertThat(json(refused).path("status").asInt()).isEqualTo(400);
    }

    String accessPath() {
        return "/v1/tables/" + table + "/access";
    }

    private String entryPath(String role) {
        return accessPath() + "?role=" + URLEncoder.encode(role, StandardCharsets.UTF_8);
    }

    private long generation() throws Exception {
        return json(send("GET", accessPath(), null)).path("generation").asLong();
    }

    static String entry(long generation) {
        return "{\"generation\":" + generation + ",\"privileges\":[\"read\"]}";
    }

    private static JsonNode json(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String body) throws IOException, InterruptedException {
        return send(method, path, body, adminAuthorization());
    }

    /** Sends as the caller {@code authorization} names, or anonymously when it is null. */
    HttpResponse<String> send(String method, String path, String body, String authorization) throws IOException, InterruptedException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int port() {
        return environment.getRequiredProperty("local.server.port", Integer.class);
    }
}
