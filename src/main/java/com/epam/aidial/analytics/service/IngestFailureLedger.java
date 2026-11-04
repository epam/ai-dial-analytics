package com.epam.aidial.analytics.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The persisted, append-only log of failed ingest attempts and of what became of them (see the
 * {@code data-sync-failures} spec, "Failed ingests are recorded in a persisted failure ledger"). Each operation
 * is a ClickHouse statement; a failure throws, and the caller decides whether that fails its cycle or day.
 */
public interface IngestFailureLedger {

    /**
     * Writes events. Each carries its own time, so an event held back while ClickHouse was unavailable keeps the
     * time it happened.
     *
     * @param events the events; a no-op when empty
     */
    void append(Collection<IngestFailureEvent> events);

    /**
     * The subjects whose latest event is not a resolution and which have failed at least once, aggregated. Not
     * yet filtered by the ingest ledger.
     *
     * @return one entry per such subject
     */
    List<OpenIngestFailure> open();

    /**
     * The latest event of one subject.
     *
     * @param kind    blob, day or records
     * @param subject the blob key or ISO date
     * @return the latest event's type, or empty when the subject has none
     */
    default Optional<IngestFailureEvent.Type> latest(IngestFailureKind kind, String subject) {
        return latestOf(kind, subject).map(Latest::type);
    }

    /**
     * The latest event of one subject, with the ledger mode of its latest failure.
     *
     * @param kind    blob, day or records
     * @param subject the blob key or ISO date
     * @return the latest event, or empty when the subject has none
     */
    Optional<Latest> latestOf(IngestFailureKind kind, String subject);

    /**
     * A subject's latest event.
     *
     * @param type       the latest event's type
     * @param failedMode the ledger mode its latest failure recorded; empty when none
     */
    record Latest(IngestFailureEvent.Type type, String failedMode) {

        /** The ledger mode of the latest failure. */
        public IngestLedger.Mode failedLedgerMode() {
            return IngestLedger.Mode.fromWire(failedMode);
        }
    }
}
