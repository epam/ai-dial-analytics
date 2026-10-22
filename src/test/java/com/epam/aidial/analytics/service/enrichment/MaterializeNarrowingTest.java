package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.exception.PipelineReadBudgetExceededException;
import com.epam.aidial.analytics.service.enrichment.MaterializeService.Narrowed;
import com.epam.aidial.analytics.service.enrichment.MaterializeService.Window;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the Class-1 materialize write does when the resource budget refuses it.
 *
 * <p>The write is bounded like every other statement, which on its own would turn a legitimately large
 * window into a dead end: the caller cannot narrow it, because ADAS picks the window. Narrowing is the
 * escape, and the parts of it a caller actually depends on are the ones asserted here — that a narrowed
 * call reports {@code hasMore}, that the cursor describes what was written rather than what was probed,
 * and that a refusal at one row is surfaced rather than retried forever.
 */
class MaterializeNarrowingTest {

    private static final String PIPELINE = "usage-client-identity-live";

    private static final PipelineBudgetProperties BUDGET =
            new PipelineBudgetProperties(Duration.ofSeconds(30), org.springframework.util.unit.DataSize.ofGigabytes(2));

    /** A clock that has not moved, for the cases where the deadline is not what is under test. */
    private static final java.util.function.Supplier<Duration> FRESH = () -> Duration.ZERO;

    /** A window the probe filled, so narrowing has room to halve it. */
    private static Window window(long version) {
        return new Window(version, "id-" + version, (int) version, true);
    }

    /** A window the probe did NOT fill: fewer rows matched than the limit allowed. */
    private static Window underFilled(long version, int rows) {
        return new Window(version, "id-" + version, rows, false);
    }

    private static PipelineReadBudgetExceededException refused() {
        return new PipelineReadBudgetExceededException("exceeded the ceiling", null);
    }

    @Test
    void writeThatFitsIsNotNarrowed() {
        Window probed = window(100);
        Narrowed result = MaterializeService.narrowing(
                probed, 1000, PIPELINE, BUDGET, FRESH, (limit, left) -> window(limit), (w, b) -> 42L);

        assertThat(result.rows()).isEqualTo(42L);
        assertThat(result.window()).isSameAs(probed);
        assertThat(result.narrowed()).isFalse();
    }

    @Test
    void refusedWriteHalvesItsWindowAndRetries() {
        List<Integer> probedAt = new ArrayList<>();
        // Refuses the first two attempts, then succeeds — so the limit sequence must be 1000, 500, 250.
        Narrowed result = MaterializeService.narrowing(window(1000), 1000, PIPELINE, BUDGET, FRESH,
                (limit, left) -> {
                    probedAt.add(limit);
                    return window(limit);
                },
                (w, b) -> {
                    if (probedAt.size() < 2) {
                        throw refused();
                    }
                    return 7L;
                });

        assertThat(probedAt).containsExactly(500, 250);
        assertThat(result.rows()).isEqualTo(7L);
        assertThat(result.narrowed()).isTrue();
        // The window reported is the re-probed one, never the originally probed one: the caller's next
        // cursor comes from here, and handing back the wider tuple would skip the rows it never wrote.
        assertThat(result.window()).isEqualTo(window(250));
    }

    @Test
    void refusalAtOneRowIsSurfacedRatherThanRetriedForever() {
        // At the floor there is nothing left to narrow: the declaration wants more than the ceiling allows
        // however it is windowed, and saying so beats crawling.
        assertThatThrownBy(() -> MaterializeService.narrowing(
                window(100), 1, PIPELINE, BUDGET, FRESH, (limit, left) -> window(limit), (w, b) -> {
                    throw refused();
                }))
                .isInstanceOf(PipelineReadBudgetExceededException.class);
    }

    @Test
    void halvingReachesTheFloorAndThenSurfaces() {
        List<Integer> probedAt = new ArrayList<>();
        assertThatThrownBy(() -> MaterializeService.narrowing(
                window(8), 8, PIPELINE, BUDGET, FRESH,
                (limit, left) -> {
                    probedAt.add(limit);
                    return window(limit);
                },
                (w, b) -> {
                    throw refused();
                }))
                .isInstanceOf(PipelineReadBudgetExceededException.class);

        assertThat(probedAt).containsExactly(4, 2, 1);
    }

