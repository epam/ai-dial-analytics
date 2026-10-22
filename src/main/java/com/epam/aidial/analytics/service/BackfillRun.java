package com.epam.aidial.analytics.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable status of a single backfill run, updated by the backfill worker thread as each day completes
 * and read concurrently by request threads serving the status endpoint. All access goes through
 * {@code synchronized} methods, and reads are served via an immutable {@link Snapshot} so a caller never
 * observes a half-updated run.
 *
 * <p>Run state lives only in memory; it is not persisted and does not survive a restart.
 */
public class BackfillRun {

    /** Lifecycle state. A run is {@code FAILED} if every day in the range failed, or if it stopped early
     * without attempting the rest of the range (see {@link #stop}); otherwise {@code COMPLETED} (which may
     * still carry a non-zero {@code daysFailed}). */
    public enum State {
        RUNNING,
        COMPLETED,
        FAILED
    }

    private final String runId;
    private final LocalDate from;
    private final LocalDate to;
    private final int daysTotal;
    private final Instant startedAt;

    private State state = State.RUNNING;
    private LocalDate currentDate;
    private int daysCompleted;
    private int daysFailed;
    private final List<LocalDate> failedDates = new ArrayList<>();
    private long filesProcessed;
    private long rowsInserted;
    private long rowsSkipped;
    private long rowsDropped;
    private Instant finishedAt;

    // Cumulative counts as of the start of the day in progress, so the day's live per-blob increments
    // can be replaced by the authoritative day summary at completion without double-counting.
    private long baselineFiles;
    private long baselineInserted;
    private long baselineSkipped;
    private long baselineDropped;

    BackfillRun(String runId, LocalDate from, LocalDate to, Instant startedAt) {
        this.runId = runId;
        this.from = from;
        this.to = to;
        this.daysTotal = (int) (to.toEpochDay() - from.toEpochDay() + 1);
        this.startedAt = startedAt;
    }

    String runId() {
        return runId;
    }

    int daysTotal() {
        return daysTotal;
    }

    /**
     * Marks a new day as in progress: records it as the current date and snapshots the cumulative
     * counts so this day's live per-blob increments can be reconciled to the day summary on completion.
     */
    synchronized void startDay(LocalDate date) {
        this.currentDate = date;
        this.baselineFiles = filesProcessed;
        this.baselineInserted = rowsInserted;
        this.baselineSkipped = rowsSkipped;
        this.baselineDropped = rowsDropped;
    }

    /**
     * Folds one blob's contribution into the live counts, so status reflects intra-day progress before
     * the day completes. Reconciled to the authoritative day summary by {@link #completeDay}.
     */
    synchronized void recordBlobProgress(long rowsInsertedDelta, long rowsSkippedDelta, long rowsDroppedDelta) {
        filesProcessed++;
        rowsInserted += rowsInsertedDelta;
        rowsSkipped += rowsSkippedDelta;
        rowsDropped += rowsDroppedDelta;
    }

    /**
     * Closes out the day in progress: replaces its live per-blob counts with the authoritative day
     * summary (so totals are exact regardless of batching/flush timing) and increments the
     * completed-or-failed tally, naming {@code date} among the failed dates when the day failed. A {@code null}
     * summary (a day abandoned on an unexpected fault) leaves the live counts as they stood.
     */
    synchronized void completeDay(LocalDate date, DataSyncService.RunSummary summary, boolean daySucceeded) {
        if (summary != null) {
            filesProcessed = baselineFiles + summary.filesProcessed();
            rowsInserted = baselineInserted + summary.rowsInserted();
            rowsSkipped = baselineSkipped + summary.rowsSkipped();
            rowsDropped = baselineDropped + summary.rowsDropped();
        }
        if (daySucceeded) {
            daysCompleted++;
        } else {
            failedDates.add(date);
            daysFailed++;
        }
    }

    /**
     * Marks the run terminal: {@code FAILED} if every day failed, otherwise {@code COMPLETED}.
     * Does nothing once the run is terminal, so the first terminal transition wins.
     */
    synchronized void finish(Instant finishedAt) {
        if (state != State.RUNNING) {
            return;
        }
        this.currentDate = null;
        this.finishedAt = finishedAt;
        this.state = (daysTotal > 0 && daysFailed == daysTotal) ? State.FAILED : State.COMPLETED;
    }

    /**
     * Marks the run terminal as {@code FAILED} because it stopped before working through the range, whatever
     * the per-day tallies say. Distinct from {@link #finish} so an abandoned run is not reported with the same
     * state as one that covered its whole range: with {@code daysTotal} left at the range size, a caller sees
     * {@code failed} plus {@code daysCompleted + daysFailed < daysTotal} and knows the remainder was never
     * attempted. (Where the stop lands on the range's last day there is no remainder to see, and the terminal
     * state alone carries it.) The reason itself is in the logs and the write-path metrics, not in the
     * status.
     *
     * <p>Does nothing once the run is terminal, so the first terminal transition wins; returns whether this call
     * made the transition.
     */
    synchronized boolean stop(Instant finishedAt) {
        if (state != State.RUNNING) {
            return false;
        }
        this.currentDate = null;
        this.finishedAt = finishedAt;
        this.state = State.FAILED;
        return true;
    }

    /** Returns an immutable, internally-consistent view of the run's current status. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                runId, from, to, daysTotal, state, currentDate, daysCompleted, daysFailed, List.copyOf(failedDates),
                filesProcessed, rowsInserted, rowsSkipped, rowsDropped, startedAt, finishedAt);
    }

    /**
     * Immutable point-in-time view of a {@link BackfillRun}, safe to hand to other threads.
     */
    public record Snapshot(
            String runId,
            LocalDate from,
            LocalDate to,
            int daysTotal,
            State state,
            LocalDate currentDate,
            int daysCompleted,
            int daysFailed,
            List<LocalDate> failedDates,
            long filesProcessed,
            long rowsInserted,
            long rowsSkipped,
            long rowsDropped,
            Instant startedAt,
            Instant finishedAt) {
    }
}
