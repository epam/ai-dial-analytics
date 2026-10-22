package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.configuration.AggregateProperties;
import com.epam.aidial.analytics.exception.PipelineConflictException;
import com.epam.aidial.analytics.exception.PipelineReadBudgetExceededException;
import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.service.pipeline.model.RefreshOutcome;
import com.epam.aidial.analytics.service.security.SystemIdentity;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit coverage for {@link AggregateScheduler}: the master switch, due-selection, failure isolation, and
 * the re-arm decision.
 *
 * <p>The two that matter operationally are failure isolation — one bad declaration must not stop every
 * other rollup from being maintained, and the failing pipeline must still be re-armed so it recovers by
 * itself once the cause is fixed — and the backlogged/caught-up split, since arming a lagging pipeline on
 * its cron instead of the next tick is the difference between a backlog that drains and one that never
 * does, with permanently stale numbers as the only symptom.
 */
@ExtendWith(MockitoExtension.class)
class AggregateSchedulerTest {

    @Mock
    private PipelineRepository pipelines;
    @Mock
    private AggregateRefreshService refreshService;
    @Mock
    private PipelineStateWriter stateWriter;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private AggregateScheduler scheduler(boolean enabled) {
        return new AggregateScheduler(pipelines, refreshService, stateWriter, new SystemIdentity(),
                new AggregateProperties(enabled, "0 * * * * *", 1, Duration.ofSeconds(5), 1000, 20,
                        new AggregateProperties.Preview(Duration.ofSeconds(30),
                                DataSize.ofGigabytes(2), DataSize.ofGigabytes(1))));
    }

    private static PipelineEntity pipeline(String name) {
        final PipelineEntity entity = new PipelineEntity();
        entity.setName(name);
        entity.setTriggerCron("0 0 * * * *");
        entity.setEnabled(true);
        return entity;
    }

    @Test
    void tickDoesNothingWhenDisabled() {
        scheduler(false).tick();

        verify(pipelines, never()).findDue(any());
        verify(refreshService, never()).refresh(any());
    }

    @Test
    void refreshesEveryDuePipelineAndReArmsIt() {
        given(pipelines.findDue(any())).willReturn(List.of(pipeline("a"), pipeline("b")));
        given(refreshService.refresh(any())).willReturn(new RefreshOutcome(3L, false, 1L));

        assertThat(scheduler(true).runDue()).isEqualTo(2);

        verify(refreshService).refresh("a");
        verify(refreshService).refresh("b");
        verify(stateWriter).setNextRunAt(eq("a"), any(Instant.class));
        verify(stateWriter).setNextRunAt(eq("b"), any(Instant.class));
    }

    @Test
    void anIdleTickLogsNothing() {
        given(pipelines.findDue(any())).willReturn(List.of());

        try (LogCapture log = LogCapture.of(AggregateScheduler.class)) {
            assertThat(scheduler(true).runDue()).isZero();

            // The default tick cron is per-minute. A summary line on an empty tick would be ~1,440 entries
            // a day per replica reporting that nothing happened, which trains an operator to filter the
            // very logger the useful line arrives on.
            assertThat(log.messagesAt(Level.INFO)).isEmpty();
        }
    }

    @Test
    void tickThatDidWorkReportsWhatItDid() {
        given(pipelines.findDue(any())).willReturn(List.of(pipeline("ok"), pipeline("gone"), pipeline("bad")));
        given(refreshService.refresh("ok")).willReturn(new RefreshOutcome(3L, false, 1L));
        willThrow(new PipelineConflictException("disabled")).given(refreshService).refresh("gone");
        willThrow(new IllegalStateException("boom")).given(refreshService).refresh("bad");

        try (LogCapture log = LogCapture.of(AggregateScheduler.class)) {
            scheduler(true).runDue();

            assertThat(log.messagesAt(Level.INFO)).singleElement().satisfies(summary -> assertThat(summary)
                    .contains("3 due")
                    .contains("1 refreshed")
                    .contains("1 skipped")
                    .contains("1 failed"));
        }
    }

