package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.observability.CursorPosition;
import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.observability.TransitionLog;
import com.epam.aidial.analytics.service.enrichment.MaterializeOutcomeLog.Outcome;
import com.epam.aidial.analytics.service.enrichment.MaterializeOutcomeLog.Report;
import com.epam.aidial.analytics.service.pipeline.EnrichmentFreshness;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The materialize outcome line: one per call that reached its probe, with the stage split and the last
 * statement's query id, logged every time for {@code written} and on change for the rest — and carrying no
 * value that came out of a source row.
 */
class MaterializeOutcomeLogTest {

    private static final String PIPELINE = "usage-client-identity-live";
    private static final String IDENTITY = "user-42@example.com";

    private final TransitionLog transitions = new TransitionLog();
    private final MaterializeOutcomeLog outcomes = new MaterializeOutcomeLog(transitions);
    private final AtomicLong nanos = new AtomicLong();

    @AfterEach
    void clearMdc() {
        MDC.remove(StatementTag.MDC_KEY);
    }

    /** A call whose probe, re-probe and write took 40, 15 and 120 ms, and 180 ms in all. */
    private MaterializeCall call() {
        MaterializeCall call = new MaterializeCall(nanos::get);
        call.time(MaterializeCall.Stage.PROBE, () -> nanos.addAndGet(40_000_000L));
        call.time(MaterializeCall.Stage.REPROBE, () -> nanos.addAndGet(15_000_000L));
        call.time(MaterializeCall.Stage.WRITE, () -> nanos.addAndGet(120_000_000L));
        nanos.addAndGet(5_000_000L);
        return call;
    }

    private static EnrichmentFreshness.Bound bound() {
        return new EnrichmentFreshness.Bound("usage_client_identity", 1_700_000_000_000L, IDENTITY);
    }

    private Report report(Outcome outcome, MaterializeCall call, EnrichmentFreshness.Bound clamp) {
        return new Report(PIPELINE, 4L, outcome, outcome == Outcome.WRITTEN ? 250L : 0L,
                outcome == Outcome.WRITTEN, outcome == Outcome.WRITTEN, outcome == Outcome.WRITTEN ? 12L : null,
                call, clamp);
    }

    @Test
    void writtenWindowIsLoggedWithItsStageSplitAndQueryId() {
        MDC.put(StatementTag.MDC_KEY, "adas-" + PIPELINE + "-write-abc");
        try (LogCapture log = LogCapture.of(MaterializeService.class)) {
            outcomes.outcome(report(Outcome.WRITTEN, call(), null));

            assertThat(log.messagesAt(Level.INFO)).singleElement().isEqualTo("materialize pipeline=" + PIPELINE
                    + " gen=4 outcome=written rows=250 narrowed=true has_more=true lag_s=12 probe_ms=40 reprobe_ms=15"
                    + " write_ms=120 total_ms=180 clamp_by=- clamp_to=(-,-) query_id=adas-" + PIPELINE + "-write-abc");
        }
    }

    @Test
    void everyOutcomeIsNamed() {
        try (LogCapture log = LogCapture.of(MaterializeService.class)) {
            outcomes.outcome(report(Outcome.WRITTEN, call(), null));
            outcomes.outcome(report(Outcome.DRAINED, call(), null));
            outcomes.outcome(report(Outcome.CLAMPED_EMPTY, call(), bound()));
            outcomes.outcome(report(Outcome.WITHHELD, null, new EnrichmentFreshness.Bound("usage_client_identity", null, null)));

            assertThat(log.messagesAt(Level.INFO)).hasSize(4).satisfiesExactly(
                    line -> assertThat(line).contains("outcome=written"),
                    line -> assertThat(line).contains("outcome=drained").contains("lag_s=-"),
                    line -> assertThat(line).contains("outcome=clamped_empty")
                            .contains("clamp_by=usage_client_identity clamp_to=(1700000000000,#"),
                    // No statement ran, so the line reports a zero total and no query id.
                    line -> assertThat(line).contains("outcome=withheld").contains("total_ms=0")
                            .contains("clamp_by=usage_client_identity clamp_to=(-,-)").endsWith("query_id=-"));
        }
    }

