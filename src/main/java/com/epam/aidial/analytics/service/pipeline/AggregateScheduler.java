package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.configuration.AggregateProperties;
import com.epam.aidial.analytics.exception.PipelineConflictException;
import com.epam.aidial.analytics.exception.PipelineNotFoundException;
import com.epam.aidial.analytics.exception.PipelineReadBudgetExceededException;
import com.epam.aidial.analytics.service.pipeline.model.RefreshOutcome;
import com.epam.aidial.analytics.service.security.SystemIdentity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Drives {@code periodic} aggregate refreshes. One tick selects the enabled pipelines whose
 * {@code next_run_at} has passed, refreshes each, and re-arms it from its own cron. A tick plus a
 * due-check (rather than registering a dynamic trigger per pipeline) is restart-safe and needs no
 * rescheduling when a pipeline is registered, patched, enabled, or disabled — the state that decides
 * whether it runs lives in the row, not in a scheduler registry.
 *
 * <p><b>No leader election, deliberately.</b> Two instances may refresh the same pipeline on the same
 * tick. Both recompute the same group keys from the same committed input and write the same values, and
 * the target — a {@code ReplacingMergeTree} on the group key — collapses the duplicate. Correctness rests
 * on the recompute being idempotent rather than on coordination, which is the same argument
 * {@code DataSyncService} and {@code BlobPollingService} already run on. Duplicated work is wasted, not
 * wrong; advisory locking could be added later without touching the contract.
 *
 * <p>A failing pipeline is isolated: the tick continues, because one bad declaration must not stop every
 * other rollup from being maintained. A pipeline that was disabled or deleted between due-selection and
 * execution is not a failure at all and is skipped quietly. The error itself is recorded by
 * {@link AggregateRefreshService#refresh}, which is also the on-demand path — so a stalled pipeline reads
 * the same in {@code GET /v1/pipelines/{name}} whichever route triggered the refresh.
 *
 * <p><b>Each refresh runs under a system identity</b> ({@link SystemIdentity}). Column-level governance is
 * decided from the calling identity, and a scheduler thread has no caller — so without this a pipeline over
 * a {@code sensitive} column would validate, preview, and refresh on demand yet fail on every tick, only in
 * a secured deployment. The elevation is scoped per pipeline and always unwound, because one tick refreshes
 * many pipelines on the same thread and an elevation left in place would silently pass to whichever ran
 * next.
 *
 * <p><b>The tick has its own scheduler</b> ({@code aggregateTaskScheduler}), not the pool the blob poller
 * and the data-sync job share. A refresh's duration is unbounded by design, so on a shared pool a slow
 * recompute occupies a thread ingestion needs — and a late rollup only serves a stale number, where late
 * ingestion loses ground against its source. See {@code SchedulingConfiguration}.
 *
 * <p>A pipeline whose refresh filled its window limit is re-armed to <b>now</b> rather than to its cron,
 * so a backlog drains at the tick's rate instead of the pipeline's own cadence (see
 * {@code safeReArm}). One window per tick, never a loop inside one tick.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AggregateScheduler {

    private final PipelineRepository pipelines;
    private final AggregateRefreshService refreshService;
    private final PipelineStateWriter stateWriter;
    private final SystemIdentity systemIdentity;
    private final AggregateProperties properties;

    /**
     * Scheduled entry point. Cadence is env-configurable via {@code aggregate.tick-cron}, and it runs on
     * the aggregate's own scheduler rather than the pool the ingestion jobs share — see
     * {@code SchedulingConfiguration} for why, and for the Boot auto-configuration trap that comes with it.
     */
    @Scheduled(cron = "${aggregate.tick-cron}", scheduler = "aggregateTaskScheduler")
    public void tick() {
        if (!properties.enabled()) {
            return;
        }
        // The zone @Scheduled evaluates its cron in, so the next fire computed below is the one it skips.
        final ZonedDateTime started = ZonedDateTime.now();
        final long startNanos = System.nanoTime();
        try {
            runDue();
        } finally {
            warnIfOverran(started, Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    /**
     * The cadence this deployment actually runs, readable from its logs: whether the tick runs at all, its
     * cron, and how many refreshes can run at once.
     */
    @PostConstruct
    void logConfiguration() {
        log.info("Aggregate scheduler: enabled={} cron='{}' pool={}",
                properties.enabled(), properties.tickCron(), properties.schedulerPoolSize());
    }

    /**
     * WARNs when a tick ran past its own next scheduled fire. Spring does not overlap fires of one
     * {@code @Scheduled} method, so that fire is skipped without a trace — and with the default pool of one,
     * every aggregate pipeline waited behind whichever refresh made the tick slow. The per-tick summary alone
     * says how long the tick took, not that a slot was lost. Package-visible so tests can drive it.
     */
    void warnIfOverran(ZonedDateTime started, Duration took) {
        final ZonedDateTime next;
        try {
            next = CronExpression.parse(properties.tickCron()).next(started);
        } catch (IllegalArgumentException ex) {
            return;   // unreachable in practice: @Scheduled refuses to start on the same cron
        }
        if (next != null && started.plus(took).isAfter(next)) {
            log.warn("Aggregate tick took {} ms and ran past its next scheduled fire at {} (cron '{}', pool {}); "
                            + "that fire was skipped, and every aggregate pipeline waited behind this tick",
                    took.toMillis(), next.toInstant(), properties.tickCron(), properties.schedulerPoolSize());
        }
    }

    /**
     * Refreshes every due pipeline once, re-arming each from its cron. Package-visible so tests can drive
     * it without the scheduler.
     *
     * <p>Emits one summary line per tick, and <b>only when something was due</b>. The tick's cadence is
     * configurable down to a minute (and defaults to it), so an unconditional line would be ~1,440 entries
     * a day per replica saying nothing happened — which trains an operator to filter the very logger the
     * useful line arrives on. Logging only non-empty ticks makes the line's presence informative in itself.
     *
     * @return how many pipelines were refreshed (successfully or not)
     */
    int runDue() {
        final List<PipelineEntity> due = pipelines.findDue(Instant.now());
        if (due.isEmpty()) {
            return 0;
        }
        final long startNanos = System.nanoTime();
        final TickCounts counts = new TickCounts();
        for (final PipelineEntity pipeline : due) {
            // Each pipeline gets its own elevation scope, so one failing mid-refresh cannot leave the next
            // one — or whatever else shares this scheduler thread — running with a borrowed identity.
            systemIdentity.runAsSystem(() -> refreshOne(pipeline, counts));
        }
        log.info("Aggregate tick finished in {} ms: {} due, {} refreshed, {} skipped, {} failed",
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis(),
                due.size(), counts.refreshed, counts.skipped, counts.failed);
        return due.size();
    }

    /** Per-tick outcome tally, for the summary line. Confined to one tick on one thread. */
    private static final class TickCounts {
        private int refreshed;
        private int skipped;
        private int failed;
    }

    private void refreshOne(PipelineEntity pipeline, TickCounts counts) {
        final String name = pipeline.getName();
        boolean hasMore = false;
        try {
            final RefreshOutcome outcome = refreshService.refresh(name);
            counts.refreshed++;
            hasMore = outcome.hasMore();
            if (hasMore) {
                log.debug("Pipeline '{}' has more input after {} row(s); due again next tick",
                        name, outcome.rowsWritten());
            }
        } catch (PipelineConflictException | PipelineNotFoundException ex) {
            // Disabled or deleted between due-selection and execution — findDue's rows are read outside the
            // refresh, so both races are ordinary. Neither is a failure of the pipeline and neither is
            // something to re-arm: a disable already disarmed it and overwriting that would leave it armed,
            // and a deleted pipeline has no row to re-arm (the attempt would only log a second warning).
            counts.skipped++;
            log.debug("Pipeline '{}' is no longer eligible for this tick: {}", name, ex.getMessage());
            return;
        } catch (PipelineReadBudgetExceededException ex) {
            // A self-describing refusal: the message names the ceiling, its value and the remedy, and the same
            // window is refused on every tick until the declaration or the budget changes. A stack per tick
            // would say nothing the message does not, so it is at DEBUG.
            counts.failed++;
            log.warn("Refresh of pipeline '{}' failed: {}", name, ex.getMessage());
            log.debug("Refresh of pipeline '{}' refused by the read budget", name, ex);
        } catch (RuntimeException ex) {
            // Isolate the failure and keep the tick going. Recording it against the pipeline is the refresh
            // service's job — it does the same for an on-demand run, so the state view reads the same
            // whichever route triggered the refresh. Logged with the throwable, because an unexpected
            // failure's message alone (an NPE's is null) says nothing an operator can act on.
            counts.failed++;
            log.warn("Refresh of pipeline '{}' failed", name, ex);
        } finally {
            // The refresh's statements published their query id for the lines logged after them; on a
            // scheduler thread nothing else clears it, and the next pipeline must not inherit it.
            StatementTag.clear();
        }
        safeReArm(pipeline, hasMore);
    }

    /**
     * Re-arms the pipeline; a bad cron disarms it rather than wedging the tick.
     *
     * <p>A run that filled its window limit left input behind, so it is re-armed to <b>now</b> — due on the
     * very next tick — instead of waiting for its cron. Otherwise the drain rate would be one window per
     * <em>cron slot</em> rather than per tick, which for an hourly pipeline is the difference between
     * ~100k input rows an hour and ~100k a minute: a backlog (or an input arriving faster than that) would
     * never be worked off, and the only symptom would be a rollup answering queries with permanently stale
     * numbers. The cron is not the right rate limiter for a pipeline that is behind — the window limit
     * already bounds what one run costs.
     *
     * <p>This cannot spin: the pipeline becomes due, but nothing re-enters until the next tick. Because
     * {@code findDue} orders by {@code next_run_at}, a lagging pipeline is served first on each tick while
     * the others still run behind it in the same pass.
     */
    private void safeReArm(PipelineEntity pipeline, boolean hasMore) {
        final String name = pipeline.getName();
        Instant next;
        try {
            next = hasMore ? Instant.now() : nextRun(pipeline.getTriggerCron());
        } catch (RuntimeException ex) {
            // A cron that will not parse cannot yield a next firing, and leaving next_run_at at its stale
            // past value would leave the pipeline due on *every* tick — a full refresh each time, forever.
            // Disarming stops that; the recorded error is what an operator sees and fixes.
            log.warn("Pipeline '{}' has an unusable cron '{}' and is disarmed: {}",
                    name, pipeline.getTriggerCron(), ex.getMessage());
            safeRecordFailure(name, "trigger.cron '" + pipeline.getTriggerCron() + "' cannot be evaluated: "
                    + ex.getMessage());
            next = null;
        }
        try {
            stateWriter.setNextRunAt(name, next);
        } catch (RuntimeException ex) {
            log.warn("Could not re-arm pipeline '{}': {}", name, ex.getMessage());
        }
    }

    private void safeRecordFailure(String name, String message) {
        try {
            stateWriter.recordFailure(name, message);
        } catch (RuntimeException ex) {
            log.warn("Could not record the failure of pipeline '{}': {}", name, ex.getMessage());
        }
    }

    /**
     * The next firing of {@code cron} after now, or null when it has none (a cron that never fires
     * disarms the pipeline instead of being retried every tick).
     *
     * <p>Evaluated in <b>UTC</b>, not the JVM default zone: a pipeline's cadence is a property of the
     * pipeline, and resolving it against wherever the instance happens to run would make the same
     * declaration fire at different wall-clock times on different replicas — and shift when one is
     * redeployed. Documented for operators in {@code docs/configuration.md}.
     */
    static Instant nextRun(String cron) {
        final ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.now(ZoneOffset.UTC));
        return next == null ? null : next.toInstant();
    }
}
