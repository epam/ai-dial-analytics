package com.epam.aidial.analytics.service;

import java.time.Instant;

/**
 * An outstanding failure-ledger subject with its derived state.
 *
 * @param failure       the subject's aggregated events
 * @param state         pending or parked
 * @param nextAttemptAt when a pending subject is due; {@code null} for a parked one
 */
public record IngestFailureEntry(OpenIngestFailure failure, IngestFailureState state, Instant nextAttemptAt) {
}
