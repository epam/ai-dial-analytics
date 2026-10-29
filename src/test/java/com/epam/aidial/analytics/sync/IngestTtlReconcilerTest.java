package com.epam.aidial.analytics.sync;

import com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector;
import com.epam.aidial.analytics.clickhouse.DdlExecutor;
import com.epam.aidial.analytics.configuration.PayloadRetentionProperties;
import com.epam.aidial.analytics.configuration.UsageLogRetentionProperties;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import com.epam.aidial.analytics.service.table.TableDdlRenderer.TtlDdl;
import com.epam.aidial.analytics.service.table.TtlUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The usage-log and payload {@code TTL} decisions, over {@code engine_full} strings in the spelling ClickHouse
 * renders, and the bound of the payload retention by the usage log's on set and live retentions.
 */
class IngestTtlReconcilerTest {

    private static final String HEAD =
            "ReplacingMergeTree(_updated_at) PARTITION BY toYYYYMMDD(request_time) ORDER BY event_id";
    private static final String SETTINGS = " SETTINGS ttl_only_drop_parts = 1, index_granularity = 8192";
    private static final String TTL_90 = ttl("toIntervalDay(90)");
    private static final String TTL_YEAR = ttl("toIntervalYear(1)");
    private static final String NO_TTL = HEAD + SETTINGS;
    private static final String MOVE_RULE = HEAD + " TTL toDateTime(request_time) + toIntervalDay(7) TO VOLUME 'cold', "
            + "toDateTime(request_time) + toIntervalYear(1)" + SETTINGS;
    private static final String PAYLOAD = PayloadRetentionProperties.DEFAULT_TABLE;
    private static final String USAGE_LOG = UsageLogRetentionProperties.DEFAULT_TABLE;

    private final TableDdlRenderer renderer = new TableDdlRenderer();

    // --- the per-table decision ---

    @Test
    void matchingRetentionNeedsNoStatement() {
        assertThat(IngestTtlReconciler.command(TTL_90, days(90), renderer)).isEmpty();
    }

    @Test
    void differingRetentionIsModified() {
        assertThat(IngestTtlReconciler.command(TTL_90, days(30), renderer))
                .contains("MODIFY TTL toDateTime(`request_time`) + INTERVAL 30 DAY");
    }

    @Test
    void missingTtlIsAddedForPositiveRetention() {
        assertThat(IngestTtlReconciler.command(NO_TTL, days(90), renderer))
                .contains("MODIFY TTL toDateTime(`request_time`) + INTERVAL 90 DAY");
    }

    @Test
    void minusOneRemovesAnExistingTtl() {
        assertThat(IngestTtlReconciler.target(-1)).isEmpty();
        assertThat(IngestTtlReconciler.command(TTL_90, Optional.empty(), renderer)).contains("REMOVE TTL");
    }

    @Test
    void minusOneLeavesTableWithoutTtlAlone() {
        // The settings carry ttl_only_drop_parts, which must not read as a TTL clause.
        assertThat(IngestTtlReconciler.command(NO_TTL, Optional.empty(), renderer)).isEmpty();
    }

    @Test
    void sameLengthInAnotherUnitIsReplaced() {
        assertThat(IngestTtlReconciler.command(ttl("toIntervalWeek(90)"), days(90), renderer)).isPresent();
    }

    @Test
    void threeHundredSixtyFiveDaysIsNotTheYear() {
        assertThat(IngestTtlReconciler.command(TTL_YEAR, days(365), renderer))
                .contains("MODIFY TTL toDateTime(`request_time`) + INTERVAL 365 DAY");
    }

    @Test
    void ttlOverAnotherColumnIsReplaced() {
        final String other = HEAD + " TTL toDateTime(_updated_at) + toIntervalDay(90)" + SETTINGS;
        assertThat(IngestTtlReconciler.command(other, days(90), renderer)).isPresent();
    }

    @Test
    void ttlOfSeveralRulesIsReplaced() {
        assertThat(IngestTtlReconciler.command(MOVE_RULE, days(365), renderer)).isPresent();
    }