    @Test
    void repeatedWithheldOutcomeIsLoggedOnlyWhenItStarts() {
        EnrichmentFreshness.Bound unmaterialized = new EnrichmentFreshness.Bound("usage_client_identity", null, null);
        try (LogCapture log = LogCapture.of(MaterializeService.class)) {
            outcomes.outcome(report(Outcome.WITHHELD, null, unmaterialized));
            outcomes.outcome(report(Outcome.WITHHELD, null, unmaterialized));
            outcomes.outcome(report(Outcome.WITHHELD, null, unmaterialized));
            assertThat(log.messagesAt(Level.INFO)).hasSize(1);

            // The next different outcome is logged, and so is a return to withheld after it.
            outcomes.outcome(report(Outcome.WRITTEN, call(), null));
            outcomes.outcome(report(Outcome.WRITTEN, call(), null));
            outcomes.outcome(report(Outcome.WITHHELD, null, unmaterialized));

            assertThat(log.messagesAt(Level.INFO)).hasSize(4);
        }
    }

    @Test
    void forgottenPipelineLogsItsNextOutcomeAgain() {
        try (LogCapture log = LogCapture.of(MaterializeService.class)) {
            outcomes.outcome(report(Outcome.DRAINED, call(), null));
            transitions.forget(PIPELINE);
            outcomes.outcome(report(Outcome.DRAINED, call(), null));

            assertThat(log.messagesAt(Level.INFO)).hasSize(2);
        }
    }

    @Test
    void theClampIdentityIsFingerprintedNeverLogged() {
        try (LogCapture log = LogCapture.of(MaterializeService.class)) {
            outcomes.outcome(report(Outcome.WRITTEN, call(), bound()));
            outcomes.outcome(report(Outcome.CLAMPED_EMPTY, call(), bound()));
            outcomes.softDeadline(PIPELINE, call(), Duration.ofMillis(100), false);

            assertThat(log.eventsAt(Level.INFO)).hasSize(2);
            assertThat(log.messagesAt(Level.INFO)).allSatisfy(line -> assertThat(line)
                    .doesNotContain(IDENTITY)
                    .doesNotContain("user-42")
                    .contains(CursorPosition.fingerprint(IDENTITY)));
            assertThat(log.messagesAt(Level.WARN)).allSatisfy(line -> assertThat(line).doesNotContain(IDENTITY));
        }
        // Stable, so an operator can tell a bound that moved from one that did not.
        assertThat(CursorPosition.fingerprint(IDENTITY)).isEqualTo(CursorPosition.fingerprint(IDENTITY))
                .isNotEqualTo(CursorPosition.fingerprint("user-43@example.com"))
                .matches("#[0-9a-f]{8}");
    }

    @Test
    void callPastHalfItsBudgetWarnsWithItsStageSplit() {
        try (LogCapture log = LogCapture.of(MaterializeService.class)) {
            outcomes.softDeadline(PIPELINE, call(), Duration.ofMillis(300), true);

            assertThat(log.messagesAt(Level.WARN)).singleElement().isEqualTo("materialize pipeline=" + PIPELINE
                    + " ran past half of its budget: probe_ms=40 reprobe_ms=15 write_ms=120 total_ms=180 timeout=PT0.3S"
                    + " failed=true");
        }
    }

    @Test
    void fastCallDoesNotWarn() {
        try (LogCapture log = LogCapture.of(MaterializeService.class)) {
            outcomes.softDeadline(PIPELINE, call(), Duration.ofSeconds(120), false);

            assertThat(log.messagesAt(Level.WARN)).isEmpty();
        }
    }
}
