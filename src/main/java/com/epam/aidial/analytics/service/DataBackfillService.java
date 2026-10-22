package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.configuration.BackfillProperties;
import com.epam.aidial.analytics.exception.BackfillDisabledException;
import com.epam.aidial.analytics.exception.BackfillInProgressException;
import com.epam.aidial.analytics.exception.BackfillNotFoundException;
import com.epam.aidial.analytics.exception.BackfillRangeException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates the operator-triggered, asynchronous backfill of a bounded UTC date range. A trigger
 * validates the range, registers a {@link BackfillRun}, and submits a worker that ingests each day in
 * the range via {@link DataSyncService#runForDate(LocalDate, IngestLedger.Mode, DataSyncService.IngestProgressListener)}
 * — the same parse → map → batch-insert pipeline used by the scheduled path. By default it skips the blobs
 * the {@link IngestLedger} records, so a backfill over ingested days, or a re-trigger of a range a stopped run
 * left part-done, writes only what is missing; {@code includeIngested} re-ingests the whole range, the
 * overlap collapsed by the target table's {@code ReplacingMergeTree} engine.
 *
 * <p>At most one backfill runs at a time (guarded by {@link #running}); a concurrent trigger is
 * rejected. A day that fails to ingest is counted and skipped without aborting the rest of the range.
 * Run state is held in memory in a bounded registry and is not retained across a restart.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DataBackfillService {

    /**
     * Upper bound on retained run statuses; the active run is always the newest, so it is never evicted. Public so
     * the API documentation can quote it instead of restating the number.
     */
    public static final int MAX_RETAINED_RUNS = 5;

    private final DataSyncService dataSyncService;
    private final BackfillProperties properties;
    private final MeterRegistry meterRegistry;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "data-backfill");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    // Insertion-ordered, so oldest started first. Trimmed by hand once a run is accepted rather than evicted on put, so
    // a trigger the worker refuses cannot push out a run it never replaced.
    private final Map<String, BackfillRun> runs = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * Validates the request and starts a backfill run asynchronously.
     *
     * @param fromText inclusive start date, ISO {@code yyyy-MM-dd}
     * @param toText   inclusive end date, ISO {@code yyyy-MM-dd}
     * @return the started run (state {@code RUNNING})
     * @throws BackfillDisabledException   if the feature is disabled
     * @throws BackfillRangeException      if the range is missing, unparseable, inverted, in the future,
     *                                     or exceeds the configured maximum span
     * @throws BackfillInProgressException if another backfill is already running
     */
    public BackfillRun start(String fromText, String toText) {
        return start(fromText, toText, false);
    }

    /**
     * As {@link #start(String, String)}, choosing whether blobs the ingest ledger records are skipped (the
     * default) or ingested again — to repair rows lost under a recorded blob, or to apply a changed mapping to
     * history.
     *
     * @param includeIngested {@code true} to re-ingest every blob in the range, recorded or not
     */
    public BackfillRun start(String fromText, String toText, boolean includeIngested) {
        if (!properties.enabled()) {
            throw new BackfillDisabledException("Backfill is disabled");
        }
        LocalDate from = parse(fromText, "from");
        LocalDate to = parse(toText, "to");
        validateRange(from, to);

        if (!running.compareAndSet(false, true)) {
            throw new BackfillInProgressException("A backfill run is already in progress");
        }

        BackfillRun run = new BackfillRun(UUID.randomUUID().toString(), from, to, Instant.now());
        // Registered and logged before submit, so the worker can neither end the run (and free the slot) ahead of
        // its registration nor log ahead of its start line; counted only once the worker has accepted it.
        runs.put(run.runId(), run);
        log.info("Backfill run {} starting: from={} to={} ({} day(s)), includeIngested={}",
                run.runId(), from, to, run.daysTotal(), includeIngested);
        IngestLedger.Mode mode = includeIngested ? IngestLedger.Mode.INGEST_ALL : IngestLedger.Mode.SKIP_RECORDED;
        try {
            worker.submit(() -> execute(run, from, to, mode));
        } catch (RuntimeException | Error ex) {
            // Submission failed (e.g. executor shutting down, or no native thread for the lazily-created worker):
            // withdraw the run no worker will end, and release the guard so the feature is not wedged.
            runs.remove(run.runId());
            running.set(false);
            log.warn("Backfill run {} withdrawn: the worker refused it ({})", run.runId(), ex.toString());
            throw ex;
        }
        evictBeyondRetention();
        meterRegistry.counter("backfill.runs").increment();
        return run;
    }

    /**
     * Returns the current status of a backfill run.
     *
     * @param runId the run identifier returned by {@link #start(String, String)}
     * @return an immutable snapshot of the run's status
     * @throws BackfillNotFoundException if the service has no run with that id
     */
    public BackfillRun.Snapshot status(String runId) {
        BackfillRun run = runs.get(runId);
        if (run == null) {
            throw new BackfillNotFoundException("Unknown backfill run: " + runId);
        }
        return run.snapshot();
    }

    /**
     * Lists the retained backfill runs, newest started first. The registry's insertion order is the runs' start
     * order ({@link #start} registers before submitting), so no sort is needed. The list is bounded by retention
     * (at most the {@value #MAX_RETAINED_RUNS} most recently started runs) and holds this instance's runs only.
     *
     * @param state keep only runs in this state, or {@code null} for every retained run
     * @return an immutable snapshot of each matching run
     */
    public List<BackfillRun.Snapshot> list(BackfillRun.State state) {
        List<BackfillRun> retained;
        // A synchronizedMap's iteration is not atomic: its contract requires holding the map's monitor. Snapshots are
        // taken after release, each under its run's own monitor, to keep this lock short.
        synchronized (runs) {
            retained = new ArrayList<>(runs.values());
        }
        Collections.reverse(retained);
        // Filtered on the snapshot rather than the run, so an entry and the filter it passed agree on its state.
        return retained.stream()
                .map(BackfillRun::snapshot)
                .filter(snapshot -> state == null || snapshot.state() == state)
                .toList();
    }

    private void execute(BackfillRun run, LocalDate from, LocalDate to, IngestLedger.Mode mode) {
        // The day started but not yet through completeDay, so an abort can count it without counting it twice.
        LocalDate dayInProgress = null;
        try {
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                dayInProgress = date;
                run.startDay(date);
                DataSyncService.RunSummary summary = dataSyncService.runForDate(date, mode, run::recordBlobProgress);
                run.completeDay(date, summary, summary.success());
                dayInProgress = null;
                if (summary.success()) {
                    meterRegistry.counter("backfill.days", "outcome", "success").increment();
                    log.info("Backfill run {} ingested {}: {}", run.runId(), date, summary);
                } else {
                    meterRegistry.counter("backfill.days", "outcome", "failure").increment();
                    // "continuing" only where it is true: a storage-full day stops the run just below.
                    log.warn("Backfill run {} failed to ingest {}{}", run.runId(), date,
                            summary.storageFull() ? "" : " (continuing)");
                }
                summary.rowsInsertedByTable().forEach((table, count) ->
                        meterRegistry.counter("backfill.rows.inserted", "table", table).increment(count));
                meterRegistry.counter("backfill.rows.skipped").increment(summary.rowsSkipped());
                meterRegistry.counter("backfill.rows.dropped").increment(summary.rowsDropped());
                meterRegistry.counter("backfill.blobs.skipped", "reason", "ledger").increment(summary.blobsSkipped());
                if (summary.storageFull()) {
                    // Per-day isolation stops here, deliberately: ClickHouse is out of disk, so every remaining
                    // day would fail the same way while adding load and partial parts to a store that cannot
                    // take them. Stopping keeps the cost of a doomed run at one day instead of the whole range;
                    // the capability stays enabled, so re-triggering after a resize is all it takes.
                    log.error("Backfill run {} stopped at {}: ClickHouse is out of disk space; "
                            + "{} day(s) of the range were not attempted", run.runId(), date, remaining(date, to));
                    run.stop(Instant.now());
                    return;
                }
            }
            run.finish(Instant.now());
            BackfillRun.Snapshot snapshot = run.snapshot();
            log.info("Backfill run {} finished: state={} daysCompleted={} daysFailed={} rowsInserted={}",
                    run.runId(), snapshot.state(), snapshot.daysCompleted(), snapshot.daysFailed(),
                    snapshot.rowsInserted());
        } catch (RuntimeException | Error ex) {
            // runForDate never throws, but the loop runs more than runForDate (the tracing wrapper, the run's own
            // bookkeeping, metrics, logging), and an Error can come from anywhere. An abandoned run is FAILED
            // whatever the tallies say, so it stops rather than finishes; the day in progress was attempted and
            // did not complete, so it counts as failed. The stop sits in a finally so nothing recorded before it
            // (under memory pressure, say) can skip it, and a second fault there is attached to the original rather
            // than replacing it. The submit Future is never read, so this log is the fault's only trace; an Error is
            // still rethrown so it is not swallowed in-process, though it only lands in that unread Future.
            LocalDate abortedOn = dayInProgress;
            boolean stopped;
            try {
                if (abortedOn != null) {
                    run.completeDay(abortedOn, null, false);
                    meterRegistry.counter("backfill.days", "outcome", "failure").increment();
                }
            } catch (RuntimeException | Error secondary) {
                if (secondary != ex) {
                    ex.addSuppressed(secondary);
                }
            } finally {
                stopped = run.stop(Instant.now());
            }
            if (stopped) {
                log.error("Backfill run {} aborted unexpectedly on {}: {}", run.runId(), abortedOn, ex.getMessage(), ex);
            } else {
                log.warn("Backfill run {} hit a fault after reaching {}: {}", run.runId(), run.snapshot().state(),
                        ex.getMessage(), ex);
            }
            if (ex instanceof Error error) {
                throw error;
            }
        } finally {
            running.set(false);
        }
    }

    /** Drops the oldest runs beyond {@link #MAX_RETAINED_RUNS}; the newest (the active run) is never among them. */
    private void evictBeyondRetention() {
        synchronized (runs) {
            Iterator<BackfillRun> oldestFirst = runs.values().iterator();
            for (int excess = runs.size() - MAX_RETAINED_RUNS; excess > 0; excess--) {
                oldestFirst.next();
                oldestFirst.remove();
            }
        }
    }

    /** Days of the range after {@code stoppedAt} that were never attempted, for the stop log. */
    private static long remaining(LocalDate stoppedAt, LocalDate to) {
        return to.toEpochDay() - stoppedAt.toEpochDay();
    }

    private static LocalDate parse(String text, String field) {
        if (StringUtils.isBlank(text)) {
            throw new BackfillRangeException(field + " is required (ISO yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ex) {
            throw new BackfillRangeException(field + " is not a valid ISO date (yyyy-MM-dd)");
        }
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BackfillRangeException("from must not be after to");
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (to.isAfter(today)) {
            throw new BackfillRangeException("to must not be in the future");
        }
        long span = to.toEpochDay() - from.toEpochDay() + 1;
        if (span > properties.maxDays()) {
            throw new BackfillRangeException(
                    "range spans " + span + " days, exceeding the maximum of " + properties.maxDays());
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException ex) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
