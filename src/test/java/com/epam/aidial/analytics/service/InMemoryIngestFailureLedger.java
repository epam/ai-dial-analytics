package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.configuration.SyncRetryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An {@link IngestFailureLedger} held in memory for unit tests, deriving {@link #open()} from its events the way the
 * ClickHouse aggregation does. {@link #failAppends} makes the next writes throw, standing in for ClickHouse being
 * unavailable.
 */
class InMemoryIngestFailureLedger implements IngestFailureLedger {

    /** Retry settings for tests: 15m doubling to 24h, parked after 10 attempts, 20 per sweep. */
    static final SyncRetryProperties RETRY = new SyncRetryProperties(true, "-", Duration.ofMinutes(15), Duration.ofHours(24), 10, 20);

    final List<IngestFailureEvent> events = new ArrayList<>();
    boolean failAppends;
    /** Successful appends, one per insert ClickHouse would receive. */
    int appends;

    /** A failure service over this ledger and {@code ingestLedger}, with the test retry settings. */
    IngestFailureService service(IngestLedger ingestLedger) {
        return service(ingestLedger, new SimpleMeterRegistry(), Clock.systemUTC());
    }

    IngestFailureService service(IngestLedger ingestLedger, MeterRegistry registry, Clock clock) {
        return new IngestFailureService(this, ingestLedger, RETRY, registry, clock);
    }

    @Override
    public void append(Collection<IngestFailureEvent> appended) {
        if (failAppends) {
            throw new IllegalStateException("failure-ledger write failed");
        }
        events.addAll(appended);
        appends++;
    }

    @Override
    public List<OpenIngestFailure> open() {
        Map<String, List<IngestFailureEvent>> bySubject = new LinkedHashMap<>();
        for (IngestFailureEvent event : events) {
            bySubject.computeIfAbsent(event.kind() + "|" + event.subject(), ignored -> new ArrayList<>()).add(event);
        }
        List<OpenIngestFailure> open = new ArrayList<>();
        for (List<IngestFailureEvent> history : bySubject.values()) {
            List<IngestFailureEvent> sorted = history.stream().sorted(Comparator.comparing(IngestFailureEvent::eventAt)).toList();
            List<IngestFailureEvent> failed = sorted.stream().filter(event -> event.type() == IngestFailureEvent.Type.FAILED).toList();
            if (failed.isEmpty() || sorted.getLast().type() == IngestFailureEvent.Type.RESOLVED) {
                continue;
            }
            Instant resetAt = sorted.stream().filter(event -> event.type() != IngestFailureEvent.Type.FAILED)
                    .map(IngestFailureEvent::eventAt).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
            IngestFailureEvent latest = failed.getLast();
            open.add(new OpenIngestFailure(latest.kind(), latest.subject(), latest.day(), latest.blobLastModified(), resetAt,
                    (int) failed.stream().filter(event -> event.eventAt().isAfter(resetAt)).count(),
                    failed.getFirst().eventAt(), latest.eventAt(), latest.reason().failureClass(), latest.reason().wire(),
                    latest.message(), latest.path(), retryMode(sorted), backfillRuns(sorted)));
        }
        return open;
    }

    @Override
    public Optional<Latest> latestOf(IngestFailureKind kind, String subject) {
        List<IngestFailureEvent> sorted = events.stream()
                .filter(event -> event.kind() == kind && event.subject().equals(subject))
                .sorted(Comparator.comparing(IngestFailureEvent::eventAt)).toList();
        return sorted.isEmpty() ? Optional.empty() : Optional.of(new Latest(sorted.getLast().type(), retryMode(sorted)));
    }

    /** {@code ingest_all} when a failure since the latest {@code resolved} recorded it, else the latest failure's mode. */
    private static String retryMode(List<IngestFailureEvent> sorted) {
        Instant resolvedAt = sorted.stream().filter(event -> event.type() == IngestFailureEvent.Type.RESOLVED)
                .map(IngestFailureEvent::eventAt).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        List<IngestFailureEvent> failed = sorted.stream().filter(event -> event.type() == IngestFailureEvent.Type.FAILED).toList();
        String ingestAll = IngestLedger.Mode.INGEST_ALL.wire();
        if (failed.stream().anyMatch(event -> event.eventAt().isAfter(resolvedAt) && ingestAll.equals(event.mode()))) {
            return ingestAll;
        }
        return failed.isEmpty() ? "" : failed.getLast().mode();
    }

    /** The backfill runs whose failures came after the latest {@code resolved}, sorted. */
    private static List<String> backfillRuns(List<IngestFailureEvent> sorted) {
        Instant resolvedAt = sorted.stream().filter(event -> event.type() == IngestFailureEvent.Type.RESOLVED)
                .map(IngestFailureEvent::eventAt).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        return sorted.stream()
                .filter(event -> event.type() == IngestFailureEvent.Type.FAILED && event.eventAt().isAfter(resolvedAt) && !event.backfillRunId().isEmpty())
                .map(IngestFailureEvent::backfillRunId).distinct().sorted().toList();
    }

    /** The events of one subject, in writing order. */
    List<IngestFailureEvent> of(IngestFailureKind kind, String subject) {
        return events.stream().filter(event -> event.kind() == kind && event.subject().equals(subject)).toList();
    }
}
