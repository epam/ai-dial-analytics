package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.configuration.BackfillProperties;
import com.epam.aidial.analytics.exception.BackfillDisabledException;
import com.epam.aidial.analytics.exception.BackfillInProgressException;
import com.epam.aidial.analytics.exception.BackfillNotFoundException;
import com.epam.aidial.analytics.exception.BackfillRangeException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DataBackfillService}: range validation, single-in-flight guarding, per-day
 * failure isolation, terminal-state transitions, and unknown-run handling — all against a mocked
 * {@link DataSyncService}, so no blob store or ClickHouse is needed.
 */
class DataBackfillServiceTest {

    private static final DataSyncService.RunSummary OK =
            new DataSyncService.RunSummary(1, 5, Map.of("dial_usage_log", 5L), 0, 0, 0, true, false);
    private static final DataSyncService.RunSummary FAIL =
            new DataSyncService.RunSummary(0, 0, Map.of(), 0, 0, 0, false, false);
    /** A day that failed because ClickHouse is out of disk space — a store-level fault, not this day's. */
    private static final DataSyncService.RunSummary DISK_FULL =
            new DataSyncService.RunSummary(0, 0, Map.of(), 0, 0, 0, false, true);

    private final DataSyncService syncService = mock(DataSyncService.class);
    private DataBackfillService service;
    private MeterRegistry meterRegistry;

    private DataBackfillService newService(boolean enabled, int maxDays) {
        return newService(enabled, maxDays, new SimpleMeterRegistry());
    }

    private DataBackfillService newService(boolean enabled, int maxDays, MeterRegistry registry) {
        meterRegistry = registry;
        service = new DataBackfillService(syncService, new BackfillProperties(enabled, maxDays), registry);
        return service;
    }

