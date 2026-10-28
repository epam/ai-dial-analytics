package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the payload table's retention ({@code sync.payload.*}), env-overridable via
 * {@code application.yml}.
 *
 * <p>The payload table's {@code TTL} is provisioned by a migration as 90 days. A set {@code ttlDays} is applied to
 * the live table at every start (see {@code IngestTtlReconciler}, which also bounds it by the usage log's
 * retention); unset leaves the live {@code TTL} as it is, so removing the property never alters the table.
 * {@code -1} removes the {@code TTL}; {@code 0} is rejected rather than given a meaning, because on a setting that
 * deletes data it reads as "keep zero days".
 *
 * <p>{@code table} names the table to reconcile. It reads the same {@code SYNC_PAYLOAD_TABLE} variable as the
 * payload ingest target ({@code sync.targets[1].table}), whose list position carries no role code could rely on.
 *
 * @param ttlDays the payload retention in days, {@code 1} or more, or {@link #NO_TTL}; {@code null} (unset) means
 *                the live {@code TTL} is left alone
 * @param table   the payload table; {@code null} or blank (absent) means {@link #DEFAULT_TABLE}
 */
@ConfigurationProperties(prefix = "sync.payload")
public record PayloadRetentionProperties(Integer ttlDays, String table) {

    /** The {@code ttlDays} value that removes the payload table's {@code TTL}. */
    public static final int NO_TTL = -1;
    public static final String DEFAULT_TABLE = "dial_usage_log_payload";

    public PayloadRetentionProperties {
        if (ttlDays != null && ttlDays < 1 && ttlDays != NO_TTL) {
            throw new IllegalArgumentException(
                    "sync.payload.ttl-days must be 1 or more, or " + NO_TTL + " for no TTL: " + ttlDays);
        }
        table = table == null || table.isBlank() ? DEFAULT_TABLE : table.trim();
    }

    /** Whether the property is unset, so the payload table keeps its live {@code TTL}. */
    public boolean unset() {
        return ttlDays == null;
    }

    /** Whether the payload table is to carry no {@code TTL}. */
    public boolean noTtl() {
        return ttlDays != null && ttlDays == NO_TTL;
    }
}
