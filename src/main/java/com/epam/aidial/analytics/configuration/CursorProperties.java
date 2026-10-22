package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * HMAC keyring for the opaque scan cursor ({@code enrichment.cursor.*}). Values are env-driven via
 * {@code application.yml}; secrets come from the deployment secret store and are redacted in logs.
 *
 * <p>{@code activeKeyId} selects the key that signs newly issued cursors; every key in {@code keys}
 * can verify a presented cursor, so the secret can rotate (add a new key, switch {@code activeKeyId},
 * retire the old key after outstanding cursors expire) without invalidating cursors in flight. A
 * cursor carrying an unknown/retired {@code key-id} is rejected.
 *
 * @param activeKeyId id of the key used to sign new cursors
 * @param keys        the verification keyring (must contain {@code activeKeyId})
 */
@Validated
@ConfigurationProperties(prefix = "enrichment.cursor")
public record CursorProperties(String activeKeyId, List<Key> keys) {

    public CursorProperties {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    /**
     * One keyring entry.
     *
     * @param id     stable key identifier embedded in the cursor
     * @param secret HMAC secret (redacted in {@code toString})
     */
    public record Key(String id, String secret) {
        @Override
        public String toString() {
            return "Key[id=" + id + ", secret=***]";
        }
    }
}
