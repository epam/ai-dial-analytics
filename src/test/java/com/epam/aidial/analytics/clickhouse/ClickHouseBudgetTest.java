package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.configuration.QueryBudgetProperties;
import com.epam.aidial.analytics.configuration.ResourceBudget;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two things: sharing one wall-clock ceiling across the two statements a single request can run, and
 * deciding <b>whose</b> ceiling a memory refusal names.
 *
 * <p>The ceiling bounds what the <em>caller</em> waits. A row query that opts into {@code include_total}
 * runs a {@code count(*)} after it, and granting that second statement a fresh ceiling would let one
 * request occupy twice the configured budget — landing the caller past its own read timeout with the empty
 * transport failure the budget exists to remove.
 */
class ClickHouseBudgetTest {

    private static final String MAX_EXECUTION_TIME = "clickhouse_setting_max_execution_time";

    private static final QueryBudgetProperties BUDGET = new QueryBudgetProperties(
            Duration.ofSeconds(20), DataSize.ofGigabytes(2), DataSize.ofGigabytes(1));

    /**
     * Verbatim from ClickHouse 26.3.21.7, captured by
     * {@code ClickHouseMemoryLimitMessageIntegrationTest}. Hand-written strings in a unit test pin nothing
     * about a real server, which is why these are copies of an observed message rather than plausible
     * ones — and why that integration test, not this class, is what fails when the wording moves.
     */
    private static final String OWN_CEILING = "Code: 241. DB::Exception: Query memory limit exceeded: "
            + "would use 9.64 MiB (attempt to allocate chunk of 5.00 MiB), maximum: 1.00 KiB: "
            + "While executing AggregatingTransform. (MEMORY_LIMIT_EXCEEDED) (version 26.3.21.7 (official build))";

    private static final String USER_CEILING = "Code: 241. DB::Exception: User memory limit exceeded: "
            + "would use 5.64 MiB (attempt to allocate chunk of 5.00 MiB), maximum: 1.00 KiB. "
            + "OvercommitTracker decision: Query was selected to stop by OvercommitTracker: "
            + "While executing AggregatingTransform. (MEMORY_LIMIT_EXCEEDED) (version 26.3.21.7 (official build))";

    /** Inferred from the {@code Query}/{@code User} pattern above; the node ceiling cannot be set per query. */
    private static final String NODE_CEILING = "Code: 241. DB::Exception: Total memory limit exceeded: "
            + "would use 7.45 GiB, maximum: 7.45 GiB. (MEMORY_LIMIT_EXCEEDED) (version 26.3.21.7 (official build))";

    @Test
    void secondStatementInheritsWhatIsLeftOfTheCeiling() {
        final QuerySettings remaining =
                ClickHouseBudget.remainingTime(ClickHouseBudget.settings(BUDGET), Duration.ofSeconds(8));

        assertThat(remaining.getAllSettings()).containsEntry(MAX_EXECUTION_TIME, "12");
        // Only the clock is spent by the first statement: the memory ceiling and the spill threshold are
        // per-statement resources and stay at their configured values.
        assertThat(remaining.getAllSettings())
                .containsEntry("clickhouse_setting_max_memory_usage", "2147483648")
                .containsEntry("clickhouse_setting_max_bytes_before_external_group_by", "1073741824");
    }

    /**
     * A budget that declares no spill bound renders no spill setting. {@code
     * max_bytes_before_external_group_by} governs {@code GROUP BY} state and nothing else, and no statement
     * {@code pipeline.budget.*} covers has a {@code GROUP BY} — the scan, both group-fetch phases and the
     * preview sample are projections, and the two window probes collapse with bare aggregates over no
     * grouping key. Emitting it anyway would publish a configuration knob an operator could turn, under
     * memory pressure, to no effect whatever.
     */
    @Test
    void budgetWithoutSpillBoundRendersNoSpillSetting() {
        final QuerySettings settings = ClickHouseBudget.settings(
                new PipelineBudgetProperties(Duration.ofSeconds(30), DataSize.ofGigabytes(1)));

        assertThat(settings.getAllSettings())
                .containsEntry(MAX_EXECUTION_TIME, "30")
                .containsEntry("clickhouse_setting_max_memory_usage", "1073741824")
                .doesNotContainKey("clickhouse_setting_max_bytes_before_external_group_by");
    }

