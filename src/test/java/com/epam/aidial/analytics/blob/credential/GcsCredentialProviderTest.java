package com.epam.aidial.analytics.blob.credential;

import com.epam.aidial.analytics.observability.EventCapture;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.sun.net.httpserver.HttpServer;
import org.jclouds.domain.Credentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.epam.aidial.analytics.blob.GoogleTestSupport.jwtBearerClaims;
import static com.epam.aidial.analytics.blob.GoogleTestSupport.respondJson;
import static com.epam.aidial.analytics.blob.GoogleTestSupport.rsaPrivateKey;
import static com.epam.aidial.analytics.blob.GoogleTestSupport.serviceAccountKeyJson;
import static com.epam.aidial.analytics.blob.GoogleTestSupport.tokenJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GcsCredentialProvider}: the key-pair-vs-keyless decision, mapping a Google access token
 * onto the jclouds bearer credential, refresh ahead of expiry, single first resolution under concurrency, lazy
 * and retryable failure, a refused token told apart from a missing credential, the ADC lookup order and its
 * re-reading of a replaced file, the read-only scope, principal reporting, and that no token reaches the log.
 *
 * <p>No GCP is contacted and no ADC environment is read: keyless cases go through the package-private
 * constructor, and the lookup is given its file paths. One case does a real token exchange against a loopback server, which also proves the library's
 * default transport works with the Apache httpclient 4 jars excluded from the build.
 */
class GcsCredentialProviderTest {

    private static final String CLIENT_EMAIL = "adas-reader@example-project.iam.gserviceaccount.com";
    private static final String OTHER_EMAIL = "adas-replacement@example-project.iam.gserviceaccount.com";

    @TempDir
    Path dir;

    private EventCapture capture;

    @BeforeEach
    void captureLogs() {
        capture = EventCapture.attach();
    }

    @AfterEach
    void releaseLogs() {
        capture.detach();
    }

    /** Messages this provider logged; other loggers sharing the root are ignored. */
    private List<String> logged() {
        return capture.events().stream()
                .filter(event -> event.getLoggerName().equals(GcsCredentialProvider.class.getName()))
                .map(EventCapture::rendered)
                .toList();
    }

    @Test
    void keyPairIsHandedOverUnchanged() {
        GcsCredentialProvider provider = new GcsCredentialProvider(CLIENT_EMAIL, "-----BEGIN PRIVATE KEY-----");

        Credentials credentials = provider.getCredentials();

        assertThat(credentials.identity).isEqualTo(CLIENT_EMAIL);
        assertThat(credentials.credential).isEqualTo("-----BEGIN PRIVATE KEY-----");
        assertThat(logged()).isEmpty();
    }

    @Test
    void keylessTokenBecomesBearerCredential() {
        GcsCredentialProvider provider = new GcsCredentialProvider(() -> new CountingCredentials("ya29.first"));

        Credentials credentials = provider.getCredentials();

        assertThat(credentials.identity).isEmpty();
        assertThat(credentials.credential).isEqualTo("ya29.first-1");
    }

    @Test
    void expiringTokenIsRefreshedBeforeUse() {
        // Every token issued expires within the library's margin, so each use must refresh first.
        CountingCredentials stub = new CountingCredentials("ya29.short", 30_000L);
        GcsCredentialProvider provider = new GcsCredentialProvider(() -> stub);

        assertThat(provider.getCredentials().credential).isEqualTo("ya29.short-1");
        assertThat(provider.getCredentials().credential).isEqualTo("ya29.short-2");
        assertThat(stub.refreshes.get()).isEqualTo(2);
    }

    @Test
    void validTokenIsReusedWithoutRefresh() {
        CountingCredentials stub = new CountingCredentials("ya29.long");
        GcsCredentialProvider provider = new GcsCredentialProvider(() -> stub);

        provider.getCredentials();
        provider.getCredentials();

        assertThat(stub.refreshes.get()).isEqualTo(1);
    }

