package com.epam.aidial.analytics.service;

import java.time.LocalDate;
import java.util.Set;

/**
 * Selects outstanding failure-ledger subjects; every member is optional and present members are ANDed.
 *
 * @param kind          blob, day or records
 * @param state         pending or parked
 * @param reason        the reason code of the latest failure
 * @param from          inclusive lower bound on the subject's day
 * @param to            inclusive upper bound on the subject's day
 * @param subjects      blob keys or ISO dates, of any kind
 * @param backfillRunId a backfill run that failed the subject since it was last resolved
 * @param targets       exact subjects, each with its kind
 */
public record IngestFailureFilter(
        IngestFailureKind kind,
        IngestFailureState state,
        IngestFailureReason reason,
        LocalDate from,
        LocalDate to,
        Set<String> subjects,
        String backfillRunId,
        Set<Target> targets) {

    /** Selects every outstanding subject. */
    public static final IngestFailureFilter ALL = new IngestFailureFilter(null, null, null, null, null, null);

    public IngestFailureFilter {
        subjects = subjects == null ? null : Set.copyOf(subjects);
        targets = targets == null ? null : Set.copyOf(targets);
    }

    /** A filter with no backfill run and no targets. */
    public IngestFailureFilter(IngestFailureKind kind, IngestFailureState state, IngestFailureReason reason, LocalDate from, LocalDate to,
            Set<String> subjects) {
        this(kind, state, reason, from, to, subjects, null, null);
    }

    /** This filter with {@code state} in place of its own; {@code null} selects either state. */
    public IngestFailureFilter withState(IngestFailureState state) {
        return new IngestFailureFilter(kind, state, reason, from, to, subjects, backfillRunId, targets);
    }

    /** Whether {@code entry} is selected. */
    public boolean matches(IngestFailureEntry entry) {
        OpenIngestFailure failure = entry.failure();
        return (kind == null || kind == failure.kind())
                && (state == null || state == entry.state())
                && (reason == null || reason.wire().equals(failure.reason()))
                && (from == null || !failure.day().isBefore(from))
                && (to == null || !failure.day().isAfter(to))
                && (subjects == null || subjects.contains(failure.subject()))
                && (backfillRunId == null || failure.backfillRunIds().contains(backfillRunId))
                && (targets == null || targets.contains(new Target(failure.kind(), failure.subject())));
    }

    /**
     * One subject named exactly: a blob's {@code blob} subject and its {@code records} subject share a key, so the key
     * alone does not tell them apart.
     *
     * @param kind    blob, day or records
     * @param subject the blob key or ISO date
     */
    public record Target(IngestFailureKind kind, String subject) {
    }
}