    @Test
    void ttlClauseEndsAtSettingsOrTheEnd() {
        assertThat(IngestTtlReconciler.ttlClause(TTL_90)).contains("toDateTime(request_time) + toIntervalDay(90)");
        assertThat(IngestTtlReconciler.ttlClause(HEAD + " TTL toDateTime(request_time) + toIntervalDay(7)"))
                .contains("toDateTime(request_time) + toIntervalDay(7)");
        assertThat(IngestTtlReconciler.ttlClause(NO_TTL)).isEmpty();
    }

    // --- reconcile: unset leaves a table alone ---

    @Test
    void unsetPropertiesIssueNothingWhateverTheTablesCarry() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(null, null, Optional.of(ttl("toIntervalDay(500)")), Optional.of(NO_TTL), ddl).reconcile();
        verify(ddl, never()).execute(anyString());
    }

    @Test
    void anUnsetSideIsLeftAloneWhileTheSetSideIsReconciled() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(180, null, Optional.of(TTL_YEAR), Optional.of(ttl("toIntervalDay(45)")), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log` MODIFY TTL toDateTime(`request_time`) + INTERVAL 180 DAY");
        verify(ddl, never()).execute(contains(PAYLOAD));
    }

    @Test
    void reconcileAltersBothSetTables() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(180, 30, Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log` MODIFY TTL toDateTime(`request_time`) + INTERVAL 180 DAY");
        verify(ddl).execute("ALTER TABLE `dial_usage_log_payload` MODIFY TTL toDateTime(`request_time`) + INTERVAL 30 DAY");
    }

    @Test
    void reconcileIssuesNothingWhenBothSetTablesMatch() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(180, 90, Optional.of(ttl("toIntervalDay(180)")), Optional.of(TTL_90), ddl).reconcile();
        verify(ddl, never()).execute(anyString());
    }

    @Test
    void reconcileSkipsMissingTableAndStillReconcilesTheOther() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(180, 30, Optional.of(TTL_YEAR), Optional.empty(), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log` MODIFY TTL toDateTime(`request_time`) + INTERVAL 180 DAY");
        verify(ddl, never()).execute(contains(PAYLOAD));
    }

    @Test
    void reconcileSkipsMissingUsageLogAndStillReconcilesThePayload() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(null, 30, Optional.empty(), Optional.of(TTL_90), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log_payload` MODIFY TTL toDateTime(`request_time`) + INTERVAL 30 DAY");
        verify(ddl, never()).execute(contains("`" + USAGE_LOG + "`"));
    }

    @Test
    void rejectedAlterPropagates() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        doThrow(new ClickHouseQueryException("rejected", new RuntimeException())).when(ddl).execute(anyString());
        assertThatThrownBy(() -> reconciler(null, 30, Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl).reconcile())
                .isInstanceOf(ClickHouseQueryException.class);
    }

    // --- the bound on two set values ---

    @Test
    void twoSetValuesWithTheLongerPayloadFailNamingBothProperties() {
        assertThatThrownBy(() -> IngestTtlReconciler.checkSetRetentions(usageLog(60), payload(90)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sync.payload.ttl-days")
                .hasMessageContaining("sync.usage-log.ttl-days");
        assertThatThrownBy(() -> IngestTtlReconciler.checkSetRetentions(usageLog(500), payload(-1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void twoSetValuesEqualShorterOrBothUnlimitedAreAccepted() {
        assertThatCode(() -> IngestTtlReconciler.checkSetRetentions(usageLog(60), payload(60))).doesNotThrowAnyException();
        assertThatCode(() -> IngestTtlReconciler.checkSetRetentions(usageLog(-1), payload(-1))).doesNotThrowAnyException();
        assertThatCode(() -> IngestTtlReconciler.checkSetRetentions(usageLog(-1), payload(90))).doesNotThrowAnyException();
    }

    @Test
    void anUnsetSideIsNotJudgedAtConstruction() {
        assertThatCode(() -> IngestTtlReconciler.checkSetRetentions(usageLog(null), payload(-1))).doesNotThrowAnyException();
        assertThatCode(() -> IngestTtlReconciler.checkSetRetentions(usageLog(1), payload(null))).doesNotThrowAnyException();
    }

    @Test
    void constructionChecksTwoSetValues() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        assertThatThrownBy(() -> reconciler(30, 90, Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- the bound against a live TTL ---

    @Test
    void setUsageLogShorterThanTheLivePayloadFailsBeforeAnyAlter() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        assertThatThrownBy(() -> reconciler(60, null, Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl).reconcile())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sync.payload.ttl-days (unset, live TTL toDateTime(request_time) + toIntervalDay(90))")
                .hasMessageContaining("sync.usage-log.ttl-days (60)");
        verify(ddl, never()).execute(anyString());
    }

    @Test
    void setPayloadLongerThanTheLiveYearFailsBeforeAnyAlter() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        assertThatThrownBy(() -> reconciler(null, 366, Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl).reconcile())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reconciler(null, -1, Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl).reconcile())
                .isInstanceOf(IllegalStateException.class);
        verify(ddl, never()).execute(anyString());
    }

    @Test
    void setPayloadOfYearDaysFitsTheLiveYear() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(null, 365, Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log_payload` MODIFY TTL toDateTime(`request_time`) + INTERVAL 365 DAY");
    }

    @Test
    void setPayloadFitsUsageLogWithoutTtl() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(null, -1, Optional.of(NO_TTL), Optional.of(TTL_90), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log_payload` REMOVE TTL");
    }

    @Test
    void liveTtlsOutOfBoundWithBothUnsetAreOnlyReported() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        assertThatCode(() -> reconciler(null, null, Optional.of(TTL_90), Optional.of(TTL_YEAR), ddl).reconcile())
                .doesNotThrowAnyException();
        verify(ddl, never()).execute(anyString());
    }

    @Test
    void anUnreadableLiveTtlSkipsTheBoundAndStillReconcilesTheSetSide() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(null, 400, Optional.of(MOVE_RULE), Optional.of(TTL_90), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log_payload` MODIFY TTL toDateTime(`request_time`) + INTERVAL 400 DAY");
        verify(ddl, never()).execute(contains("`" + USAGE_LOG + "`"));
    }

    @Test
    void missingUnsetSideSkipsTheBound() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(null, 400, Optional.empty(), Optional.of(TTL_90), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log_payload` MODIFY TTL toDateTime(`request_time`) + INTERVAL 400 DAY");
    }

    @Test
    void missingSetSideWithUnsetOtherSideAltersNothing() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        assertThatCode(() -> reconciler(60, null, Optional.empty(), Optional.of(TTL_90), ddl).reconcile())
                .doesNotThrowAnyException();
        verify(ddl, never()).execute(anyString());
    }

    @Test
    void unsetPayloadWithoutTtlIsUnlimitedAgainstSetUsageLog() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        assertThatThrownBy(() -> reconciler(365, null, Optional.of(TTL_YEAR), Optional.of(NO_TTL), ddl).reconcile())
                .isInstanceOf(IllegalStateException.class);
        verify(ddl, never()).execute(anyString());
    }

    @Test
    void unreadablePayloadTtlSkipsTheBound() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(30, null, Optional.of(TTL_YEAR), Optional.of(MOVE_RULE), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log` MODIFY TTL toDateTime(`request_time`) + INTERVAL 30 DAY");
    }

    @Test
    void unreadableTtlWithBothUnsetIsOnlyReported() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        assertThatCode(() -> reconciler(null, null, Optional.of(MOVE_RULE), Optional.of(TTL_90), ddl).reconcile())
                .doesNotThrowAnyException();
        verify(ddl, never()).execute(anyString());
    }

    // --- the order of the two ALTERs ---

    @Test
    void payloadNotFittingTheNewUsageLogIsAlteredFirst() {
        // Were the usage log shortened first and the payload ALTER then refused, payloads would outlive events.
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(30, 30, Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl).reconcile();
        final InOrder order = inOrder(ddl);
        order.verify(ddl).execute(contains("`" + PAYLOAD + "`"));
        order.verify(ddl).execute(contains("`" + USAGE_LOG + "`"));
    }

    @Test
    void growingUsageLogIsAlteredBeforeThePayload() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(500, 400, Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl).reconcile();
        final InOrder order = inOrder(ddl);
        order.verify(ddl).execute(contains("`" + USAGE_LOG + "`"));
        order.verify(ddl).execute(contains("`" + PAYLOAD + "`"));
    }

    @Test
    void failedPayloadAlterLeavesTheUsageLogUntouched() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        doThrow(new ClickHouseQueryException("rejected", new RuntimeException())).when(ddl).execute(contains(PAYLOAD));
        assertThatThrownBy(() -> reconciler(30, 30, Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl).reconcile())
                .isInstanceOf(ClickHouseQueryException.class);
        verify(ddl, never()).execute(contains("`" + USAGE_LOG + "`"));
    }

    @Test
    void monthlyUsageLogShortenedToDaysGoesFirstWhenThePayloadAlreadyFits() {
        // 30 days may be longer than a month (28 to 31 days), so "does the log shrink" cannot decide the order; the
        // live 7-day payload fits under the new 30 days, so altering the usage log first is provably safe.
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(30, 30, Optional.of(ttl("toIntervalMonth(1)")), Optional.of(ttl("toIntervalDay(7)")), ddl).reconcile();
        final InOrder order = inOrder(ddl);
        order.verify(ddl).execute(contains("`" + USAGE_LOG + "`"));
        order.verify(ddl).execute(contains("`" + PAYLOAD + "`"));
    }

    @Test
    void unreadablePayloadIsAlteredFirstEvenWhenTheUsageLogGrows() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(500, 400, Optional.of(TTL_YEAR), Optional.of(MOVE_RULE), ddl).reconcile();
        final InOrder order = inOrder(ddl);
        order.verify(ddl).execute(contains("`" + PAYLOAD + "`"));
        order.verify(ddl).execute(contains("`" + USAGE_LOG + "`"));
    }

    @Test
    void unreadableUsageLogIsOrderedByThePayload() {
        // The live usage log cannot be read, but the live 7-day payload fits under the new 30 days, so the log goes first.
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(30, 30, Optional.of(MOVE_RULE), Optional.of(ttl("toIntervalDay(7)")), ddl).reconcile();
        final InOrder order = inOrder(ddl);
        order.verify(ddl).execute(contains("`" + USAGE_LOG + "`"));
        order.verify(ddl).execute(contains("`" + PAYLOAD + "`"));
    }

    @Test
    void unlimitedUsageLogGoesFirstWhateverThePayloadCarries() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(-1, -1, Optional.of(TTL_YEAR), Optional.of(MOVE_RULE), ddl).reconcile();
        final InOrder order = inOrder(ddl);
        order.verify(ddl).execute("ALTER TABLE `dial_usage_log` REMOVE TTL");
        order.verify(ddl).execute("ALTER TABLE `dial_usage_log_payload` REMOVE TTL");
    }

    // --- reading a live TTL ---

    @Test
    void effectiveRetentionIsTheSetValueElseTheLiveTtl() {
        assertThat(IngestTtlReconciler.effective(30, Optional.of(TTL_YEAR))).contains(days(30));
        assertThat(IngestTtlReconciler.effective(null, Optional.of(TTL_YEAR)))
                .contains(Optional.of(new TtlDdl("request_time", 1, TtlUnit.YEAR, true)));
        assertThat(IngestTtlReconciler.effective(null, Optional.of(NO_TTL))).contains(Optional.empty());
        assertThat(IngestTtlReconciler.effective(null, Optional.of(MOVE_RULE))).isEmpty();
        assertThat(IngestTtlReconciler.effective(null, Optional.empty())).isEmpty();
    }

    @Test
    void likeUnitsCompareTheirIntervals() {
        assertThat(IngestTtlReconciler.exceeds(rule(1, TtlUnit.YEAR), rule(1, TtlUnit.YEAR))).isFalse();
        assertThat(IngestTtlReconciler.exceeds(rule(2, TtlUnit.MONTH), rule(1, TtlUnit.MONTH))).isTrue();
    }

    @Test
    void mixedUnitsCompareThePayloadsWidestWithTheUsageLogsNarrowest() {
        // A year is 365 to 366 days, a month 28 to 31, an hour count is rounded up for the payload and down for the log.
        assertThat(IngestTtlReconciler.exceeds(days(365), rule(1, TtlUnit.YEAR))).isFalse();
        assertThat(IngestTtlReconciler.exceeds(days(366), rule(1, TtlUnit.YEAR))).isTrue();
        assertThat(IngestTtlReconciler.exceeds(rule(1, TtlUnit.YEAR), days(365))).isTrue();
        assertThat(IngestTtlReconciler.exceeds(days(28), rule(1, TtlUnit.MONTH))).isFalse();
        assertThat(IngestTtlReconciler.exceeds(days(29), rule(1, TtlUnit.MONTH))).isTrue();
        assertThat(IngestTtlReconciler.exceeds(rule(1, TtlUnit.WEEK), days(7))).isFalse();
        assertThat(IngestTtlReconciler.exceeds(rule(25, TtlUnit.HOUR), days(1))).isTrue();
        assertThat(IngestTtlReconciler.exceeds(days(1), rule(47, TtlUnit.HOUR))).isFalse();
        assertThat(IngestTtlReconciler.exceeds(days(2), rule(47, TtlUnit.HOUR))).isTrue();
        // Twelve months are 336 to 372 days, a year 365 to 366: each outlasts the other's narrowest reading.
        assertThat(IngestTtlReconciler.exceeds(rule(1, TtlUnit.YEAR), rule(12, TtlUnit.MONTH))).isTrue();
        assertThat(IngestTtlReconciler.exceeds(rule(12, TtlUnit.MONTH), rule(1, TtlUnit.YEAR))).isTrue();
        assertThat(IngestTtlReconciler.exceeds(rule(11, TtlUnit.MONTH), rule(1, TtlUnit.YEAR))).isFalse();
    }

    @Test
    void noTtlIsUnlimited() {
        assertThat(IngestTtlReconciler.exceeds(Optional.empty(), days(10_000))).isTrue();
        assertThat(IngestTtlReconciler.exceeds(days(10_000), Optional.empty())).isFalse();
        assertThat(IngestTtlReconciler.exceeds(Optional.empty(), Optional.empty())).isFalse();
    }

    private IngestTtlReconciler reconciler(Integer usageLogDays, Integer payloadDays,
            Optional<String> usageLogEngine, Optional<String> payloadEngine, DdlExecutor ddl) {
        final ClickHouseTableInspector inspector = mock(ClickHouseTableInspector.class);
        when(inspector.engineFull(USAGE_LOG)).thenReturn(usageLogEngine);
        when(inspector.engineFull(PAYLOAD)).thenReturn(payloadEngine);
        return new IngestTtlReconciler(usageLog(usageLogDays), payload(payloadDays), inspector, ddl, renderer);
    }

    private static String ttl(String interval) {
        return HEAD + " TTL toDateTime(request_time) + " + interval + SETTINGS;
    }

    private static Optional<TtlDdl> days(int days) {
        return rule(days, TtlUnit.DAY);
    }

    private static Optional<TtlDdl> rule(int interval, TtlUnit unit) {
        return Optional.of(new TtlDdl("request_time", interval, unit, true));
    }

    private static UsageLogRetentionProperties usageLog(Integer ttlDays) {
        return new UsageLogRetentionProperties(ttlDays, null);
    }

    private static PayloadRetentionProperties payload(Integer ttlDays) {
        return new PayloadRetentionProperties(ttlDays, null);
    }
}
