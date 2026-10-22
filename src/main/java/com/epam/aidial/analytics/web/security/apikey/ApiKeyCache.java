package com.epam.aidial.analytics.web.security.apikey;

import com.epam.aidial.analytics.web.security.ConditionalOnSecurityMode;
import com.epam.aidial.analytics.web.security.SecurityMode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Caches successful API-key introspections so a valid key hits DIAL Core at most once per TTL.
 * Entries are keyed by a SHA-256 digest of the raw key (never the key itself), bounded by the
 * configured TTL and maximum size. Only successful authentications are cached — the loader propagates
 * any exception, so a rejected key is re-checked against Core on every request.
 */
@Slf4j
@Component
@ConditionalOnSecurityMode(SecurityMode.OIDC)
@ConditionalOnProperty(name = "security.api-key.enabled", havingValue = "true")
public class ApiKeyCache {

    private final Cache<String, Authentication> cache;

    public ApiKeyCache(ApiKeyProperties properties) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.getCacheTtlSeconds()))
                .maximumSize(properties.getCacheMaxSize())
                .build();
        log.debug("Initialized api-key cache: ttl={}s, maxSize={}",
                properties.getCacheTtlSeconds(), properties.getCacheMaxSize());
    }

    public Authentication getOrAuthenticate(String apiKey, Supplier<Authentication> loader) {
        return cache.get(sha256Hex(apiKey), k -> loader.get());
    }

    public long size() {
        return cache.estimatedSize();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a mandated JDK algorithm; its absence is not recoverable.
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
