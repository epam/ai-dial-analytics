package com.epam.aidial.analytics.service.pipeline;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer instrumentation for aggregate refreshes, named to match the existing {@code sync.*} series.
 *
 * <p>The staleness gauge is the one that matters operationally. A rollup is served from stored rows, so a
 * refresh that silently stopped looks exactly like a quiet period at the query surface — the numbers are
 * simply old, with nothing on the read path to indicate it. Publishing per-pipeline lag makes that
 * visible to alerting instead of only to whoever thinks to call {@code GET /v1/pipelines/{name}}.
 *
 * <p><b>The gauge holds a watermark, not the lag itself.</b> What is stored is the point in the input the
 * last successful run reached ({@code now − lag}); the gauge subtracts it from the current time at
 * <em>scrape</em> time. Storing the lag value directly would freeze the series between runs — for an hourly
 * pipeline, a flat line for an hour and then a jump — and freeze it <em>permanently</em> once refreshes
 * start failing, which is precisely the condition the gauge exists to expose. Deriving it at scrape time
 * makes a stall show up as a line that keeps climbing, with no run needed to move it.
 */
@Component
@RequiredArgsConstructor
public class AggregateMetrics {

    /** The staleness gauge's name; see the class note for why it holds a watermark. */
    private static final String LAG_GAUGE = "aggregate.lag.seconds";

    private final MeterRegistry meterRegistry;

    /**
     * Per pipeline, the input watermark the last successful run reached, in epoch millis — the value the
     * staleness gauge measures the present against.
     */
    private final Map<String, AtomicLong> watermarkByPipeline = new ConcurrentHashMap<>();

    /** The meter names tagged by pipeline, so a deregistration cannot miss one this class registers. */
    private static final List<String> SERIES = List.of(
            "aggregate.refresh.runs", "aggregate.refresh.rows.written", "aggregate.refresh.duration",
            LAG_GAUGE);

    /** Records one completed refresh: its outcome, rows written, duration, and resulting staleness. */
    public void recordRun(String pipeline, boolean success, long rowsWritten, Duration duration, long lagSeconds) {
        meterRegistry.counter("aggregate.refresh.runs",
                "pipeline", pipeline, "outcome", success ? "success" : "failure").increment();
        if (rowsWritten > 0) {
            meterRegistry.counter("aggregate.refresh.rows.written", "pipeline", pipeline).increment(rowsWritten);
        }
        Timer.builder("aggregate.refresh.duration").tag("pipeline", pipeline)
                .register(meterRegistry).record(duration);
        if (success) {
            // A failed run deliberately leaves the watermark alone, so the gauge keeps climbing from the
            // last point actually reached rather than resetting to a lag nobody achieved.
            watermark(pipeline).set(System.currentTimeMillis() - lagSeconds * 1000L);
        }
    }

    /**
     * Drops every series this class registered for {@code pipeline}. Called when a pipeline is deleted.
     *
     * <p>The staleness gauge is the reason this exists, and leaving it behind is not a mere leak: it is
     * derived at scrape time from a watermark that nothing will ever move again, so a deleted pipeline's lag
     * <em>keeps climbing forever</em> — the exact signal a stall is alerted on, raised permanently for a
     * pipeline that no longer exists. The counters and the timer go with it so the name stops appearing
     * entirely rather than half-way.
     */
    public void forget(String pipeline) {
        watermarkByPipeline.remove(pipeline);
        for (final String name : SERIES) {
            meterRegistry.find(name).tag("pipeline", pipeline).meters().forEach(meterRegistry::remove);
        }
    }

    /**
     * The per-pipeline staleness gauge, registered once on first use. Registering lazily keeps the
     * registry free of series for pipelines that do not exist.
     */
    private AtomicLong watermark(String pipeline) {
        return watermarkByPipeline.computeIfAbsent(pipeline, name -> {
            final AtomicLong holder = new AtomicLong();
            Gauge.builder(LAG_GAUGE, holder, AggregateMetrics::stalenessOf)
                    .tag("pipeline", name)
                    .register(meterRegistry);
            return holder;
        });
    }

    /** Seconds between the held watermark and now; never negative, and 0 before the first successful run. */
    private static double stalenessOf(AtomicLong watermarkMillis) {
        final long watermark = watermarkMillis.get();
        return watermark == 0L ? 0d : Math.max(0d, (System.currentTimeMillis() - watermark) / 1000d);
    }
}
