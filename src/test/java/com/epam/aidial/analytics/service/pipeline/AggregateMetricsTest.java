package com.epam.aidial.analytics.service.pipeline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refresh instrumentation, and specifically that a pipeline's series end when the pipeline does.
 *
 * <p>The staleness gauge is the one that matters: it is derived at <em>scrape</em> time from a stored
 * watermark, which is what makes a stall render as a line that keeps climbing without a run needing to
 * occur. The same property is why a series left behind after a delete is not a mere leak — it climbs
 * forever on a pipeline that no longer exists, raising the exact signal a stall is alerted on.
 */
class AggregateMetricsTest {

    private static final String PIPELINE = "usage_daily_rollup";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AggregateMetrics metrics = new AggregateMetrics(registry);

    @Test
    void recordsRunSeriesPerPipeline() {
        metrics.recordRun(PIPELINE, true, 7L, Duration.ofMillis(120), 30L);

        assertThat(registry.counter("aggregate.refresh.runs", "pipeline", PIPELINE, "outcome", "success").count())
                .isEqualTo(1d);
        assertThat(registry.counter("aggregate.refresh.rows.written", "pipeline", PIPELINE).count())
                .isEqualTo(7d);
        assertThat(registry.find("aggregate.refresh.duration").tag("pipeline", PIPELINE).timer()).isNotNull();
        // The gauge measures the present against the watermark the run reached, so it reads back the lag.
        assertThat(registry.find("aggregate.lag.seconds").tag("pipeline", PIPELINE).gauge().value())
                .isCloseTo(30d, org.assertj.core.data.Offset.offset(2d));
    }

    @Test
    void failedRunLeavesTheWatermarkWhereItWas() {
        metrics.recordRun(PIPELINE, true, 1L, Duration.ofMillis(5), 100L);
        metrics.recordRun(PIPELINE, false, 0L, Duration.ofMillis(5), 0L);

        // A failure must not reset the gauge to a lag nobody achieved — it keeps climbing from the last
        // position actually reached, which is what makes a stall visible.
        assertThat(registry.find("aggregate.lag.seconds").tag("pipeline", PIPELINE).gauge().value())
                .isGreaterThanOrEqualTo(100d);
        assertThat(registry.counter("aggregate.refresh.runs", "pipeline", PIPELINE, "outcome", "failure").count())
                .isEqualTo(1d);
    }

    @Test
    void forgetRemovesEverySeriesForThePipeline() {
        metrics.recordRun(PIPELINE, true, 3L, Duration.ofMillis(9), 42L);
        metrics.recordRun("other", true, 3L, Duration.ofMillis(9), 42L);

        metrics.forget(PIPELINE);

        assertThat(registry.getMeters())
                .as("no series may survive for a deleted pipeline")
                .noneMatch(meter -> PIPELINE.equals(meter.getId().getTag("pipeline")));
        // Only the named pipeline's series go: a delete is not a registry reset.
        assertThat(registry.find("aggregate.lag.seconds").tag("pipeline", "other").gauge()).isNotNull();
    }

    @Test
    void forgettingIsRepeatableAndReRegisters() {
        metrics.forget(PIPELINE);
        metrics.recordRun(PIPELINE, true, 1L, Duration.ofMillis(1), 0L);
        metrics.forget(PIPELINE);
        metrics.recordRun(PIPELINE, true, 1L, Duration.ofMillis(1), 0L);

        // The watermark holder is dropped alongside the gauge, so a name re-registered later gets a live
        // gauge rather than one bound to a holder no longer in the map.
        assertThat(registry.find("aggregate.lag.seconds").tag("pipeline", PIPELINE).gauge()).isNotNull();
    }
}
