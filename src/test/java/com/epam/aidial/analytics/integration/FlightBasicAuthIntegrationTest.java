package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.flight.FlightSqlServerLifecycle;
import com.epam.aidial.analytics.web.security.TestSecurityConfig;
import com.sun.net.httpserver.HttpServer;
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallInfo;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightClientMiddleware;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.grpc.CredentialCallOption;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code Basic} credential form over the real Flight handshake (spec: "Flight authentication mirrors
 * service security mode"), driven through {@link FlightClient#authenticateBasicToken} — which is exactly
 * what the Arrow Flight SQL ODBC drivers do: send {@code Basic} on {@code Handshake} only, then carry
 * forward whatever {@code Bearer }-prefixed value the server answered with. Arrow's
 * {@code ClientBearerHeaderHandler} applies the same prefix rule as the C++ client, so a present
 * credential here <em>is</em> the assertion that the handshake was answered in a form those drivers
 * accept; absent it, they build a zero-length gRPC metadata key and abort the client process.
 *
 * <p>Runs the Flight endpoint in {@code oidc} mode with {@code security.api-key.enabled=true} against an
 * in-JVM stub of DIAL Core's {@code /v1/user/info} (the same technique as {@code ApiKeyAuthIntegrationTest}),
 * which counts calls per key so the shared-cache claim can be checked.
 *
 * <p>Requires a running Docker daemon (PostgreSQL + ClickHouse Testcontainers) — {@code GetTables} reads
 * the catalog.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "poll.enabled=false",
        "security.mode=oidc",
        "security.defaults.email-claim=email",
        "security.defaults.principal-claim=sub",
        "providers.full.issuer=" + FlightBasicAuthIntegrationTest.ISSUER,
        "providers.full.jwk-set-uri=https://issuer.test/full/jwks",
        "providers.full.audiences=adas-test",
        "providers.full.role-claims=roles",
        "providers.full.roles-mapping={\"admin\":[\"FULL_ADMIN\"]}",
        "security.api-key.enabled=true",
        "security.api-key.startup-probe=false",
        "security.api-key.roles-mapping={\"admin\":[\"FULL_ADMIN\"],\"viewer\":[\"READ_ONLY_ADMIN\"]}",
})
@Import(TestSecurityConfig.class)
class FlightBasicAuthIntegrationTest {

    static final String ISSUER = "https://issuer.test/full";

    private static final String SENTINEL = "api-key";
    private static final String MARKED_BEARER_PREFIX = "Bearer adas-api-key:";

    /**
     * Keys are recognised by suffix, so every test can use its own key. That matters: the Core call
     * counter and the service's api-key cache both live for the whole class, so tests sharing a key would
     * depend on execution order.
     */
    private static final String ADMIN_SUFFIX = "-admin";
    private static final String VIEWER_SUFFIX = "-viewer";
    private static final String NO_ROLE_SUFFIX = "-norole";
    /** A key Core resolves with an empty role list: nothing to match globally or by grant. */
    private static final String NO_ROLES_AT_ALL_SUFFIX = "-noroles";

    private static HttpServer coreServer;
    private static final Map<String, AtomicInteger> coreCallsByKey = new ConcurrentHashMap<>();

    @Autowired
    private FlightSqlServerLifecycle lifecycle;

