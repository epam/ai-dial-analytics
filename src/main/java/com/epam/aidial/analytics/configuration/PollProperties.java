package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed binding for the near-real-time poller ({@code poll.*}). Values are env-driven via
 * {@code application.yml}. The poller lists the active date-derived prefix(es) on a short schedule and
 * ingests blobs whose last-modified time is at or after a moving watermark less {@link #overlap()}.
 * It needs no cloud-specific notification service or external infrastructure, so it is enabled by
 * default; when disabled, ingestion proceeds only via the scheduled reconciliation run ({@code sync.*}).
 *
 * <p><strong>Listing mode.</strong> In {@code band} mode (default) each cycle lists only a
 * <em>recent time slice</em> of the day, so its listing cost does not grow with the full day's blob
 * count; the {@code day} fallback lists the whole active day prefix(es). The slice is expressed as
 * a key prefix: because the blob key embeds a fixed-width epoch-seconds <em>start</em> timestamp right
 * after the date, a key prefix is a numeric time-range filter the store applies server-side. The
 * watermark, however, tracks each blob's last-modified (<em>finalization</em>) time, which can lag the
 * start time by the request/stream duration — so the slice reaches back by {@link #finalizationLag()}
 * below the cutoff to guarantee it still lists every blob whose last-modified time is at or after the
 * cutoff. A blob finalizing later than {@link #finalizationLag()} may be missed by {@code band}-mode
 * polling and is recovered by the daily reconciliation run.
 *
 * @param enabled          whether the poller runs
 * @param pollCron         Spring cron expression controlling how often a poll cycle runs
 * @param overlap          how far below the watermark to keep re-considering blobs, absorbing
 *                         out-of-order writes and clock skew (duplicates collapse in the table engine)
 * @param restartLookback  how far back to set the watermark on startup, so a restart re-scans a
 *                         bounded recent window rather than from the beginning of the day
 * @param previousDayGrace how long after UTC midnight to also scan the previous day's prefix in
 *                         {@code day} mode, so blobs finalized just before the date rollover are still
 *                         discovered ({@code band} mode crosses midnight via {@link #finalizationLag()}
 *                         instead and ignores this value)
 * @param finalizationLag  ({@code band} mode) the maximum start→finalization divergence to tolerate;
 *                         the listed slice reaches back this far below the cutoff so no fresh blob is
 *                         missed. Set it at or above the platform's longest request/stream duration
 * @param listingMode      {@code band} (default; list only a recent time slice derived from the key's
 *                         embedded start epoch) or {@code day} (fallback; list the whole active day)
 */
@ConfigurationProperties(prefix = "poll")
public record PollProperties(
        boolean enabled,
        String pollCron,
        Duration overlap,
        Duration restartLookback,
        Duration previousDayGrace,
        Duration finalizationLag,
        String listingMode) {
}
