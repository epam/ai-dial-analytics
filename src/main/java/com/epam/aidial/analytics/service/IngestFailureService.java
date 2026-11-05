package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobListing;
import com.epam.aidial.analytics.configuration.SyncRetryProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records failed ingest attempts in the {@link IngestFailureLedger} and answers what is still outstanding, as the
 * {@code data-sync-failures} spec defines it.
 *
 * <p><b>State.</b> A subject is outstanding while its latest event is not a resolution — and, for a blob, while the
 * {@link IngestLedger} does not record it with a last-modified time at or after the one its latest failure carried,
 * so a blob ingested by any path resolves without a write here. An outstanding subject with no failure since its
 * reset point (an operator requeue) is pending and due at once; one whose latest failure is deterministic, or whose
 * failures since the reset reach {@code sync.retry.max-attempts}, is parked; any other is pending, due after a backoff
 * doubling from {@code sync.retry.initial-backoff} up to {@code sync.retry.max-backoff}. The settings are applied when
 * the state is read, so changing them re-schedules existing entries.
 *
 * <p><b>Recording.</b> A blob failure is written at once and a write failure propagates, so the caller fails its cycle
 * or day exactly as on a failed ingest-ledger write. A day failure usually means ClickHouse is down, so a failed write
 * of it is held in memory (at most {@value #MAX_UNWRITTEN} events, oldest discarded) and written by
 * {@link #drainUnwritten()}, which the retry sweep calls first.
 */
@Service
@Slf4j
public class IngestFailureService {

    /** The most day-failure events held in memory while they cannot be written. */
    static final int MAX_UNWRITTEN = 1000;

    private final IngestFailureLedger failures;
    private final IngestLedger ingestLedger;
    private final SyncRetryProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    private final Deque<IngestFailureEvent> unwritten = new ArrayDeque<>();
    private final AtomicLong unwrittenCount = new AtomicLong();
    private final Map<IngestFailureKind, Map<IngestFailureState, AtomicLong>> outstanding = new EnumMap<>(IngestFailureKind.class);

    @Autowired
    public IngestFailureService(IngestFailureLedger failures, IngestLedger ingestLedger, SyncRetryProperties properties,
            MeterRegistry meterRegistry) {
        this(failures, ingestLedger, properties, meterRegistry, Clock.systemUTC());
    }

    IngestFailureService(IngestFailureLedger failures, IngestLedger ingestLedger, SyncRetryProperties properties,
            MeterRegistry meterRegistry, Clock clock) {
        this.failures = failures;
        this.ingestLedger = ingestLedger;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        Gauge.builder("sync.failures.unwritten", unwrittenCount, AtomicLong::doubleValue).register(meterRegistry);
        for (IngestFailureKind kind : IngestFailureKind.values()) {
            Map<IngestFailureState, AtomicLong> byState = new EnumMap<>(IngestFailureState.class);
            for (IngestFailureState state : IngestFailureState.values()) {
                AtomicLong value = new AtomicLong();
                byState.put(state, value);
                Gauge.builder("sync.failures.outstanding", value, AtomicLong::doubleValue)
                        .tag("kind", kind.wire())
                        .tag("state", state.wire())
                        .register(meterRegistry);
            }
            outstanding.put(kind, byState);
        }
    }

    /**
     * Records a failed attempt on a blob, classified from {@code error}.
     *
     * @throws RuntimeException if the event cannot be written; the caller fails its cycle or day
     */
    public void recordBlobFailure(BlobListing listing, IngestPath path, Throwable error) {
        recordBlobFailures(List.of(blobFailure(listing, path, error)));
    }

    /**
     * Records a failed attempt on a blob-keyed subject — a {@code blob}, or the {@code records} of a blob — under a known
     * reason.
     *
     * @throws RuntimeException if the event cannot be written
     */
    public void record(IngestFailureKind kind, BlobListing listing, IngestPath path, IngestFailureReason reason, String message) {
        recordBlobFailures(List.of(IngestFailureEvent.failed(kind, listing.name(), listing.lastModified(), IngestSource.of(path), reason, message,
                clock.instant())));
    }

    /**
     * Records a failed attempt on a blob-keyed subject, classified from {@code error} as a blob's failure is.
     *
     * @throws RuntimeException if the event cannot be written
     */
    public void record(IngestFailureKind kind, BlobListing listing, IngestPath path, Throwable error) {
        record(kind, listing, path, IngestFailureClassifier.classifyBlob(error), IngestFailureClassifier.describe(error));
    }

    /**
     * The failed-attempt event for a blob, classified from {@code error} and stamped now, for a caller that collects a
     * cycle's or day's failures and writes them in one {@link #recordBlobFailures} — one insert, and so one part, however
     * many blobs fail.
     */
    public IngestFailureEvent blobFailure(BlobListing listing, IngestPath path, Throwable error) {
        return blobFailure(listing, IngestSource.of(path), error);
    }

    /** As {@link #blobFailure(BlobListing, IngestPath, Throwable)}, attributed to {@code source}'s backfill run when it has one. */
    public IngestFailureEvent blobFailure(BlobListing listing, IngestSource source, Throwable error) {
        IngestFailureReason reason = IngestFailureClassifier.classifyBlob(error);
        return IngestFailureEvent.blobFailed(listing.name(), listing.lastModified(), source, reason, IngestFailureClassifier.describe(error), clock.instant());
    }

    /**
     * The failure event for the skipped records of a blob that was otherwise ingested, stamped now.
     *
     * @param skipped what the blob's ingest skipped; must not be empty
     */
    public IngestFailureEvent recordsFailure(BlobListing listing, IngestSource source, SkippedRecords skipped) {
        return IngestFailureEvent.recordsFailed(listing.name(), listing.lastModified(), source, skipped.describe(), clock.instant());
    }

    /**
     * The failure event for a blob whose rows ClickHouse refused with a data-class error, stamped now.
     */
    public IngestFailureEvent refusedBlobFailure(BlobListing listing, IngestSource source, RefusedBlob refused) {
        return IngestFailureEvent.blobFailed(listing.name(), listing.lastModified(), source, IngestFailureReason.REJECTED_BY_SERVER,
                refused.describe(), clock.instant());
    }

    /**
     * The failure events a sink hand-over calls for: a {@code records} failure for each written blob whose read skipped
     * something, and a {@code rejected_by_server} failure for each refused blob. The caller writes them with
     * {@link #recordBlobFailures} before recording the written blobs in the ingest ledger.
     */
    List<IngestFailureEvent> handOverFailures(RunSink.HandOver handOver, IngestSource source) {
        List<IngestFailureEvent> events = new ArrayList<>(handOver.skipped().size() + handOver.refused().size());
        handOver.skipped().forEach((listing, skips) -> events.add(recordsFailure(listing, source, skips)));
        for (RefusedBlob refused : handOver.refused()) {
            events.add(refusedBlobFailure(refused.listing(), source, refused));
        }
        return events;
    }

    /**
     * Records a sink hand-over: its failures first, in one append, then its written blobs in {@code ingestLedger}. So a
     * blob recorded as ingested always has its skipped records on record, and a failure to write them leaves the blob
     * unrecorded.
     *
     * @throws RuntimeException if either write fails; the sink turns it into a fault of the run
     */
    void recordHandOver(RunSink.HandOver handOver, IngestPath path) {
        recordHandOver(handOver, IngestSource.of(path));
    }

    /** As {@link #recordHandOver(RunSink.HandOver, IngestPath)}, attributed to {@code source}'s backfill run when it has one. */
    void recordHandOver(RunSink.HandOver handOver, IngestSource source) {
        recordBlobFailures(handOverFailures(handOver, source));
        if (!handOver.written().isEmpty()) {
            ingestLedger.record(handOver.written());
        }
    }

    /**
     * Writes blob, {@code records} and refused-blob failure events in one append.
     *
     * @throws RuntimeException if they cannot be written; the caller fails its cycle or day
     */
    public void recordBlobFailures(Collection<IngestFailureEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        failures.append(events);
        for (IngestFailureEvent event : events) {
            counted(event.kind(), event.path(), event.reason());
            log.warn("Recorded {} failure of {} {} on the {} path: {}", event.reason().wire(), event.kind().wire(), event.subject(), event.path(),
                    event.message());
        }
    }

    /**
     * Records a failed run of a day, with the ledger mode it ran in, so its retry runs the same way. A later failure of a
     * skipping run does not lower an {@code ingest_all} day: the ledger reads the mode across every failure since the day
     * was last resolved. Never throws: an event that cannot be written is held in memory for the next sweep, with its
     * original time.
     */
    public void recordDayFailure(LocalDate day, IngestPath path, IngestLedger.Mode mode, IngestFailureReason reason, Throwable error) {
        recordDayFailure(day, IngestSource.of(path), mode, reason, error);
    }

    /** As {@link #recordDayFailure(LocalDate, IngestPath, IngestLedger.Mode, IngestFailureReason, Throwable)}, attributed to {@code source}'s
     * backfill run when it has one. */
    public void recordDayFailure(LocalDate day, IngestSource source, IngestLedger.Mode mode, IngestFailureReason reason, Throwable error) {
        String path = source.path().wire();
        IngestFailureEvent event = IngestFailureEvent.dayFailed(day, source, mode, reason, IngestFailureClassifier.describe(error), clock.instant());
        counted(IngestFailureKind.DAY, path, reason);
        try {
            failures.append(List.of(event));
            log.warn("Recorded {} failure of day {} on the {} path", reason.wire(), day, path);
        } catch (RuntimeException ex) {
            hold(event, ex);
        }
    }

    /**
     * Writes {@code resolved} for a day that has an unresolved day failure, after a run of it in {@code mode} completed
     * without a day-level fault. A run that skipped recorded blobs does not resolve a failure of a run that ingested every
     * blob: the blobs after that failure's fault are recorded from an earlier ingest, so it skipped them, and they still
     * hold their rows from before. Never throws: a day left unresolved is retried by the sweep, which resolves it then.
     */
    public void resolveDayIfOpen(LocalDate day, IngestPath path, IngestLedger.Mode mode) {
        try {
            IngestFailureLedger.Latest latest = failures.latestOf(IngestFailureKind.DAY, day.toString()).orElse(null);
            if (latest == null || latest.type() == IngestFailureEvent.Type.RESOLVED) {
                return;
            }
            if (mode == IngestLedger.Mode.SKIP_RECORDED && latest.failedLedgerMode() == IngestLedger.Mode.INGEST_ALL) {
                log.info("Left the failure of day {} open: it failed while ingesting every blob, and the {} run skipped recorded ones", day,
                        path.wire());
                return;
            }
            failures.append(List.of(IngestFailureEvent.resolved(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, path.wire(), clock.instant())));
            log.info("Resolved the failure of day {} on the {} path", day, path.wire());
        } catch (RuntimeException ex) {
            log.warn("Could not resolve the failure of day {}; a later retry of it resolves it: {}", day, ex.getMessage());
        }
    }

    /**
     * Writes {@code resolved} for blobs a retry ingested or found already ingested.
     *
     * @throws RuntimeException if the events cannot be written
     */
    public void resolveBlobs(Collection<BlobListing> listings, IngestPath path) {
        resolve(IngestFailureKind.BLOB, listings, path);
    }

    /**
     * Writes {@code resolved} for blob-keyed subjects of {@code kind}: blobs a retry ingested, or the {@code records} of
     * blobs a retry ingested with nothing skipped.
     *
     * @throws RuntimeException if the events cannot be written
     */
    public void resolve(IngestFailureKind kind, Collection<BlobListing> listings, IngestPath path) {
        if (listings.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<IngestFailureEvent> events = new ArrayList<>(listings.size());
        for (BlobListing listing : listings) {
            events.add(IngestFailureEvent.resolved(kind, listing.name(),
                    IngestFailureEvent.blobDay(listing.lastModified(), now), listing.lastModified(), path.wire(), now));
        }
        failures.append(events);
    }

    /**
     * Writes the day-failure events held in memory. Never throws: what cannot be written stays held.
     *
     * @return how many were written
     */
    public int drainUnwritten() {
        List<IngestFailureEvent> batch;
        synchronized (unwritten) {
            if (unwritten.isEmpty()) {
                return 0;
            }
            batch = List.copyOf(unwritten);
        }
        try {
            failures.append(batch);
        } catch (RuntimeException ex) {
            log.warn("{} held failure event(s) still cannot be written: {}", batch.size(), ex.getMessage());
            return 0;
        }
        synchronized (unwritten) {
            // Only the written ones go: a day failure held while the drain ran stays for the next one.
            batch.forEach(unwritten::remove);
            unwrittenCount.set(unwritten.size());
        }
        log.info("Wrote {} failure event(s) held while ClickHouse was unavailable", batch.size());
        return batch.size();
    }

    /** The day-failure events held in memory. */
    public int unwrittenCount() {
        return (int) unwrittenCount.get();
    }

    /**
     * Every outstanding subject with its derived state, newest latest failure first.
     *
     * @throws RuntimeException if the ledgers cannot be read
     */
    public List<IngestFailureEntry> outstanding() {
        return snapshot().outstanding();
    }

    /**
     * Reads the ledgers once: the outstanding subjects, and the open blobs the ingest ledger has resolved since they
     * failed — those have no {@code resolved} event yet, and {@link #resolveIngested} writes one so the open set stays
     * the set of what is actually missing rather than growing with every blob that recovered on its own.
     *
     * @throws RuntimeException if the ledgers cannot be read
     */
    public Snapshot snapshot() {
        List<OpenIngestFailure> open = failures.open();
        // Only a blob subject resolves through the ingest ledger: the very ingest that skipped a blob's records also records
        // the blob there, so a records subject would resolve the moment it was written.
        List<String> blobKeys = open.stream().filter(failure -> failure.kind() == IngestFailureKind.BLOB).map(OpenIngestFailure::subject).toList();
        Map<String, IngestLedger.Recorded> ingested = ingestLedger.recordedAmong(blobKeys);
        List<IngestFailureEntry> entries = new ArrayList<>(open.size());
        List<OpenIngestFailure> resolvedByLedger = new ArrayList<>();
        for (OpenIngestFailure failure : open) {
            if (failure.kind() == IngestFailureKind.BLOB && ingestedSince(ingested.get(failure.subject()), failure)) {
                resolvedByLedger.add(failure);
                continue;
            }
            entries.add(derive(failure, properties));
        }
        entries.sort(Comparator.comparing((IngestFailureEntry entry) -> entry.failure().lastFailedAt()).reversed()
                .thenComparing(entry -> entry.failure().kind())
                .thenComparing(entry -> entry.failure().subject()));
        return new Snapshot(List.copyOf(entries), List.copyOf(resolvedByLedger));
    }

    /**
     * Writes {@code resolved} for the open blobs {@code snapshot} found resolved by the ingest ledger. Never throws: a
     * blob left without its event is still resolved, and the next sweep writes it.
     *
     * @return how many were written
     */
    public int resolveIngested(Snapshot snapshot) {
        if (snapshot.resolvedByLedger().isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        List<IngestFailureEvent> events = snapshot.resolvedByLedger().stream()
                .map(failure -> IngestFailureEvent.resolved(failure.kind(), failure.subject(), failure.day(), failure.blobLastModified(),
                        IngestPath.RETRY.wire(), now))
                .toList();
        try {
            failures.append(events);
            return events.size();
        } catch (RuntimeException ex) {
            log.warn("Could not write {} resolution(s) of blobs ingested since they failed: {}", events.size(), ex.getMessage());
            return 0;
        }
    }

    /**
     * The outstanding subjects {@code filter} selects, newest latest failure first, at most {@code limit} of them, with
     * how many matched in all and per state. The per-state counts disregard {@code filter}'s state, so one read
     * answers both how many wait for an operator and how many are retried automatically.
     *
     * @return the page, whether more matched, and the totals
     */
    public Page list(IngestFailureFilter filter, int limit) {
        List<IngestFailureEntry> outstanding = outstanding();
        IngestFailureFilter anyState = filter.withState(null);
        Map<IngestFailureState, Integer> byState = new EnumMap<>(IngestFailureState.class);
        for (IngestFailureState state : IngestFailureState.values()) {
            byState.put(state, 0);
        }
        List<IngestFailureEntry> matched = new ArrayList<>();
        for (IngestFailureEntry entry : outstanding) {
            if (anyState.matches(entry)) {
                byState.merge(entry.state(), 1, Integer::sum);
            }
            if (filter.matches(entry)) {
                matched.add(entry);
            }
        }
        return new Page(matched.size() > limit ? List.copyOf(matched.subList(0, limit)) : List.copyOf(matched), matched.size() > limit,
                matched.size(), Map.copyOf(byState));
    }

    /**
     * Whether the retry sweep attempts due subjects, and when its next run starts. A requeue is honoured either way,
     * but with the sweep off a requeued subject stays pending until it is turned on.
     */
    public RetrySweepStatus retrySweepStatus() {
        boolean scheduled = !Scheduled.CRON_DISABLED.equals(properties.cron());
        Instant next = null;
        if (properties.enabled() && scheduled) {
            // @Scheduled evaluates a cron in the server's default time zone, so the next run is computed the same way.
            ZonedDateTime fire = CronExpression.parse(properties.cron()).next(clock.instant().atZone(ZoneId.systemDefault()));
            next = fire == null ? null : fire.toInstant();
        }
        return new RetrySweepStatus(properties.enabled() && next != null, next, properties.maxPerSweep());
    }

    /**
     * Writes {@code requeued} for every outstanding subject {@code filter} selects, so each is pending with no
     * attempts and due at once.
     *
     * @return how many were requeued
     */
    public int requeue(IngestFailureFilter filter) {
        Instant now = clock.instant();
        List<IngestFailureEvent> events = outstanding().stream()
                .filter(filter::matches)
                .map(IngestFailureEntry::failure)
                .map(failure -> IngestFailureEvent.requeued(failure.kind(), failure.subject(), failure.day(), failure.blobLastModified(), now))
                .toList();
        failures.append(events);
        log.info("Requeued {} failure(s)", events.size());
        return events.size();
    }

    /**
     * The pending subjects whose next attempt is due, oldest latest failure first, at most {@code limit}.
     */
    public List<IngestFailureEntry> due(int limit) {
        return due(snapshot(), limit);
    }

    /** As {@link #due(int)}, over a snapshot already read. */
    public List<IngestFailureEntry> due(Snapshot snapshot, int limit) {
        Instant now = clock.instant();
        return snapshot.outstanding().stream()
                .filter(entry -> entry.state() == IngestFailureState.PENDING && !entry.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing((IngestFailureEntry entry) -> entry.failure().lastFailedAt()))
                .limit(limit)
                .toList();
    }

    /**
     * Refreshes the {@code sync.failures.outstanding} gauges from the ledgers. Never throws: the gauges keep their
     * last values when the ledgers cannot be read.
     */
    public void refreshGauges() {
        Snapshot snapshot;
        try {
            snapshot = snapshot();
        } catch (RuntimeException ex) {
            log.warn("Could not refresh the outstanding-failure gauges: {}", ex.getMessage());
            return;
        }
        refreshGauges(snapshot);
    }

    /** Refreshes the {@code sync.failures.outstanding} gauges from a snapshot already read. */
    public void refreshGauges(Snapshot snapshot) {
        List<IngestFailureEntry> entries = snapshot.outstanding();
        Map<IngestFailureKind, Map<IngestFailureState, Long>> counts = new EnumMap<>(IngestFailureKind.class);
        for (IngestFailureEntry entry : entries) {
            counts.computeIfAbsent(entry.failure().kind(), ignored -> new EnumMap<>(IngestFailureState.class))
                    .merge(entry.state(), 1L, Long::sum);
        }
        outstanding.forEach((kind, byState) -> byState.forEach((state, value) ->
                value.set(counts.getOrDefault(kind, Map.of()).getOrDefault(state, 0L))));
    }

    /**
     * Derives a subject's state and next attempt from its events and the retry settings.
     */
    static IngestFailureEntry derive(OpenIngestFailure failure, SyncRetryProperties properties) {
        if (failure.attempts() == 0) {
            return new IngestFailureEntry(failure, IngestFailureState.PENDING, failure.resetAt());
        }
        if (IngestFailureReason.DETERMINISTIC.equals(failure.failureClass()) || failure.attempts() >= properties.maxAttempts()) {
            return new IngestFailureEntry(failure, IngestFailureState.PARKED, null);
        }
        return new IngestFailureEntry(failure, IngestFailureState.PENDING,
                failure.lastFailedAt().plus(properties.backoffAfter(failure.attempts())));
    }

    /**
     * Whether the ingest ledger recorded the blob after its latest failure, at its failed version or a later one. Both
     * halves matter: an entry written before the failure — the blob ingested once, then failing again in a backfill that
     * includes ingested blobs — says nothing about the failed attempt.
     */
    private static boolean ingestedSince(IngestLedger.Recorded recorded, OpenIngestFailure failure) {
        return recorded != null
                && !recorded.lastModified().isBefore(failure.blobLastModified())
                && recorded.ingestedAt().isAfter(failure.lastFailedAt());
    }

    private void counted(IngestFailureKind kind, String path, IngestFailureReason reason) {
        meterRegistry.counter("sync.failures.recorded", "kind", kind.wire(), "path", path, "reason", reason.wire()).increment();
    }

    private void hold(IngestFailureEvent event, RuntimeException cause) {
        synchronized (unwritten) {
            if (unwritten.size() >= MAX_UNWRITTEN) {
                IngestFailureEvent discarded = unwritten.removeFirst();
                log.error("Discarded the held failure of {} {}: more than {} failure events are waiting to be written",
                        discarded.kind().wire(), discarded.subject(), MAX_UNWRITTEN);
            }
            unwritten.addLast(event);
            unwrittenCount.set(unwritten.size());
        }
        log.error("Could not record the {} failure of day {}; holding it in memory until ClickHouse accepts it. "
                + "It is lost if the service restarts first: {}", event.reason().wire(), event.subject(), cause.getMessage());
    }

    /**
     * One read of the ledgers.
     *
     * @param outstanding      the outstanding subjects, newest latest failure first
     * @param resolvedByLedger open blobs the ingest ledger has recorded since they failed, still without a resolved event
     */
    public record Snapshot(List<IngestFailureEntry> outstanding, List<OpenIngestFailure> resolvedByLedger) {
    }

    /**
     * One page of outstanding subjects.
     *
     * @param entries   the subjects returned
     * @param truncated whether more matched than were returned
     * @param total     how many matched
     * @param byState   how many matched every filter member but the state, per state
     */
    public record Page(List<IngestFailureEntry> entries, boolean truncated, int total, Map<IngestFailureState, Integer> byState) {

        /** A page whose totals are those of {@code entries} alone, not truncated. */
        public static Page of(List<IngestFailureEntry> entries) {
            Map<IngestFailureState, Integer> byState = new EnumMap<>(IngestFailureState.class);
            for (IngestFailureState state : IngestFailureState.values()) {
                byState.put(state, (int) entries.stream().filter(entry -> entry.state() == state).count());
            }
            return new Page(List.copyOf(entries), false, entries.size(), Map.copyOf(byState));
        }
    }

    /**
     * Whether the retry sweep attempts due subjects.
     *
     * @param enabled     whether {@code sync.retry.enabled} is on and the sweep is scheduled
     * @param nextSweepAt when the next sweep starts; {@code null} when not enabled
     * @param maxPerSweep the most subjects one sweep attempts
     */
    public record RetrySweepStatus(boolean enabled, Instant nextSweepAt, int maxPerSweep) {
    }
}
