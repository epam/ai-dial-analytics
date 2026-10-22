package com.epam.aidial.analytics.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link BackfillRun}: the first terminal transition wins, and an abandoned day's accounting. */
class BackfillRunTest {

    private static final Instant FIRST = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-06-01T01:00:00Z");

    private static BackfillRun newRun() {
        return new BackfillRun("run", LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-02"), FIRST);
    }

    @Test
    void stopAfterFinishKeepsCompleted() {
        BackfillRun run = newRun();
        run.finish(FIRST);

        assertThat(run.stop(SECOND)).isFalse();
        BackfillRun.Snapshot s = run.snapshot();
        assertThat(s.state()).isEqualTo(BackfillRun.State.COMPLETED);
        assertThat(s.finishedAt()).isEqualTo(FIRST);
    }

    @Test
    void finishAfterStopKeepsFailed() {
        BackfillRun run = newRun();

        assertThat(run.stop(FIRST)).isTrue();
        run.finish(SECOND);
        BackfillRun.Snapshot s = run.snapshot();
        assertThat(s.state()).isEqualTo(BackfillRun.State.FAILED);
        assertThat(s.finishedAt()).isEqualTo(FIRST);
    }

    @Test
    void secondStopIsIgnored() {
        BackfillRun run = newRun();

        assertThat(run.stop(FIRST)).isTrue();
        assertThat(run.stop(SECOND)).isFalse();
        assertThat(run.snapshot().finishedAt()).isEqualTo(FIRST);
    }

    @Test
    void secondFinishIsIgnored() {
        BackfillRun run = newRun();
        run.finish(FIRST);
        run.finish(SECOND);

        assertThat(run.snapshot().finishedAt()).isEqualTo(FIRST);
    }

    /** A day abandoned without a summary keeps its live counts and is named as failed. */
    @Test
    void abandonedDayKeepsLiveCountsAndIsNamedFailed() {
        BackfillRun run = newRun();
        LocalDate day = LocalDate.parse("2026-06-01");
        run.startDay(day);
        run.recordBlobProgress(7, 1, 2);

        run.completeDay(day, null, false);

        BackfillRun.Snapshot s = run.snapshot();
        assertThat(s.filesProcessed()).isEqualTo(1);
        assertThat(s.rowsInserted()).isEqualTo(7);
        assertThat(s.rowsSkipped()).isEqualTo(1);
        assertThat(s.rowsDropped()).isEqualTo(2);
        assertThat(s.daysFailed()).isEqualTo(1);
        assertThat(s.failedDates()).containsExactly(day);
    }
}
