package com.epam.aidial.analytics.configuration;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Typed binding for the retry sweep of the failure ledger ({@code sync.retry.*}). Values are env-driven via
 * {@code application.yml}; the behaviour they tune is specified by {@code openspec/specs/data-sync-failures}.
 *
 * @param enabled        whether the sweep attempts due failures; failures are recorded and listed either way
 * @param cron           Spring cron expression controlling how often a sweep runs
 * @param initialBackoff the wait after a subject's first transient failure, doubled after each further one
 * @param maxBackoff     the cap on that wait
 * @param maxAttempts    the transient failures after which a subject is parked
 * @param maxPerSweep    the most subjects one sweep attempts
 */
@Validated
@ConfigurationProperties(prefix = "sync.retry")
public record SyncRetryProperties(
        boolean enabled,
        String cron,
        Duration initialBackoff,
        Duration maxBackoff,
        @Min(1) int maxAttempts,
        @Min(1) int maxPerSweep) {

    public SyncRetryProperties {
        if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("sync.retry.initial-backoff must be positive: " + initialBackoff);
        }
        if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("sync.retry.max-backoff must not be below sync.retry.initial-backoff: " + maxBackoff);
        }
    }

    /**
     * The wait before the attempt that follows the {@code attempts}-th transient failure:
     * {@code initialBackoff × 2^(attempts − 1)}, capped at {@link #maxBackoff()}.
     *
     * @param attempts the transient failures so far, at least 1
     * @return the backoff
     */
    public Duration backoffAfter(int attempts) {
        Duration backoff = initialBackoff;
        for (int i = 1; i < attempts && backoff.compareTo(maxBackoff) < 0; i++) {
            backoff = backoff.multipliedBy(2);
        }
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }
}
