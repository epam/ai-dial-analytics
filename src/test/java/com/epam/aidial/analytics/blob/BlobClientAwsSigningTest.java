package com.epam.aidial.analytics.blob;

import com.epam.aidial.analytics.blob.credential.CredentialProvider;
import com.epam.aidial.analytics.configuration.BlobProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jclouds.aws.domain.SessionCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that jclouds' {@code aws-s3} provider actually honours the temporary credentials the AWS
 * workload-identity path produces: a {@link SessionCredentials} must be signed SigV4 <em>and</em> carry
 * the session token as {@code X-Amz-Security-Token}, or S3 rejects every request from an IRSA pod.
 *
 * <p>This asserts the signer, not STS — the credential is fixed, substituted through
 * {@link BlobClient#credentialProvider(String, String)}. The endpoint is a JDK {@code com.sun.net.httpserver}
 * loopback server, so the test needs neither AWS nor a container: {@code aws-s3} signs unconditionally and
 * resolves an unrecognised host to {@code us-east-1}, which makes the signature deterministic.
 */
class BlobClientAwsSigningTest {

    /** Must be DNS-compatible even in path-style mode: the provider validates the name before signing. */
    private static final String BUCKET = "adas-signing-test";
    private static final String SESSION_TOKEN = "FwoGZXIvYXdzEXAMPLESESSIONTOKEN";

    private static final String LIST_RESPONSE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
              <Name>adas-signing-test</Name>
              <Prefix>logs/</Prefix>
              <IsTruncated>false</IsTruncated>
            </ListBucketResult>
            """;

    private HttpServer server;
    private BlobClient blobClient;
    private final AtomicReference<Map<String, List<String>>> captured = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        BlobProperties properties = new BlobProperties(
                "aws-s3", null, null, BUCKET,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                // aws-s3 defaults virtual-host addressing on, which would resolve <bucket>.127.0.0.1.
                Map.of("jclouds.s3.virtual-host-buckets", "false"));
        blobClient = new BlobClient(properties, TestBlobSpans.noop()) {
            @Override
            CredentialProvider credentialProvider(String identity, String credential) {
                SessionCredentials session = SessionCredentials.builder()
                        .accessKeyId("ASIAEXAMPLETEMPID")
                        .secretAccessKey("temp-secret")
                        .sessionToken(SESSION_TOKEN)
                        .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                        .build();
                return () -> session;
            }
        };
    }

    @AfterEach
    void stopServer() {
        blobClient.shutdown();
        server.stop(0);
    }

    @Test
    void temporaryCredentialsAreSignedWithSessionToken() {
        List<String> keys = blobClient.list("logs/");

        assertThat(keys).isEmpty();
        Map<String, List<String>> headers = captured.get();
        assertThat(headers).as("the store was contacted").isNotNull();
        assertThat(header(headers, "X-Amz-Security-Token")).isEqualTo(SESSION_TOKEN);
        assertThat(header(headers, "Authorization"))
                .startsWith("AWS4-HMAC-SHA256")
                .contains("ASIAEXAMPLETEMPID")
                .contains("/s3/aws4_request");
        // The token is part of what is signed, so a signer that emitted the header without covering it
        // would still be rejected by S3; SignedHeaders proves it was.
        assertThat(header(headers, "Authorization")).contains("x-amz-security-token");
    }

    private void respond(HttpExchange exchange) throws IOException {
        captured.compareAndSet(null, Map.copyOf(exchange.getRequestHeaders()));
        byte[] body = LIST_RESPONSE.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/xml");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** Header lookup that ignores case, as the JDK server's own map does not once it is copied. */
    private static String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
    }
}