    /**
     * Triggers a run, retrying while the previous run's guard is still held: a run's terminal state is visible a
     * moment before its worker's {@code finally} releases the slot, so a re-trigger right after seeing it can race.
     */
    private static BackfillRun startWhenFree(DataBackfillService svc, String from, String to) {
        return await().ignoreException(BackfillInProgressException.class).until(() -> svc.start(from, to), Objects::nonNull);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void rejectsInvertedRange() {
        DataBackfillService svc = newService(true, 366);
        assertThatThrownBy(() -> svc.start("2026-06-10", "2026-06-01"))
                .isInstanceOf(BackfillRangeException.class);
    }

    @Test
    void rejectsFutureRange() {
        DataBackfillService svc = newService(true, 366);
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        assertThatThrownBy(() -> svc.start(tomorrow.toString(), tomorrow.toString()))
                .isInstanceOf(BackfillRangeException.class);
    }

    @Test
    void rejectsOverCapRange() {
        DataBackfillService svc = newService(true, 5);
        assertThatThrownBy(() -> svc.start("2026-06-01", "2026-06-10"))
                .isInstanceOf(BackfillRangeException.class);
    }

    @Test
    void rejectsMissingOrUnparseableDates() {
        DataBackfillService svc = newService(true, 366);
        assertThatThrownBy(() -> svc.start(null, "2026-06-01")).isInstanceOf(BackfillRangeException.class);
        assertThatThrownBy(() -> svc.start("not-a-date", "2026-06-01")).isInstanceOf(BackfillRangeException.class);
    }

    @Test
    void acceptsRangeExactlyAtCap() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        DataBackfillService svc = newService(true, 3);

        BackfillRun run = svc.start("2026-06-01", "2026-06-03"); // span == 3 == cap
        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));
        assertThat(svc.status(run.runId()).daysCompleted()).isEqualTo(3);
    }

    @Test
    void rejectsWhenDisabled() {
        DataBackfillService svc = newService(false, 366);
        assertThatThrownBy(() -> svc.start("2026-06-01", "2026-06-02"))
                .isInstanceOf(BackfillDisabledException.class);
    }

    @Test
    void ingestsEveryDayAndAccumulatesCounts() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        DataBackfillService svc = newService(true, 366);

        BackfillRun run = svc.start("2026-06-01", "2026-06-03");

        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));
        BackfillRun.Snapshot s = svc.status(run.runId());
        assertThat(s.daysTotal()).isEqualTo(3);
        assertThat(s.daysCompleted()).isEqualTo(3);
        assertThat(s.daysFailed()).isZero();
        assertThat(s.rowsInserted()).isEqualTo(15); // 3 days * 5
        assertThat(s.finishedAt()).isNotNull();
        assertThat(s.currentDate()).isNull();
    }

    @Test
    void isolatesFailingDayAndContinues() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        when(syncService.runForDate(eq(LocalDate.parse("2026-06-02")), any(), any())).thenReturn(FAIL);
        DataBackfillService svc = newService(true, 366);

        BackfillRun run = svc.start("2026-06-01", "2026-06-03");

        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));
        BackfillRun.Snapshot s = svc.status(run.runId());
        assertThat(s.daysCompleted()).isEqualTo(2);
        assertThat(s.daysFailed()).isEqualTo(1);
    }

    /**
     * Per-day isolation stops at disk exhaustion: it is a store-level fault, so every remaining day would fail
     * the same way. The run must stop rather than work through the range — the behavior four real backfills
     * lacked when they each hammered a full disk for their whole span.
     */
    @Test
    void stopsTheRangeWhenClickHouseIsOutOfDiskSpace() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        when(syncService.runForDate(eq(LocalDate.parse("2026-06-02")), any(), any())).thenReturn(DISK_FULL);
        DataBackfillService svc = newService(true, 366);

        BackfillRun run = svc.start("2026-06-01", "2026-06-05");

        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.FAILED));
        BackfillRun.Snapshot s = svc.status(run.runId());
        // Days 3-5 were never attempted, and the gap is what makes that visible in the status.
        assertThat(s.daysTotal()).isEqualTo(5);
        assertThat(s.daysCompleted()).isEqualTo(1);
        assertThat(s.daysFailed()).isEqualTo(1);
        assertThat(s.daysCompleted() + s.daysFailed()).isLessThan(s.daysTotal());
        assertThat(s.failedDates()).containsExactly(LocalDate.parse("2026-06-02"));
        assertThat(s.finishedAt()).isNotNull();
        assertThat(s.currentDate()).isNull();
        verify(syncService, never()).runForDate(eq(LocalDate.parse("2026-06-03")), any(), any());
        verify(syncService, never()).runForDate(eq(LocalDate.parse("2026-06-05")), any(), any());
    }

    /** The guard is released by the stop, so the operator can re-trigger the range once space is back. */
    @Test
    void allowsRetriggerAfterStoppingOnDiskExhaustion() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(DISK_FULL);
        DataBackfillService svc = newService(true, 366);
        BackfillRun stopped = svc.start("2026-06-01", "2026-06-03");
        await().untilAsserted(() ->
                assertThat(svc.status(stopped.runId()).state()).isEqualTo(BackfillRun.State.FAILED));

        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        BackfillRun retried = startWhenFree(svc, "2026-06-01", "2026-06-03");

        await().untilAsserted(() ->
                assertThat(svc.status(retried.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));
        assertThat(svc.status(retried.runId()).daysCompleted()).isEqualTo(3);
    }

    @Test
    void allDaysFailingYieldsFailedState() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(FAIL);
        DataBackfillService svc = newService(true, 366);

        BackfillRun run = svc.start("2026-06-01", "2026-06-02");

        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.FAILED));
        assertThat(svc.status(run.runId()).daysFailed()).isEqualTo(2);
    }

    @Test
    void rejectsConcurrentBackfill() {
        CountDownLatch release = new CountDownLatch(1);
        when(syncService.runForDate(any(), any(), any())).thenAnswer(invocation -> {
            release.await();
            return OK;
        });
        DataBackfillService svc = newService(true, 366);

        BackfillRun first = svc.start("2026-06-01", "2026-06-02"); // worker blocks on the latch

        assertThatThrownBy(() -> svc.start("2026-06-03", "2026-06-04"))
                .isInstanceOf(BackfillInProgressException.class);

        release.countDown();
        await().untilAsserted(() ->
                assertThat(svc.status(first.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));

        // A new backfill is accepted once the first reached a terminal state.
        BackfillRun second = startWhenFree(svc, "2026-06-03", "2026-06-04");
        await().untilAsserted(() ->
                assertThat(svc.status(second.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));
    }

    @Test
    void reflectsPerBlobProgressBeforeDayCompletes() throws Exception {
        CountDownLatch blobsReported = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(syncService.runForDate(any(), any(), any())).thenAnswer(invocation -> {
            DataSyncService.IngestProgressListener listener = invocation.getArgument(2);
            listener.onBlobIngested(5, 1, 2);
            listener.onBlobIngested(3, 0, 0);
            blobsReported.countDown();
            release.await();
            return OK;
        });
        DataBackfillService svc = newService(true, 366);

        BackfillRun run = svc.start("2026-06-01", "2026-06-01");
        blobsReported.await();

        // Mid-day: the run is still RUNNING but already reflects the two ingested blobs.
        BackfillRun.Snapshot mid = svc.status(run.runId());
        assertThat(mid.state()).isEqualTo(BackfillRun.State.RUNNING);
        assertThat(mid.daysCompleted()).isZero();
        assertThat(mid.filesProcessed()).isEqualTo(2);
        assertThat(mid.rowsInserted()).isEqualTo(8);
        assertThat(mid.rowsSkipped()).isEqualTo(1);
        assertThat(mid.rowsDropped()).isEqualTo(2);

        release.countDown();
        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));
    }

    /**
     * A fault escaping the loop abandons the rest of the range, so the run must end FAILED rather than claim
     * the completed state over days it never attempted; the day it died on is named as failed.
     */
    @Test
    void abortOnUnexpectedFaultReportsFailed() {
        assertAbortReportsFailed(new IllegalStateException("boom"));
    }

    /** As above for an {@link Error}, which the arm also stops the run for. */
    @Test
    void abortOnErrorReportsFailed() {
        assertAbortReportsFailed(new AssertionError("boom"));
    }

    private void assertAbortReportsFailed(Throwable fault) {
        LocalDate abortDay = LocalDate.parse("2026-06-05");
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        when(syncService.runForDate(eq(abortDay), any(), any())).thenThrow(fault);
        DataBackfillService svc = newService(true, 366);

        BackfillRun run = svc.start("2026-06-01", "2026-06-10");

        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.FAILED));
        BackfillRun.Snapshot s = svc.status(run.runId());
        assertThat(s.finishedAt()).isNotNull();
        assertThat(s.daysCompleted()).isEqualTo(4);
        assertThat(s.daysFailed()).isEqualTo(1);
        assertThat(s.failedDates()).containsExactly(abortDay);
        assertThat(s.daysCompleted() + s.daysFailed()).isLessThan(s.daysTotal());
        // The aborted day is counted in the metric, and its null summary leaves the four completed days' rows as they stood.
        assertThat(meterRegistry.counter("backfill.days", "outcome", "failure").count()).isEqualTo(1);
        assertThat(s.rowsInserted()).isEqualTo(20);
        verify(syncService, never()).runForDate(eq(LocalDate.parse("2026-06-06")), any(), any());

        // The guard is released however the run ended.
        BackfillRun next = startWhenFree(svc, "2026-06-01", "2026-06-01");
        await().untilAsserted(() ->
                assertThat(svc.status(next.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));
    }

    /** An abort on the last day leaves no remainder: the counts sum to the total, and the state alone says it stopped. */
    @Test
    void abortOnLastDayReportsFailed() {
        LocalDate lastDay = LocalDate.parse("2026-06-03");
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        when(syncService.runForDate(eq(lastDay), any(), any())).thenThrow(new IllegalStateException("boom"));
        DataBackfillService svc = newService(true, 366);

        BackfillRun run = svc.start("2026-06-01", "2026-06-03");

        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.FAILED));
        BackfillRun.Snapshot s = svc.status(run.runId());
        assertThat(s.daysCompleted()).isEqualTo(2);
        assertThat(s.daysFailed()).isEqualTo(1);
        assertThat(s.daysCompleted() + s.daysFailed()).isEqualTo(s.daysTotal());
        assertThat(s.failedDates()).containsExactly(lastDay);
    }

    @Test
    void namesFailedDatesInOrderOnCompletedRun() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        when(syncService.runForDate(eq(LocalDate.parse("2026-06-02")), any(), any())).thenReturn(FAIL);
        when(syncService.runForDate(eq(LocalDate.parse("2026-06-04")), any(), any())).thenReturn(FAIL);
        DataBackfillService svc = newService(true, 366);

        BackfillRun run = svc.start("2026-06-01", "2026-06-05");

        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));
        assertThat(svc.status(run.runId()).failedDates())
                .containsExactly(LocalDate.parse("2026-06-02"), LocalDate.parse("2026-06-04"));
    }

    @Test
    void cleanRunNamesNoFailedDates() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        DataBackfillService svc = newService(true, 366);

        BackfillRun run = svc.start("2026-06-01", "2026-06-03");

        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));
        assertThat(svc.status(run.runId()).failedDates()).isEmpty();
    }

    /** The disk-full day is named with the earlier failure; the unattempted remainder is not. */
    @Test
    void diskFullStopNamesOnlyAttemptedFailedDays() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        when(syncService.runForDate(eq(LocalDate.parse("2026-06-02")), any(), any())).thenReturn(FAIL);
        when(syncService.runForDate(eq(LocalDate.parse("2026-06-04")), any(), any())).thenReturn(DISK_FULL);
        DataBackfillService svc = newService(true, 366);

        BackfillRun run = svc.start("2026-06-01", "2026-06-05");

        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.FAILED));
        assertThat(svc.status(run.runId()).failedDates())
                .isEqualTo(List.of(LocalDate.parse("2026-06-02"), LocalDate.parse("2026-06-04")));
    }

    /**
     * A trigger the worker refuses leaves nothing behind: it is not counted, and the guard is released, so the
     * next trigger fails the same way rather than as already in progress, and it is never listed.
     */
    @Test
    void rejectedSubmissionReleasesGuardAndIsNotCounted() {
        DataBackfillService svc = newService(true, 366);
        svc.shutdown();

        assertThatThrownBy(() -> svc.start("2026-06-01", "2026-06-02"))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(meterRegistry.counter("backfill.runs").count()).isZero();
        assertThat(svc.list(null)).isEmpty();
        assertThatThrownBy(() -> svc.start("2026-06-01", "2026-06-02"))
                .isInstanceOf(RejectedExecutionException.class);
    }

    /**
     * A fault after a day went through completeDay (here, its success metric throwing) must not count the day
     * a second time as the aborted one: the day was completed, not aborted, and the run still ends FAILED.
     */
    @Test
    void faultAfterCompletingDayDoesNotCountItTwice() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        DataBackfillService svc = newService(true, 366, registryThrowingOn("success"));

        BackfillRun run = svc.start("2026-06-01", "2026-06-03");

        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.FAILED));
        BackfillRun.Snapshot s = svc.status(run.runId());
        assertThat(s.daysCompleted()).isEqualTo(1);
        assertThat(s.daysFailed()).isZero();
        assertThat(s.failedDates()).isEmpty();
    }

    /** A registry whose {@code backfill.days{outcome=<outcome>}} counter lookup throws. */
    private static MeterRegistry registryThrowingOn(String outcome) {
        MeterRegistry registry = spy(new SimpleMeterRegistry());
        doThrow(new IllegalStateException("meter failure")).when(registry).counter("backfill.days", "outcome", outcome);
        return registry;
    }

    /**
     * A second fault inside the abort handler (here, the failure metric throwing) must not skip the stop: the run
     * still ends FAILED naming the day it died on, and the slot is released.
     */
    @Test
    void secondFaultWhileAbortingStillStopsTheRun() {
        LocalDate abortDay = LocalDate.parse("2026-06-02");
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        when(syncService.runForDate(eq(abortDay), any(), any())).thenThrow(new IllegalStateException("boom"));
        DataBackfillService svc = newService(true, 366, registryThrowingOn("failure"));

        BackfillRun run = svc.start("2026-06-01", "2026-06-03");

        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.FAILED));
        assertThat(svc.status(run.runId()).failedDates()).containsExactly(abortDay);
        BackfillRun next = startWhenFree(svc, "2026-06-01", "2026-06-01");
        await().untilAsserted(() ->
                assertThat(svc.status(next.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));
    }

    /** Runs one one-day backfill to a terminal state, waiting for the previous run's slot first. */
    private static String runToEnd(DataBackfillService svc, String day) {
        BackfillRun run = startWhenFree(svc, day, day);
        await().until(() -> svc.status(run.runId()).state() != BackfillRun.State.RUNNING);
        return run.runId();
    }

    /** Runs {@code count} one-day backfills to their end, one after another, on consecutive days; returns their ids. */
    private static List<String> runsToEnd(DataBackfillService svc, int count) {
        List<String> started = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            started.add(runToEnd(svc, LocalDate.parse("2026-06-01").plusDays(i).toString()));
        }
        return started;
    }

    @Test
    void listsRunsNewestFirstAndFiltersByState() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        when(syncService.runForDate(eq(LocalDate.parse("2026-06-02")), any(), any())).thenReturn(FAIL);
        DataBackfillService svc = newService(true, 366);

        String first = runToEnd(svc, "2026-06-01");
        String failed = runToEnd(svc, "2026-06-02");
        String third = runToEnd(svc, "2026-06-03");

        assertThat(svc.list(null)).extracting(BackfillRun.Snapshot::runId).containsExactly(third, failed, first);
        assertThat(svc.list(BackfillRun.State.FAILED)).singleElement().satisfies(s -> {
            assertThat(s.runId()).isEqualTo(failed);
            assertThat(s.failedDates()).containsExactly(LocalDate.parse("2026-06-02"));
        });
        assertThat(svc.list(BackfillRun.State.COMPLETED)).extracting(BackfillRun.Snapshot::runId).containsExactly(third, first);
        assertThat(svc.list(BackfillRun.State.RUNNING)).isEmpty();
    }

    @Test
    void listsTheActiveRunWhileItRuns() throws Exception {
        CountDownLatch dayStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(syncService.runForDate(any(), any(), any())).thenAnswer(invocation -> {
            dayStarted.countDown();
            // Bounded, so a failed assertion before the release cannot wedge the worker until tearDown.
            release.await(10, TimeUnit.SECONDS);
            return OK;
        });
        DataBackfillService svc = newService(true, 366);

        BackfillRun run = svc.start("2026-06-01", "2026-06-01");
        assertThat(dayStarted.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(svc.list(BackfillRun.State.RUNNING)).singleElement().satisfies(s -> {
            assertThat(s.runId()).isEqualTo(run.runId());
            assertThat(s.state()).isEqualTo(BackfillRun.State.RUNNING);
            assertThat(s.currentDate()).isEqualTo(LocalDate.parse("2026-06-01"));
        });

        release.countDown();
        await().untilAsserted(() ->
                assertThat(svc.status(run.runId()).state()).isEqualTo(BackfillRun.State.COMPLETED));
        assertThat(svc.list(BackfillRun.State.RUNNING)).isEmpty();
    }

    /** One retention governs both reads: a run evicted from the list is unknown to the by-id read too. */
    @Test
    void evictsTheOldestRunBeyondRetention() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        DataBackfillService svc = newService(true, 366);

        List<String> started = runsToEnd(svc, DataBackfillService.MAX_RETAINED_RUNS + 1);

        assertThat(svc.list(null)).extracting(BackfillRun.Snapshot::runId)
                .containsExactlyElementsOf(started.subList(1, started.size()).reversed());
        assertThatThrownBy(() -> svc.status(started.getFirst())).isInstanceOf(BackfillNotFoundException.class);
    }

    /** At full retention, a refused trigger must not push out the oldest run it never replaced. */
    @Test
    void refusedTriggerAtFullRetentionEvictsNothing() {
        when(syncService.runForDate(any(), any(), any())).thenReturn(OK);
        DataBackfillService svc = newService(true, 366);
        List<String> started = runsToEnd(svc, DataBackfillService.MAX_RETAINED_RUNS);
        svc.shutdown();

        assertThatThrownBy(() -> svc.start("2026-06-01", "2026-06-01"))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(svc.list(null)).extracting(BackfillRun.Snapshot::runId).containsExactlyElementsOf(started.reversed());
    }

    @Test
    void unknownRunIdIsRejected() {
        DataBackfillService svc = newService(true, 366);
        assertThatThrownBy(() -> svc.status("does-not-exist"))
                .isInstanceOf(BackfillNotFoundException.class);
    }
}