    @Test
    void caughtUpPipelineWaitsForItsCron() {
        final ArgumentCaptor<Instant> armed = ArgumentCaptor.forClass(Instant.class);
        given(pipelines.findDue(any())).willReturn(List.of(pipeline("caught_up")));
        given(refreshService.refresh("caught_up")).willReturn(new RefreshOutcome(3L, false, 1L));

        scheduler(true).runDue();

        // The fixture's cron is hourly, so a caught-up pipeline must be parked well beyond the next tick.
        verify(stateWriter).setNextRunAt(eq("caught_up"), armed.capture());
        assertThat(armed.getValue()).isAfter(Instant.now().plusSeconds(60));
    }

    @Test
    void backloggedPipelineIsDueAgainNextTickNotOnItsCron() {
        final ArgumentCaptor<Instant> armed = ArgumentCaptor.forClass(Instant.class);
        given(pipelines.findDue(any())).willReturn(List.of(pipeline("behind")));
        // hasMore: the probe filled its window limit, so input remains beyond this run.
        given(refreshService.refresh("behind")).willReturn(new RefreshOutcome(100_000L, true, 900L));

        scheduler(true).runDue();

        // Parking it on the hourly cron would drain one window per HOUR instead of per tick — for a
        // pipeline whose input arrives faster than that, a backlog it can never work off, showing up only
        // as a rollup serving permanently stale numbers. So it is armed for immediately.
        verify(stateWriter).setNextRunAt(eq("behind"), armed.capture());
        assertThat(armed.getValue()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void failingPipelineIsIsolatedAndStillReArmed() {
        given(pipelines.findDue(any())).willReturn(List.of(pipeline("broken"), pipeline("healthy")));
        willThrow(new IllegalStateException("target vanished")).given(refreshService).refresh("broken");
        given(refreshService.refresh("healthy")).willReturn(new RefreshOutcome(1L, false, 0L));

        scheduler(true).runDue();

        // The healthy pipeline still ran, and the broken one is re-armed so it recovers by itself once the
        // cause is fixed. Recording the error against the pipeline is the refresh service's job (it does the
        // same for an on-demand run), so the scheduler must not write a second, competing record.
        verify(refreshService).refresh("healthy");
        verify(stateWriter, never()).recordFailure(eq("broken"), any());
        verify(stateWriter).setNextRunAt(eq("broken"), any(Instant.class));
    }

    /**
     * A budget refusal repeats on every tick until the declaration or the budget changes, and its message
     * already names the ceiling and the remedy: one WARN line with that message and no stack, which is at DEBUG.
     * An unexpected failure keeps its stack.
     */
    @Test
    void budgetRefusalLogsNoStackButUnexpectedFailureDoes() {
        given(pipelines.findDue(any())).willReturn(List.of(pipeline("heavy"), pipeline("broken")));
        willThrow(new PipelineReadBudgetExceededException("this aggregate refresh write exceeded the 2GB memory "
                + "ceiling (pipeline.budget.*)", null)).given(refreshService).refresh("heavy");
        willThrow(new IllegalStateException("target vanished")).given(refreshService).refresh("broken");

        try (LogCapture log = LogCapture.of(AggregateScheduler.class)) {
            scheduler(true).runDue();

            assertThat(log.eventsAt(Level.WARN)).satisfiesExactly(
                    refused -> {
                        assertThat(refused.getMessage().getFormattedMessage())
                                .contains("'heavy'").contains("exceeded the 2GB memory ceiling");
                        assertThat(refused.getThrown()).isNull();
                    },
                    unexpected -> {
                        assertThat(unexpected.getMessage().getFormattedMessage()).contains("'broken'");
                        assertThat(unexpected.getThrown()).isInstanceOf(IllegalStateException.class);
                    });
            assertThat(log.eventsAt(Level.DEBUG)).anySatisfy(debug -> assertThat(debug.getThrown())
                    .isInstanceOf(PipelineReadBudgetExceededException.class));
        }
    }

    @Test
    void nextRunIsDerivedFromTheCron() {
        final Instant next = AggregateScheduler.nextRun("0 0 * * * *");

        assertThat(next).isNotNull().isAfter(Instant.now().minusSeconds(1));
    }

    @Test
    void anUnusableCronDisarmsThePipelineInsteadOfWedgingTheTick() {
        final PipelineEntity broken = pipeline("bad_cron");
        broken.setTriggerCron("not a cron");
        given(pipelines.findDue(any())).willReturn(List.of(broken));
        given(refreshService.refresh("bad_cron")).willReturn(new RefreshOutcome(1L, false, 0L));

        scheduler(true).runDue();

        // Leaving next_run_at at its stale past value would make the pipeline due on EVERY tick, running a
        // full refresh each time, forever. Disarming stops that and the recorded error is what gets fixed.
        verify(stateWriter).setNextRunAt("bad_cron", null);
        verify(stateWriter).recordFailure(eq("bad_cron"), contains("cannot be evaluated"));
    }

    @Test
    void pipelineDisabledMidTickIsSkippedRatherThanRecordedAsFailing() {
        given(pipelines.findDue(any())).willReturn(List.of(pipeline("paused")));
        willThrow(new PipelineConflictException("pipeline 'paused' is disabled and cannot write"))
                .given(refreshService).refresh("paused");

        scheduler(true).runDue();

        // Disabled between due-selection and execution: not this pipeline's failure, and re-arming would
        // undo the disarm the disable just performed.
        verify(stateWriter, never()).recordFailure(any(), any());
        verify(stateWriter, never()).setNextRunAt(any(), any());
    }

    @Test
    void eachRefreshRunsUnderTheSystemIdentityAndUnwindsIt() {
        final AtomicReference<Authentication> seen = new AtomicReference<>();
        given(pipelines.findDue(any())).willReturn(List.of(pipeline("elevated")));
        given(refreshService.refresh("elevated")).willAnswer(inv -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication());
            return new RefreshOutcome(1L, false, 0L);
        });

        scheduler(true).runDue();

        // Column governance reads the calling identity, and a scheduler thread has no caller — without the
        // elevation a pipeline over a sensitive column would fail on every tick, only in oidc mode. The
        // elevation is now the read ladder alone — closed under implication — not every privilege.
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("PRIVILEGE_TABLE_SENSITIVE", "PRIVILEGE_TABLE_READ",
                        "PRIVILEGE_TABLE_GET", "PRIVILEGE_TABLE_LIST");
        // And the shared scheduler thread is handed back unelevated.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ---- operability lines

    @Test
    void startupLogsTheCadenceTheDeploymentRuns() {
        try (LogCapture log = LogCapture.of(AggregateScheduler.class)) {
            scheduler(true).logConfiguration();

            assertThat(log.messagesAt(Level.INFO)).singleElement()
                    .isEqualTo("Aggregate scheduler: enabled=true cron='0 * * * * *' pool=1");
        }
    }

    /**
     * Spring skips a fire that lands while the previous tick is still running, and with a pool of one every
     * aggregate pipeline waits behind it — so a tick that ran past its next fire says so.
     */
    @Test
    void tickThatRunsPastItsNextFireWarns() {
        final ZonedDateTime started = ZonedDateTime.of(2026, 9, 29, 10, 0, 30, 0, ZoneOffset.UTC);
        try (LogCapture log = LogCapture.of(AggregateScheduler.class)) {
            scheduler(true).warnIfOverran(started, Duration.ofSeconds(45));

            assertThat(log.messagesAt(Level.WARN)).singleElement().satisfies(line -> assertThat(line)
                    .contains("45000 ms")
                    .contains("2026-09-29T10:01:00Z")
                    .contains("cron '0 * * * * *'")
                    .contains("pool 1"));
        }
    }

    @Test
    void tickThatEndsBeforeItsNextFireDoesNotWarn() {
        final ZonedDateTime started = ZonedDateTime.of(2026, 9, 29, 10, 0, 30, 0, ZoneOffset.UTC);
        try (LogCapture log = LogCapture.of(AggregateScheduler.class)) {
            scheduler(true).warnIfOverran(started, Duration.ofSeconds(20));

            assertThat(log.messagesAt(Level.WARN)).isEmpty();
        }
    }
}
