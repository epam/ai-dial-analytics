package com.epam.aidial.analytics.integration;

import com.sun.net.httpserver.HttpServer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Singleton userinfo endpoint backing the {@code providers.opaque} provider of
 * {@link AbstractOidcAuthIntegrationTest}, mirroring {@link PostgresContainerSupport}: started once on
 * first use and reused for the JVM lifetime. It accepts the one known token
 * ({@link #OPAQUE_READONLY_TOKEN}) and returns claims carrying a {@code viewer} role; every other token
 * gets a 401, mimicking an IdP rejecting an unknown token.
 *
 * <p>Lifetime is what makes it a singleton rather than a per-class fixture. The opaque provider is part
 * of the shared context's configuration, and that context outlives the class that first triggered it —
 * a server stopped in an {@code @AfterAll} would leave every later class pointed at a dead port.
 *
 * <p>Nothing stops it, which is only safe because it is started from a <strong>daemon</strong> thread:
 * {@code HttpServer}'s internal {@code HTTP-Dispatcher} thread inherits the daemon flag of whichever
 * thread constructed it, and a non-daemon one would keep the Gradle test worker JVM alive after the
 * last test.
 *
 * <p><b>The daemon thread's body must live in {@link Starter}, not here.</b> Handing this class's own
 * code to a thread started from its {@code <clinit>} deadlocks: the initializing thread holds this
 * class's initialization monitor and then joins the new thread, while the new thread cannot enter a
 * method of a class whose initialization is in progress on another thread. The result is a permanent
 * hang — the suite stops rather than fails, so it reads as a slow build rather than a broken one.
 */
final class OpaqueUserInfoServerSupport {

    /** The one opaque token the stub accepts. */
    static final String OPAQUE_READONLY_TOKEN = "opaque-readonly";

    private static final String CLAIMS =
            "{\"sub\":\"opaque-user\",\"email\":\"opaque@test\",\"roles\":[\"viewer\"]}";

    private static final HttpServer USER_INFO_SERVER = Starter.onDaemonThread(OPAQUE_READONLY_TOKEN, CLAIMS);

    private OpaqueUserInfoServerSupport() {
    }

    /** Wires the singleton stub into {@code providers.opaque.*}. */
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("providers.opaque.user-info-endpoint",
                () -> "http://localhost:" + USER_INFO_SERVER.getAddress().getPort() + "/userinfo");
        registry.add("providers.opaque.role-claims", () -> "roles");
        registry.add("providers.opaque.roles-mapping", () -> "{\"viewer\":[\"READ_ONLY_ADMIN\"]}");
        registry.add("providers.opaque.email-claims", () -> "email");
        registry.add("providers.opaque.principal-claim", () -> "sub");
    }

    /**
     * Starts the stub on a daemon thread. Every member is {@code Starter}'s own and the two claim values
     * arrive as arguments, so the started thread never needs
     * {@link OpaqueUserInfoServerSupport} — which is still initializing when this runs.
     */
    private static final class Starter {

        private Starter() {
        }

        static HttpServer onDaemonThread(String token, String claims) {
            final HttpServer[] started = new HttpServer[1];
            final RuntimeException[] failure = new RuntimeException[1];
            final Thread starter = new Thread(new Creator(token, claims, started, failure),
                    "opaque-userinfo-starter");
            starter.setDaemon(true);
            starter.start();
            try {
                starter.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while starting the userinfo test server", e);
            }
            if (failure[0] != null) {
                throw failure[0];
            }
            if (started[0] == null) {
                throw new IllegalStateException("Unable to start the userinfo test server");
            }
            return started[0];
        }

        /**
         * A named class rather than a lambda: a lambda's synthetic method belongs to its <em>capturing</em>
         * class, so writing one here would put the thread's entry point back on the class this exists to
         * stay away from.
         */
        private record Creator(String token, String claims, HttpServer[] started, RuntimeException[] failure)
                implements Runnable {

            @Override
            public void run() {
                try {
                    started[0] = create(token, claims);
                } catch (RuntimeException e) {
                    // Carried back to the joining thread: an exception thrown here would otherwise reach
                    // only the default handler, and the caller would see the less informative null check.
                    failure[0] = e;
                }
            }
        }

        private static HttpServer create(String token, String claims) {
            try {
                final HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                server.createContext("/userinfo", exchange -> {
                    if (("Bearer " + token).equals(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION))) {
                        final byte[] body = claims.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                        exchange.sendResponseHeaders(200, body.length);
                        try (OutputStream responseBody = exchange.getResponseBody()) {
                            responseBody.write(body);
                        }
                    } else {
                        exchange.sendResponseHeaders(401, -1);
                        exchange.close();
                    }
                });
                server.start();
                return server;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to start the userinfo test server", e);
            }
        }
    }
}