    /** The shared wall-clock accounting works the same for a budget carrying only two knobs. */
    @Test
    void remainingTimeIsUnaffectedByAnAbsentSpillBound() {
        final QuerySettings remaining = ClickHouseBudget.remainingTime(
                ClickHouseBudget.settings(
                        new PipelineBudgetProperties(Duration.ofSeconds(30), DataSize.ofGigabytes(1))),
                Duration.ofSeconds(10));

        assertThat(remaining.getAllSettings())
                .containsEntry(MAX_EXECUTION_TIME, "20")
                .doesNotContainKey("clickhouse_setting_max_bytes_before_external_group_by");
    }

    @Test
    void anExhaustedCeilingFloorsAtOneSecondRatherThanZero() {
        // Zero is the one value that must never be produced: ClickHouse reads max_execution_time=0 as
        // unlimited, so a budget already spent would come back as no budget at all.
        final QuerySettings remaining =
                ClickHouseBudget.remainingTime(ClickHouseBudget.settings(BUDGET), Duration.ofSeconds(45));

        assertThat(remaining.getAllSettings()).containsEntry(MAX_EXECUTION_TIME, "1");
    }

    @Test
    void skippableStatementGetsNothingOnceUnderOneSecondIsLeft() {
        // The floor above suits a statement that must run; one the caller can skip must not be handed a fresh second
        // per statement once the budget is spent, or a sequence of them outlives the ceiling.
        assertThat(ClickHouseBudget.remainingTimeIfAny(ClickHouseBudget.settings(BUDGET), Duration.ofMillis(19_001))).isNull();
        assertThat(ClickHouseBudget.remainingTimeIfAny(ClickHouseBudget.settings(BUDGET), Duration.ofSeconds(45))).isNull();
        assertThat(ClickHouseBudget.remainingTimeIfAny(ClickHouseBudget.settings(BUDGET), Duration.ofSeconds(8)).getAllSettings())
                .containsEntry(MAX_EXECUTION_TIME, "12");
        final QuerySettings unbudgeted = new QuerySettings().serverSetting("final", "1");
        assertThat(ClickHouseBudget.remainingTimeIfAny(unbudgeted, Duration.ofHours(1))).isSameAs(unbudgeted);
    }

    @Test
    void theRemainderIsRoundedDownNotTheElapsedTime() {
        // 1.1 s of a 20 s ceiling are left: granting 2 s (20 - truncated 18 s) would let the statement end past the ceiling.
        assertThat(ClickHouseBudget.remainingTimeIfAny(ClickHouseBudget.settings(BUDGET), Duration.ofMillis(18_900)).getAllSettings())
                .containsEntry(MAX_EXECUTION_TIME, "1");
        assertThat(ClickHouseBudget.remainingTime(ClickHouseBudget.settings(BUDGET), Duration.ofMillis(7_500)).getAllSettings())
                .containsEntry(MAX_EXECUTION_TIME, "12");
    }

    @Test
    void settingsCarryingNoCeilingAreReturnedUnchanged() {
        // Every scan, materialize and refresh call site passes settings with no budget attached; the
        // deduction has to be inert there rather than inventing a ceiling those paths never asked for.
        final QuerySettings unbudgeted = new QuerySettings().serverSetting("final", "1");

        final QuerySettings result = ClickHouseBudget.remainingTime(unbudgeted, Duration.ofSeconds(8));

        assertThat(result).isSameAs(unbudgeted);
        assertThat(result.getAllSettings()).doesNotContainKey(MAX_EXECUTION_TIME);
    }

    @Test
    void theOriginalSettingsAreNotMutated() {
        // The row query's settings are still in use by the caller; deducting time must produce a copy.
        final QuerySettings original = ClickHouseBudget.settings(BUDGET);

        ClickHouseBudget.remainingTime(original, Duration.ofSeconds(8));

        assertThat(original.getAllSettings()).containsEntry(MAX_EXECUTION_TIME, "20");
    }

    @Test
    void theCopyDoesNotCarryTheFirstStatementsQueryId() {
        // A second statement under the first one's id would be indistinguishable from it in system.query_log.
        final QuerySettings original = ClickHouseBudget.settings(BUDGET);
        original.setQueryId("adas-p1-probe-1");

        final QuerySettings copy = ClickHouseBudget.remainingTime(original, Duration.ofSeconds(8));

        assertThat(copy.getQueryId()).isNull();
        assertThat(original.getQueryId()).isEqualTo("adas-p1-probe-1");
    }

    @Test
    void settingsWithNoCeilingStillDropTheQueryId() {
        // No budget is no reason to reuse the first statement's id.
        final QuerySettings tagged = new QuerySettings().serverSetting("final", "1").setQueryId("adas-p1-probe-1");

        final QuerySettings copy = ClickHouseBudget.remainingTime(tagged, Duration.ofSeconds(8));

        assertThat(copy.getQueryId()).isNull();
        assertThat(copy.getAllSettings()).containsEntry("clickhouse_setting_final", "1").doesNotContainKey(MAX_EXECUTION_TIME);
        assertThat(tagged.getQueryId()).isEqualTo("adas-p1-probe-1");
    }

