package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.observability.CursorPosition;
import com.epam.aidial.analytics.observability.TransitionLog;
import com.epam.aidial.analytics.service.pipeline.EnrichmentFreshness;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * The materialize outcome line and the soft-deadline WARN, on {@link MaterializeService}'s logger.
 *
 * <p>Neither line carries a row value, a filter literal or SQL. The one value that comes from a source row is
 * the clamp bound's identity half, and that is logged as a short fingerprint rather than the value, as every
 * position is (see {@link CursorPosition}). The fingerprint still tells an operator whether the bound moved
 * between two lines.
 */
@Component
@RequiredArgsConstructor
class MaterializeOutcomeLog {

    private static final Logger LOG = LoggerFactory.getLogger(MaterializeService.class);

    private static final String NONE = "-";

    private final TransitionLog transitions;

    /** What a materialize call that reached its probe (or was withheld before it) ended with. */
    enum Outcome {
        WRITTEN, DRAINED, WITHHELD, CLAMPED_EMPTY;

        String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One call's outcome.
     *
     * @param call  the call's clock, or {@code null} when no statement ran ({@code withheld})
     * @param clamp the freshness bound applied to the window, or {@code null} when none was
     */
    record Report(
            String pipeline,
            long generation,
            Outcome outcome,
            long rows,
            boolean narrowed,
            boolean hasMore,
            Long lagSeconds,
            MaterializeCall call,
            EnrichmentFreshness.Bound clamp) {

        /** A call that wrote nothing ({@code drained}, {@code withheld}, {@code clamped_empty}). */
        static Report empty(String pipeline, long generation, Outcome outcome, MaterializeCall call,
                EnrichmentFreshness.Bound clamp) {
            return new Report(pipeline, generation, outcome, 0L, false, false, null, call, clamp);
        }
    }

    /**
     * Logs the outcome line: always for {@code written}, and for the other outcomes only when the pipeline's
     * previous outcome on this replica was a different one. A caught-up or blocked pipeline is called on every
     * runner cadence, and the line is there to show the change.
     */
    void outcome(Report report) {
        final String code = report.outcome().code();
        final String previous = transitions.swap(TransitionLog.Channel.MATERIALIZE, report.pipeline(), code);
        if (report.outcome() != Outcome.WRITTEN && code.equals(previous)) {
            return;
        }
        final MaterializeCall call = report.call();
        final EnrichmentFreshness.Bound clamp = report.clamp();
        LOG.info("materialize pipeline={} gen={} outcome={} rows={} narrowed={} has_more={} lag_s={} probe_ms={} "
                        + "reprobe_ms={} write_ms={} total_ms={} clamp_by={} clamp_to={} query_id={}",
                report.pipeline(), report.generation(), code, report.rows(), report.narrowed(), report.hasMore(),
                report.lagSeconds() == null ? NONE : report.lagSeconds(),
                call == null ? 0L : call.probeMs(),
                call == null ? 0L : call.reprobeMs(),
                call == null ? 0L : call.writeMs(),
                call == null ? 0L : call.totalMs(),
                clamp == null ? NONE : clamp.enrichment(),
                clamp == null ? CursorPosition.of(null, null) : CursorPosition.of(clamp.version(), clamp.identity()),
                call == null ? NONE : orNone(StatementTag.current()));
    }

    /**
     * Logs one WARN when the call has run past half of {@code timeout}, successful or not. Under the single
     * per-call deadline the timeout is the call's upper bound, so half of it is where one more slow statement
     * reaches the refusal; the split says which stage spent it.
     */
    void softDeadline(String pipeline, MaterializeCall call, Duration timeout, boolean failed) {
        final long total = call.totalMs();
        if (total <= timeout.toMillis() / 2) {
            return;
        }
        LOG.warn("materialize pipeline={} ran past half of its budget: probe_ms={} reprobe_ms={} write_ms={} total_ms={} "
                        + "timeout={} failed={}",
                pipeline, call.probeMs(), call.reprobeMs(), call.writeMs(), total, timeout, failed);
    }

    private static String orNone(String value) {
        return value == null ? NONE : value;
    }
}
