package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.RunnerContractException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runner-contact instrumentation, and specifically the registration rules — which carry as much weight
 * here as the value does.
 *
 * <p>The staleness gauge is derived at observation time from a stored instant, which is what makes a runner
 * that has stopped calling render as a line that keeps climbing with no call needed to move it. The same
 * property is why the series must not outlive the thing that moves it: one left behind for a deleted or
 * disabled pipeline climbs forever and raises exactly the alarm a stalled runner raises.
 */
class RunnerContactMetricsTest {

    private static final String PIPELINE = "usage-sentiment-live";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RunnerContactMetrics metrics = new RunnerContactMetrics(registry);

    @BeforeEach
    void registerStartupSeries() {
        // What @PostConstruct does in the container.
        metrics.registerRefusalCounters();
    }

    /**
     * A pipeline the runner has never called has no series, rather than one climbing since startup — the
     * gauge would otherwise report "the runner has stopped calling" for a pipeline it has not yet reached.
     */
    @Test
    void noStalenessSeriesBeforeTheFirstContact() {
        assertThat(registry.find(RunnerContactMetrics.STALENESS_GAUGE).tag("pipeline", PIPELINE).gauge())
                .isNull();
    }

    @Test
    void stalenessGrowsBetweenObservationsWithNoCallMade() throws Exception {
        metrics.recordContact(PIPELINE);
        final double first = staleness();

        Thread.sleep(20L);

        // Nothing happened in between. That is the point: a stored lag value would be flat here and would
        // freeze permanently once the runner stopped, which is the condition this series exists to expose.
        assertThat(staleness()).isGreaterThan(first);
    }

    @Test
    void laterContactResetsTheStaleness() throws Exception {
        metrics.recordContact(PIPELINE);
        Thread.sleep(20L);
        final double stale = staleness();

        metrics.recordContact(PIPELINE);

        assertThat(staleness()).isLessThan(stale);
    }

    /**
     * The counters exist before the first skew, because they are untagged by pipeline and an alert cannot
     * be written against a series that appears only once the damage is being done.
     */
    @Test
    void refusalCountersExistAtZeroBeforeAnyRefusal() {
        assertThat(counter("required")).isZero();
        assertThat(counter("mismatch")).isZero();
    }

    @Test
    void refusalIsCountedUnderItsOwnReason() {
        metrics.recordRefusal(RunnerContractException.Reason.REQUIRED);
        metrics.recordRefusal(RunnerContractException.Reason.MISMATCH);
        metrics.recordRefusal(RunnerContractException.Reason.MISMATCH);

        assertThat(counter("required")).isEqualTo(1d);
        assertThat(counter("mismatch")).isEqualTo(2d);
    }

    /**
     * The refusal is decided before the pipeline is looked up, so the only name in hand is the unvalidated
     * path segment the caller sent. Tagging it would let a skewed — or hostile — client mint registry series
     * at will, so the counters are not keyed by it and the registry gains nothing per refusal.
     */
    @Test
    void refusalsMintNoNewSeries() {
        final int before = registry.getMeters().size();

        for (int i = 0; i < 50; i++) {
            metrics.recordRefusal(RunnerContractException.Reason.MISMATCH);
        }

        assertThat(registry.getMeters()).hasSize(before);
        assertThat(registry.find(RunnerContactMetrics.REFUSALS_COUNTER).meters())
                .allSatisfy(meter -> assertThat(meter.getId().getTag("pipeline")).isNull());
    }

    /** Deletion and disabling take the same path; the service calls this from both. */
    @Test
    void forgetRemovesTheSeriesForThatPipelineOnly() {
        metrics.recordContact(PIPELINE);
        metrics.recordContact("other-live");

        metrics.forget(PIPELINE);

        assertThat(registry.getMeters())
                .as("no series may survive for a pipeline the runner will not be called for again")
                .noneMatch(meter -> PIPELINE.equals(meter.getId().getTag("pipeline")));
        assertThat(registry.find(RunnerContactMetrics.STALENESS_GAUGE).tag("pipeline", "other-live").gauge())
                .isNotNull();
    }

    /**
     * Re-enabling or re-creating a pipeline re-registers on the next call, measured from a contact that
     * actually happened — which only works if the stored instant is dropped alongside the gauge rather than
     * left bound to a holder no longer in the map.
     */
    @Test
    void forgettingIsRepeatableAndReRegisters() {
        metrics.forget(PIPELINE);
        metrics.recordContact(PIPELINE);
        metrics.forget(PIPELINE);
        metrics.recordContact(PIPELINE);

        assertThat(staleness()).isGreaterThanOrEqualTo(0d).isLessThan(5d);
    }

    @Test
    void noAttemptSeriesBeforeTheFirstAttempt() {
        metrics.recordContact(PIPELINE);

        // recordContact never creates the attempt series: the two are independent.
        assertThat(registry.find(RunnerContactMetrics.ATTEMPT_STALENESS_GAUGE).tag("pipeline", PIPELINE).gauge())
                .isNull();
    }

    /**
     * A runner that keeps calling and is refused: attempts are reset while contact keeps climbing — the
     * pattern that separates "refused" from "down".
     */
    @Test
    void anAttemptMovesOnlyTheAttemptSeries() throws Exception {
        metrics.recordContact(PIPELINE);
        metrics.recordAttempt(PIPELINE);
        Thread.sleep(20L);
        final double contactBefore = staleness();
        final double attemptBefore = attemptStaleness();

        metrics.recordAttempt(PIPELINE);

        assertThat(attemptStaleness()).isLessThan(attemptBefore);
        assertThat(staleness()).isGreaterThanOrEqualTo(contactBefore);
    }

    /** Delete and disable both call forget, and neither series may survive it. */
    @Test
    void forgetRemovesBothSeries() {
        metrics.recordContact(PIPELINE);
        metrics.recordAttempt(PIPELINE);
        metrics.recordAttempt("other-live");

        metrics.forget(PIPELINE);

        assertThat(registry.find(RunnerContactMetrics.STALENESS_GAUGE).tag("pipeline", PIPELINE).gauge()).isNull();
        assertThat(registry.find(RunnerContactMetrics.ATTEMPT_STALENESS_GAUGE).tag("pipeline", PIPELINE).gauge())
                .isNull();
        assertThat(registry.find(RunnerContactMetrics.ATTEMPT_STALENESS_GAUGE).tag("pipeline", "other-live").gauge())
                .isNotNull();

        metrics.recordAttempt(PIPELINE);
        assertThat(attemptStaleness()).isGreaterThanOrEqualTo(0d).isLessThan(5d);
    }

    private double attemptStaleness() {
        return registry.get(RunnerContactMetrics.ATTEMPT_STALENESS_GAUGE).tag("pipeline", PIPELINE).gauge().value();
    }

    private double staleness() {
        return registry.get(RunnerContactMetrics.STALENESS_GAUGE).tag("pipeline", PIPELINE).gauge().value();
    }

    private double counter(String reason) {
        return registry.get(RunnerContactMetrics.REFUSALS_COUNTER).tag("reason", reason).counter().count();
    }
}
