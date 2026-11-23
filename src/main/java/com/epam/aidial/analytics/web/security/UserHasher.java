package com.epam.aidial.analytics.web.security;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Derives a caller's user hash the way DIAL Core derives the {@code user.id} field of its usage log, so the
 * result can be compared with a {@code user_hash} column: the lowercase hex SHA-256 of the salt followed by
 * one claim's value, or the claim value itself when obfuscation is off.
 *
 * <p>A class rather than a record on purpose: the salt is a credential, and a generated {@code toString}
 * would print it.
 */
public final class UserHasher {

    /** Derives nothing: no claim is configured. */
    public static final UserHasher NONE = new UserHasher(null, null, true);

    private final String loggingKey;
    private final String loggingSalt;
    private final boolean obfuscate;

    private UserHasher(String loggingKey, String loggingSalt, boolean obfuscate) {
        this.loggingKey = loggingKey;
        this.loggingSalt = loggingSalt;
        this.obfuscate = obfuscate;
    }

    /**
     * The hasher for one settings group.
     *
     * @param owner the property prefix the settings were read from, for the startup error
     * @throws IllegalStateException if a claim is named with no salt while obfuscation is on
     */
    public static UserHasher of(String owner, String loggingKey, String loggingSalt, Boolean obfuscateUserEmail) {
        if (StringUtils.isBlank(loggingKey)) {
            return NONE;
        }
        final boolean obfuscate = !Boolean.FALSE.equals(obfuscateUserEmail);
        if (obfuscate && StringUtils.isEmpty(loggingSalt)) {
            throw new IllegalStateException(owner + ".logging-key is set but " + owner + ".logging-salt is not; "
                    + "set the salt, or set " + owner + ".obfuscate-user-email=false");
        }
        return new UserHasher(loggingKey.trim(), loggingSalt, obfuscate);
    }

    /** The top-level claim the hash is derived from, or {@code null} when none is configured. */
    public String loggingKey() {
        return loggingKey;
    }

    /**
     * The user hash for a claim value that must be a string, or {@code null} when no claim is configured or
     * the value is missing, not a string, or blank. A blank claim is not hashed: every user with one would
     * share a single hash.
     */
    public String hash(Object claim) {
        if (loggingKey == null || !(claim instanceof String value) || StringUtils.isBlank(value)) {
            return null;
        }
        if (!obfuscate) {
            return value;
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((loggingSalt + value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@link #hash} for a claim that arrives as a list and must hold exactly one string. */
    public String hashSingle(List<String> claim) {
        return claim != null && claim.size() == 1 ? hash(claim.getFirst()) : null;
    }
}
