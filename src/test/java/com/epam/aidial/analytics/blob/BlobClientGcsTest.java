package com.epam.aidial.analytics.blob;

import com.epam.aidial.analytics.blob.credential.CredentialProvider;
import com.epam.aidial.analytics.configuration.BlobProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jclouds.domain.Credentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.epam.aidial.analytics.blob.GoogleTestSupport.jwtBearerClaims;
import static com.epam.aidial.analytics.blob.GoogleTestSupport.pemPrivateKey;
import static com.epam.aidial.analytics.blob.GoogleTestSupport.respondJson;
import static com.epam.aidial.analytics.blob.GoogleTestSupport.tokenJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that jclouds' {@code google-cloud-storage} provider behaves the way {@link BlobClient} relies on, in
 * both credential modes, against a JDK {@code com.sun.net.httpserver} loopback server standing in for the
 * Cloud Storage JSON API — no GCP, no emulator, no container.
 *
 * <ul>
 *   <li>Keyless: the token from the credential provider is sent as {@code Authorization: Bearer}, a listing
 *       split by {@code nextPageToken} is followed to completion, the object's {@code updated} time becomes
 *       the listing's last-modified time, and a key containing {@code /} streams its bytes.</li>
 *   <li>Key pair: jclouds' native JWT-bearer flow is reached — a signed assertion is posted to the token
 *       endpoint and the token it returns authorizes the listing — with no placeholder in between.</li>
 * </ul>
 */
class BlobClientGcsTest {

    private static final String BUCKET = "adas-gcs-test";
    private static final String KEYLESS_TOKEN = "ya29.keyless-test-token";
    private static final String JWT_TOKEN = "ya29.jwt-test-token";
    private static final String KEY = "logs/2026/06/18/date=2026-06-18T10-15-30.log.gz";
    private static final String OTHER_KEY = "logs/2026/06/18/date=2026-06-18T10-20-00.log.gz";
    private static final byte[] CONTENT = "gzip-bytes".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private BlobClient blobClient;
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private record Request(String method, String path, String query, String authorization, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (blobClient != null) {
            blobClient.shutdown();
        }
        server.stop(0);
    }

    @Test
    void keylessListingIsBearerAuthorizedAndFollowsPages() {
        blobClient = keylessClient();

        List<BlobListing> listings = blobClient.listWithMetadata("logs/2026/06/18/");

        assertThat(listings).extracting(BlobListing::name).containsExactly(KEY, OTHER_KEY);
        assertThat(listings.get(0).lastModified()).isEqualTo(Instant.parse("2026-06-18T10:16:00Z"));
        assertThat(listings.get(1).lastModified()).isEqualTo(Instant.parse("2026-06-18T10:21:00Z"));
        List<Request> lists = requests.stream().filter(r -> r.path().equals("/storage/v1/b/" + BUCKET + "/o")).toList();
        assertThat(lists).hasSize(2);
        assertThat(lists).allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer " + KEYLESS_TOKEN));
        assertThat(lists.get(0).query()).contains("prefix=logs/2026/06/18/").doesNotContain("delimiter");
        assertThat(lists.get(1).query()).contains("pageToken=page-2");
    }

    /**
     * A renewed token reaches the next request: jclouds consults the credential supplier per request rather than
     * memoizing the first value, so a keyless token refreshed after its hour is what the bucket sees.
     */
    @Test
    void keylessRenewedTokenReachesNextRequest() {
        AtomicInteger issued = new AtomicInteger();
        blobClient = keylessClient(() -> new Credentials("", KEYLESS_TOKEN + "-" + issued.incrementAndGet()));

        blobClient.list("logs/2026/06/18/");
        blobClient.list("logs/2026/06/18/");

        List<String> authorizations = requests.stream().map(Request::authorization).distinct().toList();
        assertThat(authorizations).hasSizeGreaterThan(1).allSatisfy(a -> assertThat(a).startsWith("Bearer " + KEYLESS_TOKEN + "-"));
    }

