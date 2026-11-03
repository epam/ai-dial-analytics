package com.epam.aidial.analytics.service;

import java.time.LocalDate;
import java.util.Set;

/**
 * Selects outstanding failure-ledger subjects; every member is optional and present members are ANDed.
 *
 * @param kind     blob, day or records
 * @param state    pending or parked
 * @param reason   the reason code of the latest failure
 * @param from     inclusive lower bound on the subject's day
 * @param to       inclusive upper bound on the subject's day
 * @param subjects blob keys or ISO dates
 */
public record IngestFailureFilter(
        IngestFailureKind kind,
        IngestFailureState state,
        IngestFailureReason reason,
        LocalDate from,
        LocalDate to,
        Set<String> subjects) {

    /** Selects every outstanding subject. */
    public static final IngestFailureFilter ALL = new IngestFailureFilter(null, null, null, null, null, null);

    public IngestFailureFilter {
        subjects = subjects == null ? null : Set.copyOf(subjects);
    }

    /** Whether {@code entry} is selected. */
    public boolean matches(IngestFailureEntry entry) {
        OpenIngestFailure failure = entry.failure();
        return (kind == null || kind == failure.kind())
                && (state == null || state == entry.state())
                && (reason == null || reason.wire().equals(failure.reason()))
                && (from == null || !failure.day().isBefore(from))
                && (to == null || !failure.day().isAfter(to))
                && (subjects == null || subjects.contains(failure.subject()));
    }
}
