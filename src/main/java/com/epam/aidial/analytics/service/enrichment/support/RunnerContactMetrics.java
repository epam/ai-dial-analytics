package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.RunnerContractException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer instrumentation for the Enrichment Runner's side of the data plane: how long since each
 * {@code enrich} pipeline was last called, and how many calls were refused for a contract skew.
 *
 * <p>This exists because ADAS's own output depends on the runner being alive. A Class-1 enrichment's
 * materialized position is what every dependent rollup's freshness clamp reads, so a runner that is down,
 * skewed or refused makes those rollups write zero rows per tick with the clamp naming an enrichment —
 * and nothing else in ADAS distinguishes that from a quiet period.
 *
 * <p><b>Metrics, never a health contributor.</b> The deployment's liveness probe targets the aggregate
 * {@code /actuator/health}, so a contributor reporting DOWN for "no runner" would restart ADAS every few
 * ticks instead of reporting the condition.
 *
 * <h2>Registration is part of the contract, not bookkeeping</h2>
 *
 * <p>The staleness gauge is derived at <b>observation</b> time from a stored instant, following
 * {@code AggregateMetrics}' reasoning: a stored lag value freezes exactly when calls stop, which is the
 * condition the series exists to expose. A value derived that way is only honest while something can still
 * move its input, so:
 *
 * <ul>
 *   <li><b>Registered on first contact.</b> A pipeline the runner has never called has no series, rather
 *       than one climbing since startup.
 *   <li><b>Dropped when the pipeline is deleted <em>or disabled</em></b>, by {@link #forget(String)}.
 *       Disabling is the case with no aggregate analogue and it matters more: the runner's feed is
 *       {@code enabled=true}, so a disabled pipeline leaves it and the runner correctly stops calling. A
 *       climbing "the runner has stopped calling" series for a pipeline an operator deliberately switched
 *       off is a false alarm raised by the intended behaviour — and it would be the loudest series on the
 *       dashboard. Re-enabling or re-creating one re-registers on the next call, measured from a contact
 *       that actually happened.
 *   <li><b>The refusal counters go the other way and are registered at startup</b>, because they are
 *       untagged by pipeline and a counter that appears only once the condition has happened cannot be
 *       alerted on beforehand.
 *   <li><b>The attempt series follows the contact series' rules</b> — registered on the first attempt,
 *       measured at observation time, dropped by {@link #forget(String)} — and differs only in when it
 *       moves: when any call to an existing, enabled {@code enrich} pipeline completes, refused or not.
 *       Together the two separate a runner that is down (both climb) from one that calls and is refused
 *       (attempts stay low while contact climbs). A contract refusal records no attempt, for the reason the
 *       refusals carry no tag.
 *   <li><b>No "contact stale" WARN.</b> Each replica sees only the calls the load balancer routed to it, so a
 *       rarely-routed replica would warn falsely; the signal is the gauge's minimum across replicas.
 *   <li><b>The refusals carry no {@code pipeline} tag.</b> A refusal is decided before the pipeline is
 *       looked up, so the only name in hand is the unvalidated path segment the caller sent — an unbounded
 *       tag whose values are chosen by exactly the client that is already misbehaving.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RunnerContactMetrics {

    /** Seconds since the runner last successfully called one of a pipeline's data-plane endpoints. */
    public static final String STALENESS_GAUGE = "enrichment.runner.contact.staleness.seconds";

    /**
     * Seconds since the runner last <b>attempted</b> one of a pipeline's data-plane endpoints, whatever the
     * outcome; see the class note.
     */
    public static final String ATTEMPT_STALENESS_GAUGE = "enrichment.runner.attempt.staleness.seconds";

    /** Data-plane calls refused for a contract skew, tagged {@code reason}. */
    public static final String REFUSALS_COUNTER = "enrichment.runner.contract.refusals";

    private final MeterRegistry meterRegistry;

    /** Per pipeline, the epoch millis of the last successful data-plane call — what the gauge measures against. */
    private final Map<String, AtomicLong> lastContactByPipeline = new ConcurrentHashMap<>();

    /** Per pipeline, the epoch millis of the last data-plane call attempted — refused or not. */
    private final Map<String, AtomicLong> lastAttemptByPipeline = new ConcurrentHashMap<>();

    /**
     * Registers both refusal counters up front, so an alert can be written against a series that exists
     * before the first skew rather than one that appears only once the damage is being done.
     */
    @PostConstruct
    void registerRefusalCounters() {
        for (final RunnerContractException.Reason reason : RunnerContractException.Reason.values()) {
            refusals(reason);
        }
    }

    /**
     * Records that the runner successfully called one of {@code pipeline}'s data-plane endpoints, now.
     * Registers the pipeline's staleness gauge if this is its first contact.
     */
    public void recordContact(String pipeline) {
        lastContact(pipeline).set(System.currentTimeMillis());
    }

    /**
     * Records that the runner called one of {@code pipeline}'s data-plane endpoints, now, whatever the call's
     * outcome. Call only for a name known to be an existing, enabled {@code enrich} pipeline, so a caller-chosen
     * name never creates a series. Registers the pipeline's attempt gauge if this is its first attempt.
     */
    public void recordAttempt(String pipeline) {
        holder(lastAttemptByPipeline, ATTEMPT_STALENESS_GAUGE,
                "Seconds since the Enrichment Runner last attempted a call to this pipeline's data plane", pipeline)
                .set(System.currentTimeMillis());
    }

    /** Counts one refused data-plane call. Untagged by pipeline; see the class note. */
    public void recordRefusal(RunnerContractException.Reason reason) {
        refusals(reason).increment();
    }

    /**
     * Drops {@code pipeline}'s staleness series, contact and attempt both. Called when the pipeline is
     * deleted — by any path that deletes one — and when it is disabled.
     *
     * <p>Leaving it behind is not a mere leak: the gauge is derived at observation time from an instant
     * nothing will move again, so it climbs forever — raising the exact signal a stalled runner raises, for
     * a pipeline that is either gone or deliberately switched off.
     */
    public void forget(String pipeline) {
        lastContactByPipeline.remove(pipeline);
        meterRegistry.find(STALENESS_GAUGE).tag("pipeline", pipeline).meters().forEach(meterRegistry::remove);
        lastAttemptByPipeline.remove(pipeline);
        meterRegistry.find(ATTEMPT_STALENESS_GAUGE).tag("pipeline", pipeline).meters()
                .forEach(meterRegistry::remove);
    }

    private Counter refusals(RunnerContractException.Reason reason) {
        return Counter.builder(REFUSALS_COUNTER)
                .description("Enrichment Runner data-plane calls refused for a contract version skew")
                .tag("reason", reason.tag())
                .register(meterRegistry);
    }

    private AtomicLong lastContact(String pipeline) {
        return holder(lastContactByPipeline, STALENESS_GAUGE,
                "Seconds since the Enrichment Runner last called this pipeline's data plane", pipeline);
    }

    /** {@code pipeline}'s instant in {@code instants}, registering its gauge on first use. */
    private AtomicLong holder(Map<String, AtomicLong> instants, String gauge, String description, String pipeline) {
        return instants.computeIfAbsent(pipeline, name -> {
            final AtomicLong holder = new AtomicLong();
            Gauge.builder(gauge, holder, RunnerContactMetrics::stalenessOf)
                    .description(description)
                    .baseUnit("seconds")
                    .tag("pipeline", name)
                    .register(meterRegistry);
            return holder;
        });
    }

    /** Seconds between the stored last contact and now; never negative. */
    private static double stalenessOf(AtomicLong lastContactMillis) {
        final long lastContact = lastContactMillis.get();
        return lastContact == 0L ? 0d : Math.max(0d, (System.currentTimeMillis() - lastContact) / 1000d);
    }
}
