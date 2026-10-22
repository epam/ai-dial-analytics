package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the operator-triggered date-range backfill ({@code backfill.*}). Values are
 * env-driven via {@code application.yml}. The backfill is a deliberately HTTP-triggered, one-time
 * ingestion of a bounded UTC date range, separate from the recurring scheduled and poller paths.
 *
 * @param enabled whether the backfill endpoints may initiate runs; when {@code false} a trigger is
 *                rejected and no run starts
 * @param maxDays the maximum inclusive span (in days) a single backfill request may cover; guards
 *                against an unbounded run
 */
@ConfigurationProperties(prefix = "backfill")
public record BackfillProperties(
        boolean enabled,
        int maxDays) {
}