    // ---- translate: whose ceiling was it ----

    /**
     * The deterministic half. This refusal is the service's own ceiling, so it must override the 503
     * {@code ClickHouseFailures} assigns from the bare code and become the capability's 422 — a caller
     * that retried it would be refused identically forever.
     */
    @Test
    void ourOwnMemoryCeilingIsClaimedAsTheBudgetsRefusal() {
        assertThat(translate(OWN_CEILING)).isEqualTo("refused: the 2147483648B memory ceiling");
    }

    /**
     * The transient half, and the defect this pair exists for: a ceiling the service never set. Returning
     * the exception untouched is what lets the already-wrapped 503 stand, so the runner retries instead of
     * dead-lettering work that was never too expensive.
     */
    @Test
    void ceilingTheServiceDidNotSetIsLeftAsTheOutageItIs() {
        assertThat(translate(NODE_CEILING)).isNull();
        assertThat(translate(USER_CEILING)).isNull();
    }

    /**
     * The driver renders a message it cannot parse out of its read buffer as this literal, so an
     * unreadable 241 is a real arrival and not a hypothetical. It is unattributable, so it takes the
     * outage answer rather than being claimed as ours.
     */
    @Test
    void anUnreadableOrUnfamiliarMemoryMessageTakesTheOutageAnswer() {
        assertThat(translate("Code: 241. DB::Exception: <Unreadable error message>")).isNull();
        assertThat(translate("Code: 241. DB::Exception: Sandbox memory limit exceeded: nope")).isNull();
        assertThat(translate(null)).isNull();
    }

    /**
     * ClickHouse can append the offending statement to a message, so the wording may appear inside text
     * the caller controls. Matching it anywhere would let a caller relabel its own deterministic refusal
     * as retryable and loop on it; the match is anchored behind the driver's prefix instead.
     */
    @Test
    void theWordingIsNotHonouredWhenItComesFromTheCallersOwnStatement() {
        final String embedded = "Code: 241. DB::Exception: Total memory limit exceeded: would use 7.45 GiB"
                + " (in query: SELECT 1 AS `Query memory limit exceeded`)";

        assertThat(translate(embedded)).isNull();
    }

    /** The timeout arm is decided by its code alone and must stay that way; only 241 is ambiguous. */
    @Test
    void theTimeoutArmStillNeedsNoMessage() {
        final RuntimeException translated = ClickHouseBudget.translate(
                new IllegalStateException("wrapped", new ServerException(159, "anything at all", 500, "q")),
                BUDGET, (exceeded, cause) -> new IllegalArgumentException("refused: " + exceeded, cause));

        assertThat(translated).hasMessage("refused: the PT20S execution timeout");
    }

    /**
     * A statement that ran on what was left of a call's budget is refused naming the configured timeout, which is
     * the value an operator can change, with the remainder beside it; a remainder chained twice still names the
     * original.
     */
    @Test
    void remainderRefusalNamesTheConfiguredTimeout() {
        final ResourceBudget left = ClickHouseBudget.remaining(
                ClickHouseBudget.remaining(BUDGET, Duration.ofMillis(4_300)), Duration.ofSeconds(3));
        final RuntimeException translated = ClickHouseBudget.translate(
                new IllegalStateException("wrapped", new ServerException(159, "anything at all", 500, "q")),
                left, (exceeded, cause) -> new IllegalArgumentException("refused: " + exceeded, cause));

        assertThat(translated).hasMessage("refused: the PT20S execution timeout (12s of it left for this statement)");
        assertThat(left.timeout()).isEqualTo(Duration.ofMillis(12_700));
    }

    /** A statement defect is neither ceiling and is handed back for the ordinary 4xx mapping. */
    @Test
    void anUnrelatedServerErrorIsNotTranslated() {
        assertThat(translate("Code: 60. DB::Exception: Table x does not exist", 60)).isNull();
    }

    private static String translate(String message) {
        return translate(message, 241);
    }

    /** The refusal phrase {@code translate} produced, or null when it handed the exception back unchanged. */
    private static String translate(String message, int code) {
        final RuntimeException original =
                new IllegalStateException("wrapped", new ServerException(code, message, 500, "q"));
        final RuntimeException translated = ClickHouseBudget.translate(
                original, BUDGET, (exceeded, cause) -> new IllegalArgumentException("refused: " + exceeded, cause));

        return translated == original ? null : translated.getMessage();
    }
}