    @Test
    void windowThatEmptiesUnderNarrowingSurfacesTheRefusal() {
        // Nothing matches any more, so there is no smaller window to write. Reporting the refusal is the
        // honest answer; reporting success would advance a cursor past rows nothing wrote.
        assertThatThrownBy(() -> MaterializeService.narrowing(
                window(100), 100, PIPELINE, BUDGET, FRESH, (limit, left) -> null, (w, b) -> {
                    throw refused();
                }))
                .isInstanceOf(PipelineReadBudgetExceededException.class);
    }

    @Test
    void anythingOtherThanBudgetRefusalIsNotNarrowed() {
        // A malformed statement or an outage must not be retried three times at halved widths.
        assertThatThrownBy(() -> MaterializeService.narrowing(
                window(100), 1000, PIPELINE, BUDGET, FRESH, (limit, left) -> window(limit), (w, b) -> {
                    throw new IllegalStateException("ClickHouse rejected the statement");
                }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void narrowingStopsWhenTheCallsOwnDeadlineIsSpent() {
        // The ceiling bounds the call, not the attempt. A sequence that kept halving on a fresh 30s each
        // time would run past the caller's read timeout and finish into a closed socket — which the caller
        // reports as a transport failure and retries, which is the livelock the budget replaces.
        List<Integer> probedAt = new ArrayList<>();
        assertThatThrownBy(() -> MaterializeService.narrowing(
                window(100), 1000, PIPELINE, BUDGET, () -> Duration.ofSeconds(30),
                (limit, left) -> {
                    probedAt.add(limit);
                    return window(limit);
                },
                (w, b) -> {
                    throw refused();
                }))
                .isInstanceOf(PipelineReadBudgetExceededException.class)
                .hasMessageContaining("execution budget")
                // And says so. Nothing was written, so neither "across narrowing retries" nor any claim
                // about which window would have fitted may appear — both assert a sequence that never ran.
                .hasMessageContaining("no write was attempted")
                .hasMessageNotContaining("across narrowing retries")
                .hasMessageNotContaining("smaller than one this call can find");

        // Spent before the first attempt, so nothing was even tried.
        assertThat(probedAt).isEmpty();
    }

    /**
     * A refusal that consumed the budget stops the sequence <b>without</b> paying for one more probe. The
     * probe exists only to size a narrowed window, and there is no time left to write one — so issuing it
     * spends a statement against a node that has just refused us, for a result that cannot be used.
     *
     * <p>The refusal surfaced is the write's own, which names the real cause, rather than the exhaustion
     * message the extra round trip would have produced.
     */
    @Test
    void refusalThatSpentTheBudgetDoesNotPayForAnotherProbe() {
        List<Integer> probedAt = new ArrayList<>();
        // Advanced by the write, not per reading: the implementation consults the clock twice on a refused
        // attempt, and a fixed sequence would make this test depend on that arity rather than on the
        // behaviour. The write spends the whole budget, which is what a timeout-refused write does.
        AtomicReference<Duration> spent = new AtomicReference<>(Duration.ZERO);

        assertThatThrownBy(() -> MaterializeService.narrowing(
                window(100), 1000, PIPELINE, BUDGET, spent::get,
                (limit, left) -> {
                    probedAt.add(limit);
                    return window(limit);
                },
                (w, b) -> {
                    spent.set(BUDGET.timeout());
                    throw refused();
                }))
                .isInstanceOf(PipelineReadBudgetExceededException.class)
                .hasMessage("exceeded the ceiling");

        assertThat(probedAt).isEmpty();
    }

    /**
     * The other arm of that message. Here the write leaves budget, so the call narrows — and then the
     * re-probe spends what was left. That genuinely did happen "across narrowing retries", and saying so is
     * the whole point of distinguishing the two.
     */
    @Test
    void budgetSpentByTheReprobeIsReportedAsNarrowed() {
        AtomicReference<Duration> spent = new AtomicReference<>(Duration.ZERO);

        assertThatThrownBy(() -> MaterializeService.narrowing(
                window(100), 1000, PIPELINE, BUDGET, spent::get,
                (limit, left) -> {
                    // Probing is what exhausts the budget, after a write that left some of it.
                    spent.set(BUDGET.timeout());
                    return window(limit);
                },
                (w, b) -> {
                    spent.set(Duration.ofSeconds(10));
                    throw refused();
                }))
                .isInstanceOf(PipelineReadBudgetExceededException.class)
                .hasMessageContaining("across narrowing retries")
                .hasMessageNotContaining("no write was attempted");
    }

    /**
     * The control-flow consequence of attributing a memory refusal to the node rather than to our ceiling:
     * it is no longer a {@code PipelineReadBudgetExceededException}, so it does not enter the narrowing
     * loop at all. That is the intent — halving a window does not address a condition the window's size did
     * not cause — but it changes how many statements a materialize issues under pressure, so it is pinned.
     */
    @Test
    void anOutageIsNotNarrowedAndCostsNoProbe() {
        List<Integer> probedAt = new ArrayList<>();

        assertThatThrownBy(() -> MaterializeService.narrowing(
                window(100), 1000, PIPELINE, BUDGET, FRESH,
                (limit, left) -> {
                    probedAt.add(limit);
                    return window(limit);
                },
                (w, b) -> {
                    throw new ClickHouseUnavailableException("ClickHouse is unavailable", null);
                }))
                .isInstanceOf(ClickHouseUnavailableException.class);

        assertThat(probedAt).isEmpty();
    }

    @Test
    void eachAttemptRunsOnWhatIsLeftOfTheBudget() {
        List<Duration> timeouts = new ArrayList<>();
        // Advanced by the WRITE rather than by each reading of it: time passes when a statement runs, not
        // when the loop asks what time it is. A clock that moved per reading would make this assertion
        // depend on how many times the implementation consults it.
        AtomicReference<Duration> spent = new AtomicReference<>(Duration.ZERO);
        MaterializeService.narrowing(window(100), 1000, PIPELINE, BUDGET, spent::get,
                (limit, left) -> window(limit),
                (w, b) -> {
                    timeouts.add(b.timeout());
                    spent.updateAndGet(elapsed -> elapsed.plusSeconds(10));
                    if (timeouts.size() < 3) {
                        throw refused();
                    }
                    return 1L;
                });

        assertThat(timeouts).containsExactly(
                Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofSeconds(10));
    }

    @Test
    void anUnderFilledWindowNarrowsFromItsRowsNotFromTheLimit() {
        // The default limit is 10000; a window that matched 300 rows returns the SAME 300 rows and the same
        // end tuple at every limit down to 512. Seeding from the limit would buy six refused, full-cost
        // writes against a shared node before the window actually moved.
        List<Integer> probedAt = new ArrayList<>();
        assertThatThrownBy(() -> MaterializeService.narrowing(
                underFilled(300, 300), 10000, PIPELINE, BUDGET, FRESH,
                (limit, left) -> {
                    probedAt.add(limit);
                    return underFilled(300, limit);
                },
                (w, b) -> {
                    throw refused();
                }))
                .isInstanceOf(PipelineReadBudgetExceededException.class);

        // First retry is at 150, not 5000.
        assertThat(probedAt.getFirst()).isEqualTo(150);
    }

    // ---- the single per-call deadline

    /**
     * The clock is the call's, started before its first probe: a probe that spent 20 of the 30 seconds leaves
     * the write 10, not a fresh 30.
     */
    @Test
    void slowProbeLeavesTheWriteOnlyTheRemainingTime() {
        List<Duration> timeouts = new ArrayList<>();
        MaterializeService.narrowing(window(100), 1000, PIPELINE, BUDGET, () -> Duration.ofSeconds(20),
                (limit, left) -> window(limit),
                (w, b) -> {
                    timeouts.add(b.timeout());
                    return 1L;
                });

        assertThat(timeouts).containsExactly(Duration.ofSeconds(10));
    }

    @Test
    void narrowingReprobeRunsOnTheRemainingTimeToo() {
        List<Duration> reprobeTimeouts = new ArrayList<>();
        AtomicReference<Duration> spent = new AtomicReference<>(Duration.ofSeconds(5));
        MaterializeService.narrowing(window(100), 1000, PIPELINE, BUDGET, spent::get,
                (limit, left) -> {
                    reprobeTimeouts.add(left.timeout());
                    spent.updateAndGet(elapsed -> elapsed.plusSeconds(2));
                    return window(limit);
                },
                (w, b) -> {
                    spent.updateAndGet(elapsed -> elapsed.plusSeconds(8));
                    if (reprobeTimeouts.isEmpty()) {
                        throw refused();
                    }
                    return 1L;
                });

        // 5 s of probe, 8 s of refused write: the re-probe had 17 s, never a fresh 30.
        assertThat(reprobeTimeouts).containsExactly(Duration.ofSeconds(17));
    }

    /**
     * With every statement taking 8 s on a fake clock, the whole call — first probe included — ends within
     * the one 30 s budget: each statement is granted only what is left, and once nothing usable is left the
     * refusal is reported instead of another statement being started.
     */
    @Test
    void theWholeCallEndsWithinOneBudget() {
        AtomicReference<Duration> clock = new AtomicReference<>(Duration.ofSeconds(8));   // the first probe
        List<Duration> granted = new ArrayList<>();

        assertThatThrownBy(() -> MaterializeService.narrowing(window(1000), 1000, PIPELINE, BUDGET, clock::get,
                (limit, left) -> {
                    granted.add(left.timeout());
                    clock.updateAndGet(elapsed -> elapsed.plusSeconds(8));
                    return window(limit);
                },
                (w, b) -> {
                    granted.add(b.timeout());
                    clock.updateAndGet(elapsed -> elapsed.plus(b.timeout().compareTo(Duration.ofSeconds(8)) < 0
                            ? b.timeout() : Duration.ofSeconds(8)));
                    throw refused();
                }))
                .isInstanceOf(PipelineReadBudgetExceededException.class);

        // write 22 s, re-probe 14 s, write 6 s — and then nothing left, so no further statement.
        assertThat(granted).containsExactly(Duration.ofSeconds(22), Duration.ofSeconds(14), Duration.ofSeconds(6));
        assertThat(clock.get()).isLessThanOrEqualTo(BUDGET.timeout());
    }

    @Test
    void theCallClockAccumulatesEachStageSeparately() {
        AtomicReference<Long> nanos = new AtomicReference<>(0L);
        MaterializeCall call = new MaterializeCall(nanos::get);

        call.time(MaterializeCall.Stage.PROBE, () -> nanos.updateAndGet(n -> n + 3_000_000L));
        call.time(MaterializeCall.Stage.REPROBE, () -> nanos.updateAndGet(n -> n + 2_000_000L));
        call.time(MaterializeCall.Stage.WRITE, () -> nanos.updateAndGet(n -> n + 5_000_000L));
        call.time(MaterializeCall.Stage.REPROBE, () -> nanos.updateAndGet(n -> n + 1_000_000L));
        // A statement that throws still counts: the soft-deadline WARN reports failed calls too.
        assertThatThrownBy(() -> call.time(MaterializeCall.Stage.WRITE, () -> {
            nanos.updateAndGet(n -> n + 4_000_000L);
            throw refused();
        })).isInstanceOf(PipelineReadBudgetExceededException.class);

        assertThat(call.probeMs()).isEqualTo(3L);
        assertThat(call.reprobeMs()).isEqualTo(3L);
        assertThat(call.writeMs()).isEqualTo(9L);
        assertThat(call.totalMs()).isEqualTo(15L);
        assertThat(call.elapsed()).isEqualTo(Duration.ofMillis(15));
    }
}
