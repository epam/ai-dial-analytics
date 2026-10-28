package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the usage log's retention ({@code sync.usage-log.*}), env-overridable via
 * {@code application.yml}.
 *
 * <p>The usage log's {@code TTL} is provisioned by a migration as one year and then reconciled at every start (see
 * {@code IngestTtlReconciler}). Unset keeps that year rather than defaulting to a number of days: the server
 * renders it {@code toIntervalYear(1)}, which no day count equals, so a day default would alter every existing
 * table on upgrade. {@code -1} removes the {@code TTL}; {@code 0} is rejected, as for
 * {@link PayloadRetentionProperties}.
 *
 * <p>{@code table} reads the same {@code SYNC_TABLE} variable as the usage-log ingest target
 * ({@code sync.targets[0].table}).
 *
 * @param ttlDays the usage-log retention in days, {@code 1} or more, or {@link #NO_TTL}; {@code null} (unset) means
 *                the provisioned year
 * @param table   the usage-log table; {@code null} or blank (absent) means {@link #DEFAULT_TABLE}
 */
@ConfigurationProperties(prefix = "sync.usage-log")
public record UsageLogRetentionProperties(Integer ttlDays, String table) {

    /** The {@code ttlDays} value that removes the usage log's {@code TTL}. */
    public static final int NO_TTL = -1;
    public static final String DEFAULT_TABLE = "dial_usage_log";

    public UsageLogRetentionProperties {
        if (ttlDays != null && ttlDays < 1 && ttlDays != NO_TTL) {
            throw new IllegalArgumentException(
                    "sync.usage-log.ttl-days must be 1 or more, or " + NO_TTL + " for no TTL: " + ttlDays);
        }
        table = table == null || table.isBlank() ? DEFAULT_TABLE : table.trim();
    }

    /** Whether the usage log keeps its provisioned one-year {@code TTL}. */
    public boolean provisionedYear() {
        return ttlDays == null;
    }

    /** Whether the usage log is to carry no {@code TTL}. */
    public boolean noTtl() {
        return ttlDays != null && ttlDays == NO_TTL;
    }
}