    private BufferAllocator clientAllocator;
    private FlightClient flightClient;
    private FlightSqlClient sqlClient;
    private ResponseAuthCapture.Factory responseHeaders;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerProperties(registry);
        ClickHouseContainerSupport.registerProperties(registry);
        registry.add("flight.enabled", () -> "true");
        registry.add("flight.port", () -> "0");
        final int port = startCoreServer();
        registry.add("security.api-key.core-url", () -> "http://localhost:" + port);
    }

    /** Stub of DIAL Core {@code /v1/user/info}: keys are resolved by suffix, 401 for everything else. */
    private static int startCoreServer() {
        try {
            coreServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start Core stub server", e);
        }
        coreServer.createContext("/v1/user/info", exchange -> {
            final String presented = exchange.getRequestHeaders().getFirst("Api-Key");
            final String apiKey = presented == null ? "" : presented;
            coreCallsByKey.computeIfAbsent(apiKey, k -> new AtomicInteger()).incrementAndGet();
            final String body;
            if (apiKey.endsWith(ADMIN_SUFFIX)) {
                body = "{\"project\":\"" + apiKey + "\",\"roles\":[\"admin\"]}";
            } else if (apiKey.endsWith(VIEWER_SUFFIX)) {
                body = "{\"project\":\"" + apiKey + "\",\"roles\":[\"viewer\"]}";
            } else if (apiKey.endsWith(NO_ROLES_AT_ALL_SUFFIX)) {
                body = "{\"project\":\"" + apiKey + "\",\"roles\":[]}";
            } else if (apiKey.endsWith(NO_ROLE_SUFFIX)) {
                body = "{\"project\":\"" + apiKey + "\",\"roles\":[\"nobody\"]}";
            } else {
                body = null;
            }
            if (body == null) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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

    @BeforeEach
    void openClient() {
        clientAllocator = new RootAllocator();
        responseHeaders = new ResponseAuthCapture.Factory();
        flightClient = FlightClient.builder(
                        clientAllocator, Location.forGrpcInsecure("localhost", lifecycle.boundPort()))
                .intercept(responseHeaders)
                .build();
        sqlClient = new FlightSqlClient(flightClient);
    }

    @AfterEach
    void closeClient() throws Exception {
        sqlClient.close();
        clientAllocator.close();
    }

    @Test
    void basicHandshakeIsAnsweredAndAuthenticatesSubsequentCalls() {
        final String key = "handshake" + ADMIN_SUFFIX;
        final Optional<CredentialCallOption> credential = flightClient.authenticateBasicToken(SENTINEL, key);

        // Present at all == the server answered with a Bearer-prefixed value. This is the failure mode the
        // ODBC drivers cannot survive, so it is asserted before anything else.
        assertThat(credential).isPresent();
        // And the value is the presented key in the marked envelope — not a token ADAS invented.
        assertThat(responseHeaders.received).contains(MARKED_BEARER_PREFIX + key);

        assertThat(listedTableNames(credential.orElseThrow())).contains("dial_usage_log");
    }

    @Test
    void sentinelUsernameIsCaseInsensitiveOverTheWire() {
        assertThat(flightClient.authenticateBasicToken("API-KEY", "casing" + ADMIN_SUFFIX)).isPresent();
    }

    @Test
    void wrongUsernameIsRejectedWithTheAcceptedUsernameNamed() {
        final String key = "wrong-username" + ADMIN_SUFFIX;

        assertThatThrownBy(() -> flightClient.authenticateBasicToken("someone@example.com", key))
                .isInstanceOfSatisfying(FlightRuntimeException.class, e -> {
                    assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED);
                    assertThat(e.status().description()).contains(SENTINEL);
                });
        // The key was valid; it must not have been introspected, so no principal was ever established.
        assertThat(coreCallsByKey.get(key)).isNull();
    }

    @Test
    void invalidKeyIsRejected() {
        assertThatThrownBy(() -> flightClient.authenticateBasicToken(SENTINEL, "bogus-key"))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHENTICATED));
    }

    @Test
    void keyMappingToNoPrivilegeIsAdmittedAndSeesNothing() {
        // The gate no longer tests for an application role. This key maps to no privilege but carries the
        // raw Core role `nobody`, so a per-table grant naming it would authorize it — which is exactly how
        // a BI tool is meant to be entitled, and what a role test here would take away. With no grant set,
        // it is admitted and the catalog it sees is empty.
        final Optional<CredentialCallOption> credential =
                flightClient.authenticateBasicToken(SENTINEL, "unmapped" + NO_ROLE_SUFFIX);

        assertThat(credential).isPresent();
        assertThat(listedTableNames(credential.orElseThrow())).isEmpty();
    }

    @Test
    void keyWithNoRolesAtAllIsDeniedPermission() {
        // Nothing to match, globally or by grant, so this caller can never reach a table. Rejected at the
        // gate rather than served an empty catalog on every call.
        assertThatThrownBy(() -> flightClient.authenticateBasicToken(SENTINEL, "empty" + NO_ROLES_AT_ALL_SUFFIX))
                .isInstanceOfSatisfying(FlightRuntimeException.class,
                        e -> assertThat(e.status().code()).isEqualTo(FlightStatusCode.UNAUTHORIZED));
    }

    @Test
    void readOnlyKeyAuthenticatesAndQueriesTheCatalog() {
        final Optional<CredentialCallOption> credential =
                flightClient.authenticateBasicToken(SENTINEL, "readonly" + VIEWER_SUFFIX);

        assertThat(credential).isPresent();
        assertThat(listedTableNames(credential.orElseThrow())).contains("dial_usage_log");
    }

    @Test
    void handshakeAndSubsequentCallsShareOneIntrospection() {
        // Every call re-enters the api-key path — including the handshake — so the only thing keeping Core
        // traffic down is the shared cache, not a session credential.
        final String key = "cache-probe" + ADMIN_SUFFIX;
        final CredentialCallOption credential =
                flightClient.authenticateBasicToken(SENTINEL, key).orElseThrow();
        listedTableNames(credential);
        listedTableNames(credential);

        assertThat(coreCallsByKey.get(key).get()).isEqualTo(1);
    }

    /** The {@code table_name} values {@code GetTables} streams to {@code credentials}. */
    private List<String> listedTableNames(CallOption credentials) {
        final FlightInfo info = sqlClient.getTables(null, null, null, null, false, credentials);
        final List<String> names = new ArrayList<>();
        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().getFirst().getTicket(), credentials)) {
            while (stream.next()) {
                final VarCharVector column = (VarCharVector) stream.getRoot().getVector("table_name");
                for (int row = 0; row < stream.getRoot().getRowCount(); row++) {
                    names.add(column.getObject(row).toString());
                }
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return names;
    }

    /** Records every {@code authorization} value the server sends back, so the answer can be asserted verbatim. */
    private static final class ResponseAuthCapture implements FlightClientMiddleware {

        private final Factory factory;

        private ResponseAuthCapture(Factory factory) {
            this.factory = factory;
        }

        @Override
        public void onBeforeSendingHeaders(CallHeaders outgoingHeaders) {
            // Nothing to add.
        }

        @Override
        public void onHeadersReceived(CallHeaders incomingHeaders) {
            final String value = incomingHeaders.get("authorization");
            if (value != null) {
                factory.received.add(value);
            }
        }

        @Override
        public void onCallCompleted(CallStatus status) {
            // Nothing to release.
        }

        private static final class Factory implements FlightClientMiddleware.Factory {

            private final List<String> received = new CopyOnWriteArrayList<>();

            @Override
            public FlightClientMiddleware onCallStarted(CallInfo info) {
                return new ResponseAuthCapture(this);
            }
        }
    }
}
