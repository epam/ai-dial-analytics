package com.epam.aidial.analytics.sync.delta;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/**
 * The prefix hashes that identify a request's place in a chain: {@code p0 = H(scope, day)} and
 * {@code pk = H(p(k-1), canonical(hk))}. Each hash folds in every history element before it and the scope, so two
 * requests share {@code pk} only when their first {@code k} elements are identical and they share project, user and UTC day.
 * {@code H} is SHA-256 truncated to 128 bits and carried as a {@link UUID}, the type the chain columns store. Element
 * text is hashed as its UTF-16 code units rather than encoded to UTF-8 first, because the UTF-8 encoder replaces a lone
 * surrogate with {@code ?}: two different elements would hash alike, and one could link to the other's history.
 */
final class PathHash {

    /** Seeds every chain; changing it re-roots every conversation once, and breaks nothing. */
    private static final byte[] SEED = "adas-request-delta-v1".getBytes(StandardCharsets.UTF_8);

    private PathHash() {
    }

    /**
     * The prefix hashes {@code p0..pn} of a history.
     *
     * @param scope   the row's chain scope (project and user)
     * @param day     the row's UTC day ({@code yyyy-MM-dd})
     * @param history the canonical history elements
     * @return {@code n + 1} hashes; element {@code k} covers the first {@code k} history elements
     */
    static UUID[] prefixes(String scope, String day, List<String> history) {
        UUID[] prefixes = new UUID[history.size() + 1];
        MessageDigest digest = sha256();
        digest.update(SEED);
        digest.update((byte) 0);
        updateChars(digest, scope);
        digest.update((byte) 0);
        digest.update(day.getBytes(StandardCharsets.UTF_8));
        prefixes[0] = truncate(digest.digest());
        for (int k = 1; k <= history.size(); k++) {
            digest.update(bytes(prefixes[k - 1]));
            updateChars(digest, history.get(k - 1));
            prefixes[k] = truncate(digest.digest());
        }
        return prefixes;
    }

    /**
     * The hash of a canonical top-level value, such as a request's {@code tools}: what a child compares with its parent
     * to inherit the value, and what a rebuild looks the stored value up by.
     *
     * @param key  the top-level key the value sits under
     * @param text the value as canonical JSON
     */
    static UUID value(String key, String text) {
        MessageDigest digest = sha256();
        digest.update(SEED);
        digest.update((byte) 1);
        updateChars(digest, key);
        digest.update((byte) 0);
        updateChars(digest, text);
        return truncate(digest.digest());
    }

    /** Feeds every UTF-16 code unit of {@code text}, big-endian, lone surrogates included. */
    private static void updateChars(MessageDigest digest, String text) {
        byte[] units = new byte[text.length() * 2];
        for (int i = 0; i < text.length(); i++) {
            char unit = text.charAt(i);
            units[2 * i] = (byte) (unit >>> 8);
            units[2 * i + 1] = (byte) unit;
        }
        digest.update(units);
    }

    private static UUID truncate(byte[] hash) {
        ByteBuffer buffer = ByteBuffer.wrap(hash);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", ex);
        }
    }
}