    @Test
    void concurrentFirstUseResolvesOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountingCredentials stub = new CountingCredentials("ya29.shared");
        GcsCredentialProvider provider = new GcsCredentialProvider(() -> {
            loads.incrementAndGet();
            return stub;
        });
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Credentials>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return provider.getCredentials();
                }));
            }
            start.countDown();
            for (Future<Credentials> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS).credential).isEqualTo("ya29.shared-1");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads.get()).isEqualTo(1);
        assertThat(stub.refreshes.get()).isEqualTo(1);
    }

    @Test
    void failedLookupSurfacesPerCallAndIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        GcsCredentialProvider provider = new GcsCredentialProvider(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IOException("The Application Default Credentials are not available.");
            }
            return new CountingCredentials("ya29.recovered");
        });

        assertThatThrownBy(provider::getCredentials)
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("GOOGLE_APPLICATION_CREDENTIALS");
        assertThat(provider.getCredentials().credential).isEqualTo("ya29.recovered-1");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void failedRenewalDropsTheCredentialAndTheNextCallResolvesAgain() {
        // The first credential stands for a rotated-away key: its first token is issued, every renewal is refused.
        AtomicInteger loads = new AtomicInteger();
        GcsCredentialProvider provider = new GcsCredentialProvider(() -> {
            if (loads.incrementAndGet() == 1) {
                return new CountingCredentials("ya29.old", 30_000L) {
                    @Override
                    public AccessToken refreshAccessToken() throws IOException {
                        if (refreshes.get() >= 1) {
                            throw new IOException("invalid_grant: Invalid JWT Signature.");
                        }
                        return super.refreshAccessToken();
                    }
                };
            }
            return new CountingCredentials("ya29.rotated");
        });

        assertThat(provider.getCredentials().credential).isEqualTo("ya29.old-1");
        assertThatThrownBy(provider::getCredentials)
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageStartingWith("Failed to obtain a Google access token");
        assertThat(provider.getCredentials().credential).isEqualTo("ya29.rotated-1");
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void refusedTokenIsReportedApartFromMissingCredential() {
        AtomicInteger tokenRequests = new AtomicInteger();
        GcsCredentialProvider provider = new GcsCredentialProvider(() -> new CountingCredentials("ya29.later") {
            @Override
            public AccessToken refreshAccessToken() throws IOException {
                if (tokenRequests.incrementAndGet() == 1) {
                    throw new IOException("403 Forbidden");
                }
                return super.refreshAccessToken();
            }
        });

        assertThatThrownBy(provider::getCredentials)
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageStartingWith("Failed to obtain a Google access token")
                .hasMessageNotContaining("Cannot resolve")
                .hasRootCauseMessage("403 Forbidden");
        assertThat(provider.getCredentials().credential).isEqualTo("ya29.later-1");
    }

    @Test
    void lookupPrefersTheNamedFileAndRereadsItEachTime() throws IOException {
        Path keyFile = dir.resolve("key.json");
        Path wellKnown = dir.resolve("application_default_credentials.json");
        Files.writeString(keyFile, serviceAccountKeyJson(CLIENT_EMAIL, "http://127.0.0.1:1/token"));
        Files.writeString(wellKnown, serviceAccountKeyJson(OTHER_EMAIL, "http://127.0.0.1:1/token"));

        assertThat(GcsCredentialProvider.principalOf(GcsCredentialProvider.loadApplicationDefault(keyFile.toString(), wellKnown)))
                .isEqualTo(CLIENT_EMAIL);
        // A replaced file is read again: nothing is cached across lookups.
        Files.writeString(keyFile, serviceAccountKeyJson(OTHER_EMAIL, "http://127.0.0.1:1/token"));
        assertThat(GcsCredentialProvider.principalOf(GcsCredentialProvider.loadApplicationDefault(keyFile.toString(), wellKnown)))
                .isEqualTo(OTHER_EMAIL);
    }

    @Test
    void lookupFallsBackToGcloudFileThenMetadataServer() throws IOException {
        Path wellKnown = dir.resolve("application_default_credentials.json");

        assertThat(GcsCredentialProvider.loadApplicationDefault(null, wellKnown)).isInstanceOf(ComputeEngineCredentials.class);
        assertThat(GcsCredentialProvider.loadApplicationDefault(" ", wellKnown)).isInstanceOf(ComputeEngineCredentials.class);

        Files.writeString(wellKnown, serviceAccountKeyJson(CLIENT_EMAIL, "http://127.0.0.1:1/token"));
        assertThat(GcsCredentialProvider.principalOf(GcsCredentialProvider.loadApplicationDefault(null, wellKnown)))
                .isEqualTo(CLIENT_EMAIL);
    }

    @Test
    void missingNamedFileFailsTheLookup() {
        Path missing = dir.resolve("absent.json");

        assertThatThrownBy(() -> GcsCredentialProvider.loadApplicationDefault(missing.toString(), dir.resolve("none.json")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("GOOGLE_APPLICATION_CREDENTIALS")
                .hasMessageContaining(missing.toString());
    }

    @Test
    void constructionLooksNothingUp() {
        AtomicInteger loads = new AtomicInteger();

        new GcsCredentialProvider(() -> {
            loads.incrementAndGet();
            throw new IOException("unavailable");
        });

        assertThat(loads.get()).isZero();
    }

    @Test
    void scopeRequestedIsReadOnly() {
        // Like ComputeEngineCredentials and ExternalAccountCredentials, this reports createScopedRequired() false
        // yet accepts a scope, so the read-only scope must be requested regardless of that flag.
        AtomicReference<Collection<String>> requested = new AtomicReference<>();
        GoogleCredentials unscoped = new CountingCredentials("unused") {
            @Override
            public GoogleCredentials createScoped(Collection<String> scopes) {
                requested.set(scopes);
                return new CountingCredentials("ya29.scoped");
            }
        };

        Credentials credentials = new GcsCredentialProvider(() -> unscoped).getCredentials();

        assertThat(requested.get()).containsExactly(GcsCredentialProvider.READ_ONLY_SCOPE);
        assertThat(credentials.credential).isEqualTo("ya29.scoped-1");
    }

    @Test
    void principalIsLoggedOnceAndTokenNever() {
        GoogleCredentials serviceAccount = ServiceAccountCredentials.newBuilder()
                .setClientEmail(CLIENT_EMAIL)
                .setPrivateKey(rsaPrivateKey())
                .build();
        assertThat(GcsCredentialProvider.principalOf(serviceAccount)).isEqualTo(CLIENT_EMAIL);
        assertThat(GcsCredentialProvider.principalOf(new CountingCredentials("x"))).isEqualTo("unknown");

        GcsCredentialProvider provider = new GcsCredentialProvider(() -> new CountingCredentials("ya29.secret-token", 30_000L));
        provider.getCredentials();
        provider.getCredentials();

        assertThat(logged()).singleElement().satisfies(message -> assertThat(message).contains("principal unknown"));
        assertThat(logged()).noneSatisfy(message -> assertThat(message).contains("ya29.secret-token"));
    }

    /**
     * A real service-account token exchange over the library's default transport against a loopback token
     * endpoint: the JWT-bearer grant is posted with the read-only scope, its token becomes the bearer
     * credential, and the principal logged is the account's email.
     */
    @Test
    void serviceAccountExchangeRunsOverDefaultTransport() throws IOException {
        AtomicReference<String> claims = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            claims.set(jwtBearerClaims(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respondJson(exchange, 200, tokenJson("ya29.exchanged"));
        });
        server.start();
        try {
            GoogleCredentials keyFile = ServiceAccountCredentials.newBuilder()
                    .setClientEmail(CLIENT_EMAIL)
                    .setPrivateKey(rsaPrivateKey())
                    .setTokenServerUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"))
                    .build();

            Credentials credentials = new GcsCredentialProvider(() -> keyFile).getCredentials();

            assertThat(credentials.credential).isEqualTo("ya29.exchanged");
            assertThat(claims.get()).contains(GcsCredentialProvider.READ_ONLY_SCOPE);
            assertThat(logged()).singleElement()
                    .satisfies(message -> assertThat(message).contains(CLIENT_EMAIL).doesNotContain("ya29.exchanged"));
        } finally {
            server.stop(0);
        }
    }

    /** Issues a numbered token per refresh, valid for the given lifetime (an hour by default). */
    private static class CountingCredentials extends GoogleCredentials {

        private final String prefix;
        private final long lifetimeMillis;
        final AtomicInteger refreshes = new AtomicInteger();

        CountingCredentials(String prefix) {
            this(prefix, 3_600_000L);
        }

        CountingCredentials(String prefix, long lifetimeMillis) {
            this.prefix = prefix;
            this.lifetimeMillis = lifetimeMillis;
        }

        @Override
        public AccessToken refreshAccessToken() throws IOException {
            return new AccessToken(prefix + "-" + refreshes.incrementAndGet(), new Date(System.currentTimeMillis() + lifetimeMillis));
        }
    }
}
