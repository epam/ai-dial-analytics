package com.epam.aidial.analytics.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * How a log line names a cursor position: {@code (version_ms,#8hex)}.
 *
 * <p>A position is a {@code (version, identity)} tuple read from the source. The version half is the source's
 * version timestamp in epoch milliseconds, a position rather than a value, and is logged as is. The identity half
 * is whatever the source's identity column holds, which the service cannot assume carries no user data, so it is
 * logged only as a short SHA-256 fingerprint. Equal identities give equal fingerprints, so two lines still show
 * whether a bound or a mark moved. Every log line that names a position goes through here, so none prints the
 * identity itself.
 */
public final class CursorPosition {

    private static final String NONE = "-";

    private CursorPosition() {
    }

    /** {@code (version,#fingerprint)}; either half is {@code -} when it is {@code null}. */
    public static String of(Long version, String identity) {
        return "(" + (version == null ? NONE : version) + "," + (identity == null ? NONE : fingerprint(identity)) + ")";
    }

    /** A short, stable digest of a cursor identity: {@code #} and the first four bytes of its SHA-256, in hex. */
    public static String fingerprint(String identity) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return "#" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException ex) {
            // Every JDK ships SHA-256; without it, log nothing about the identity rather than the value.
            return NONE;
        }
    }
}