    @Test
    void keylessReadStreamsObjectWithSlashesInKey() throws IOException {
        blobClient = keylessClient();

        byte[] bytes;
        try (InputStream in = blobClient.openStream(KEY)) {
            bytes = in.readAllBytes();
        }

        assertThat(bytes).isEqualTo(CONTENT);
        assertThat(requests).isNotEmpty().allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer " + KEYLESS_TOKEN));
    }

    @Test
    void keyPairReachesNativeJwtBearerFlow() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        BlobProperties properties = new BlobProperties(
                "google-cloud-storage", "adas-reader@example-project.iam.gserviceaccount.com", pemPrivateKey(), BUCKET,
                base, Map.of("oauth.endpoint", base + "/token"));
        blobClient = new BlobClient(properties, TestBlobSpans.noop());

        List<String> keys = blobClient.list("logs/2026/06/18/");

        assertThat(keys).containsExactly(KEY, OTHER_KEY);
        Request token = requests.stream().filter(r -> r.path().equals("/token")).findFirst().orElseThrow();
        assertThat(token.method()).isEqualTo("POST");
        String claims = jwtBearerClaims(token.body());
        // GET/HEAD-only use means the provider asks for the read-only scope on its own.
        assertThat(claims).contains("\"iss\":\"adas-reader@example-project.iam.gserviceaccount.com\"")
                .contains("devstorage.read_only");
        assertThat(requests.stream().filter(r -> r.path().startsWith("/storage/")))
                .allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer " + JWT_TOKEN));
    }

    private BlobClient keylessClient() {
        Credentials token = new Credentials("", KEYLESS_TOKEN);
        return keylessClient(() -> token);
    }

    private BlobClient keylessClient(CredentialProvider provider) {
        BlobProperties properties = new BlobProperties(
                "google-cloud-storage", null, null, BUCKET, "http://127.0.0.1:" + server.getAddress().getPort(), Map.of());
        return new BlobClient(properties, TestBlobSpans.noop()) {
            @Override
            CredentialProvider credentialProvider(String identity, String credential) {
                return provider;
            }
        };
    }

    private void respond(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        String query = rawQuery == null ? "" : URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(), path, query,
                exchange.getRequestHeaders().getFirst("Authorization"), body));

        if (path.equals("/token")) {
            respondJson(exchange, 200, tokenJson(JWT_TOKEN));
        } else if (path.equals("/storage/v1/b/" + BUCKET + "/o")) {
            respondJson(exchange, 200, query.contains("pageToken=page-2")
                    ? "{\"kind\":\"storage#objects\",\"items\":[" + object(OTHER_KEY, "2026-06-18T10:21:00.000Z") + "]}"
                    : "{\"kind\":\"storage#objects\",\"items\":[" + object(KEY, "2026-06-18T10:16:00.000Z") + "],"
                            + "\"nextPageToken\":\"page-2\"}");
        } else if (path.endsWith("/o/" + KEY) && query.contains("alt=media")) {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, CONTENT.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(CONTENT);
            }
        } else if (path.endsWith("/o/" + KEY)) {
            respondJson(exchange, 200, object(KEY, "2026-06-18T10:16:00.000Z"));
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }
    }

    /** An object resource carrying every field the provider's domain type refuses to be without. */
    private String object(String name, String updated) {
        String self = "http://127.0.0.1:" + server.getAddress().getPort() + "/storage/v1/b/" + BUCKET + "/o/" + name;
        return "{\"kind\":\"storage#object\",\"id\":\"" + BUCKET + "/" + name + "/1\",\"selfLink\":\"" + self + "\","
                + "\"mediaLink\":\"" + self + "?alt=media\",\"etag\":\"CAE=\",\"name\":\"" + name + "\","
                + "\"bucket\":\"" + BUCKET + "\",\"generation\":\"1\",\"metageneration\":\"1\","
                + "\"contentType\":\"application/gzip\",\"size\":\"" + CONTENT.length + "\",\"metadata\":{},\"acl\":[],"
                + "\"timeCreated\":\"" + updated + "\",\"updated\":\"" + updated + "\",\"storageClass\":\"STANDARD\"}";
    }
}
