package com.epam.aidial.analytics.blob;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.util.Base64;

/**
 * Shared scaffolding for tests that stand in for Google's token endpoint and JSON APIs on a loopback
 * {@code HttpServer}: a fresh service-account key, a key file around it, a JSON reply, and the claims of a
 * JWT-bearer token request.
 */
public final class GoogleTestSupport {

    private GoogleTestSupport() {
    }

    /** A fresh 2048-bit RSA private key, the kind a service-account key carries. */
    public static PrivateKey rsaPrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair().getPrivate();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A fresh RSA key in PKCS#8 PEM, the form a service-account key file carries. */
    public static String pemPrivateKey() {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(rsaPrivateKey().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    /** A service-account key file for {@code clientEmail} whose token requests go to {@code tokenUri}. */
    public static String serviceAccountKeyJson(String clientEmail, String tokenUri) {
        return """
                {
                  "type": "service_account",
                  "project_id": "example-project",
                  "private_key_id": "0000000000000000000000000000000000000001",
                  "private_key": "%s",
                  "client_email": "%s",
                  "client_id": "000000000000000000001",
                  "token_uri": "%s"
                }
                """.formatted(pemPrivateKey().replace("\n", "\\n"), clientEmail, tokenUri);
    }

    /** Replies with a JSON body and the given status. */
    public static void respondJson(HttpExchange exchange, int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A token reply carrying {@code token}, valid for an hour. */
    public static String tokenJson(String token) {
        return "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
    }

    /**
     * The claims JSON of a JWT-bearer token request, from its URL-encoded form body; fails if the form is not a
     * JWT-bearer grant.
     */
    public static String jwtBearerClaims(String form) {
        String decoded = URLDecoder.decode(form, StandardCharsets.UTF_8);
        if (!decoded.contains("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer") || !decoded.contains("assertion=")) {
            throw new AssertionError("Not a JWT-bearer token request: " + decoded);
        }
        return new String(Base64.getUrlDecoder().decode(decoded.split("assertion=")[1].split("\\.")[1]), StandardCharsets.UTF_8);
    }
}
