package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.configuration.CursorProperties;
import com.epam.aidial.analytics.exception.InvalidCursorException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encodes/decodes the opaque scan cursor. A cursor is
 * {@code base64url(payload) "." base64url(hmac)} where {@code payload =
 * formatVersion ∥ keyId ∥ entity ∥ tupleValues…} (unit-separated) and {@code hmac} is
 * HMAC-SHA256 over the payload under the keyring key named by {@code keyId}. Decoding verifies the
 * HMAC and that the encoded entity matches the pipeline's source; a malformed, tampered, cross-entity,
 * or unknown/retired-key cursor raises {@link InvalidCursorException} (400 {@code invalid_cursor}).
 * The keyring lets the secret rotate without invalidating cursors in flight.
 *
 * <p>The cursor is opaque to callers — they echo it verbatim; ADAS owns the tuple. The server keeps
 * no cursor state (it signs and decodes only), so live and backfill scans of the same pipeline are
 * indistinguishable server-side.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CursorCodec {

    private static final String FORMAT_VERSION = "1";
    private static final char SEP = '';
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final CursorProperties properties;

    /** A decoded cursor: the source entity it was issued for and the ordered tuple values. */
    public record Cursor(String entity, List<String> values) {}

    /**
     * Fails fast on a keyring misconfiguration (the active key must exist so cursors can be signed) and
     * warns when the active key's secret is blank — the degenerate default under which cursor integrity
     * is not actually enforced.
     */
    @PostConstruct
    void validateKeyring() {
        byte[] secret = secretFor(properties.activeKeyId());
        if (secret == null) {
            throw new IllegalStateException("cursor active-key-id '" + properties.activeKeyId()
                    + "' is not present in enrichment.cursor.keys");
        }
        if (secret.length == 0) {
            log.warn("Cursor active key '{}' has a blank secret — scan-cursor integrity is NOT enforced; "
                    + "set ENRICHMENT_CURSOR_SECRET to a strong value in any real deployment",
                    properties.activeKeyId());
        }
    }

    /** Encodes a cursor for {@code entity} over {@code values}, signed with the active keyring key. */
    public String encode(String entity, List<String> values) {
        String keyId = properties.activeKeyId();
        byte[] secret = secretFor(keyId);
        if (secret == null) {
            throw new IllegalStateException("cursor active key '" + keyId + "' is not present in the keyring");
        }
        String payload = payload(keyId, entity, values);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        byte[] mac = hmac(secret, payloadBytes);
        return ENCODER.encodeToString(payloadBytes) + "." + ENCODER.encodeToString(mac);
    }

    /**
     * Decodes and verifies {@code token}, requiring it to have been issued for {@code expectedEntity}.
     *
     * @throws InvalidCursorException if the token is malformed, its HMAC fails, its key id is unknown,
     *                                or its entity does not match {@code expectedEntity}
     */
    public Cursor decode(String token, String expectedEntity) {
        if (StringUtils.isBlank(token)) {
            throw new InvalidCursorException("cursor is empty");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new InvalidCursorException("cursor is malformed");
        }
        byte[] payloadBytes;
        byte[] presentedMac;
        try {
            payloadBytes = DECODER.decode(token.substring(0, dot));
            presentedMac = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException ex) {
            throw new InvalidCursorException("cursor is not valid base64url");
        }
        List<String> fields = split(new String(payloadBytes, StandardCharsets.UTF_8));
        if (fields.size() < 3 || !FORMAT_VERSION.equals(fields.get(0))) {
            throw new InvalidCursorException("cursor format is unsupported");
        }
        String keyId = fields.get(1);
        byte[] secret = secretFor(keyId);
        if (secret == null) {
            throw new InvalidCursorException("cursor key is unknown or retired");
        }
        byte[] expectedMac = hmac(secret, payloadBytes);
        if (!MessageDigest.isEqual(expectedMac, presentedMac)) {
            throw new InvalidCursorException("cursor signature is invalid");
        }
        String entity = fields.get(2);
        if (!entity.equals(expectedEntity)) {
            throw new InvalidCursorException("cursor was issued for a different source");
        }
        return new Cursor(entity, List.copyOf(fields.subList(3, fields.size())));
    }

    private static String payload(String keyId, String entity, List<String> values) {
        StringBuilder sb = new StringBuilder()
                .append(FORMAT_VERSION).append(SEP)
                .append(keyId).append(SEP)
                .append(entity);
        for (String value : values) {
            sb.append(SEP).append(value);
        }
        return sb.toString();
    }

    private static List<String> split(String payload) {
        List<String> fields = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < payload.length(); i++) {
            if (payload.charAt(i) == SEP) {
                fields.add(payload.substring(start, i));
                start = i + 1;
            }
        }
        fields.add(payload.substring(start));
        return fields;
    }

    private byte[] secretFor(String keyId) {
        if (keyId == null) {
            return null;
        }
        for (CursorProperties.Key key : properties.keys()) {
            if (keyId.equals(key.id())) {
                return key.secret() == null ? new byte[0] : key.secret().getBytes(StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static byte[] hmac(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.length == 0 ? new byte[1] : secret, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("failed to compute cursor HMAC", ex);
        }
    }
}
