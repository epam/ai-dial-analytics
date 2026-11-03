package com.epam.aidial.analytics.service;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A failure-ledger subject whose latest event is not a resolution, aggregated from its events. Whether it is
 * pending or parked, and when it is due, is derived from this and the retry settings
 * ({@link IngestFailureService#derive}); a blob is further resolved by the ingest ledger.
 *
 * @param kind             blob, day or records
 * @param subject          the blob key or the ISO date
 * @param day              the day of the latest failure
 * @param blobLastModified the blob's last-modified time at its latest failure; {@link Instant#EPOCH} for a day
 * @param resetAt          the latest requeue or resolution; {@link Instant#EPOCH} when there is none
 * @param attempts         failures after {@code resetAt}
 * @param firstFailedAt    the earliest failure
 * @param lastFailedAt     the latest failure
 * @param failureClass     the class of the latest failure
 * @param reason           the reason code of the latest failure
 * @param message          the message of the latest failure
 * @param path             the path of the latest failure
 */
public record OpenIngestFailure(
        IngestFailureKind kind,
        String subject,
        LocalDate day,
        Instant blobLastModified,
        Instant resetAt,
        int attempts,
        Instant firstFailedAt,
        Instant lastFailedAt,
        String failureClass,
        String reason,
        String message,
        String path) {
}
