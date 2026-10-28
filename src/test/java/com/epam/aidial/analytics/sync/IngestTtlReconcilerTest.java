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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The usage-log and payload {@code TTL} decisions, over {@code engine_full} strings in the spelling ClickHouse
 * renders, and the bound of the payload retention by the usage log's.
 */
class IngestTtlReconcilerTest {

    private static final String HEAD =
            "ReplacingMergeTree(_updated_at) PARTITION BY toYYYYMMDD(request_time) ORDER BY event_id";
    private static final String SETTINGS = " SETTINGS ttl_only_drop_parts = 1, index_granularity = 8192";
    private static final String TTL_90 = HEAD + " TTL toDateTime(request_time) + toIntervalDay(90)" + SETTINGS;
    private static final String TTL_YEAR = HEAD + " TTL toDateTime(request_time) + toIntervalYear(1)" + SETTINGS;
    private static final String NO_TTL = HEAD + SETTINGS;
    private static final String PAYLOAD = PayloadRetentionProperties.DEFAULT_TABLE;
    private static final String USAGE_LOG = UsageLogRetentionProperties.DEFAULT_TABLE;

    private final TableDdlRenderer renderer = new TableDdlRenderer();

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
        assertThat(IngestTtlReconciler.command(TTL_90, Optional.empty(), renderer)).contains("REMOVE TTL");
    }

    @Test
    void minusOneLeavesTableWithoutTtlAlone() {
        // The settings carry ttl_only_drop_parts, which must not read as a TTL clause.
        assertThat(IngestTtlReconciler.command(NO_TTL, Optional.empty(), renderer)).isEmpty();
    }

    @Test
    void sameLengthInAnotherUnitIsReplaced() {
        final String weeks = HEAD + " TTL toDateTime(request_time) + toIntervalWeek(90)" + SETTINGS;
        assertThat(IngestTtlReconciler.command(weeks, days(90), renderer)).isPresent();
    }

    @Test
    void ttlOverAnotherColumnIsReplaced() {
        final String other = HEAD + " TTL toDateTime(_updated_at) + toIntervalDay(90)" + SETTINGS;
        assertThat(IngestTtlReconciler.command(other, days(90), renderer)).isPresent();
    }

    @Test
    void ttlOfSeveralRulesIsReplaced() {
        final String rules = HEAD + " TTL toDateTime(request_time) + toIntervalDay(90), "
                + "toDateTime(request_time) + toIntervalDay(30) DELETE WHERE event_id = ''" + SETTINGS;
        assertThat(IngestTtlReconciler.command(rules, days(90), renderer)).isPresent();
    }

    @Test
    void ttlClauseEndsAtSettingsOrTheEnd() {
        assertThat(IngestTtlReconciler.ttlClause(TTL_90)).contains("toDateTime(request_time) + toIntervalDay(90)");
        assertThat(IngestTtlReconciler.ttlClause(HEAD + " TTL toDateTime(request_time) + toIntervalDay(7)"))
                .contains("toDateTime(request_time) + toIntervalDay(7)");
        assertThat(IngestTtlReconciler.ttlClause(NO_TTL)).isEmpty();
    }

    @Test
    void unsetUsageLogRetentionTargetsTheProvisionedYear() {
        final Optional<TtlDdl> target = IngestTtlReconciler.usageLogTtl(usageLog(null));
        assertThat(target).contains(new TtlDdl("request_time", 1, TtlUnit.YEAR, true));
        assertThat(IngestTtlReconciler.command(TTL_YEAR, target, renderer)).isEmpty();
    }

    @Test
    void unsetUsageLogRetentionRestoresTheYear() {
        assertThat(IngestTtlReconciler.command(TTL_90, IngestTtlReconciler.usageLogTtl(usageLog(null)), renderer))
                .contains("MODIFY TTL toDateTime(`request_time`) + INTERVAL 1 YEAR");
    }

    @Test
    void threeHundredSixtyFiveDaysIsNotTheYear() {
        assertThat(IngestTtlReconciler.command(TTL_YEAR, IngestTtlReconciler.usageLogTtl(usageLog(365)), renderer))
                .contains("MODIFY TTL toDateTime(`request_time`) + INTERVAL 365 DAY");
    }

    @Test
    void minusOneUsageLogRetentionTargetsNoTtl() {
        assertThat(IngestTtlReconciler.usageLogTtl(usageLog(-1))).isEmpty();
    }

    @Test
    void reconcileAltersBothConfiguredTables() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(usageLog(180), payload(30), Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log` MODIFY TTL toDateTime(`request_time`) + INTERVAL 180 DAY");
        verify(ddl).execute("ALTER TABLE `dial_usage_log_payload` MODIFY TTL toDateTime(`request_time`) + INTERVAL 30 DAY");
    }

    @Test
    void reconcileIssuesNothingWhenBothTablesMatch() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(usageLog(null), payload(90), Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl).reconcile();
        verify(ddl, never()).execute(anyString());
    }

    @Test
    void reconcileSkipsMissingTableAndStillReconcilesTheOther() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(usageLog(180), payload(30), Optional.of(TTL_YEAR), Optional.empty(), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log` MODIFY TTL toDateTime(`request_time`) + INTERVAL 180 DAY");
        verify(ddl, never()).execute(contains(PAYLOAD));
    }

    @Test
    void reconcileSkipsMissingUsageLogAndStillReconcilesThePayload() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(usageLog(180), payload(30), Optional.empty(), Optional.of(TTL_90), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log_payload` MODIFY TTL toDateTime(`request_time`) + INTERVAL 30 DAY");
        verify(ddl, never()).execute(contains("`" + USAGE_LOG + "`"));
    }

    @Test
    void rejectedAlterPropagates() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        doThrow(new ClickHouseQueryException("rejected", new RuntimeException())).when(ddl).execute(anyString());
        assertThatThrownBy(() -> reconciler(usageLog(null), payload(30), Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl)
                .reconcile())
                .isInstanceOf(ClickHouseQueryException.class);
    }

    @Test
    void payloadLongerThanUsageLogFailsNamingBothProperties() {
        assertThatThrownBy(() -> IngestTtlReconciler.checkPayloadWithinUsageLog(usageLog(60), payload(90)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sync.payload.ttl-days")
                .hasMessageContaining("sync.usage-log.ttl-days");
    }

    @Test
    void payloadLongerThanTheProvisionedYearFails() {
        assertThatThrownBy(() -> IngestTtlReconciler.checkPayloadWithinUsageLog(usageLog(null), payload(366)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unlimitedPayloadNeedsAnUnlimitedUsageLog() {
        assertThatThrownBy(() -> IngestTtlReconciler.checkPayloadWithinUsageLog(usageLog(null), payload(-1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IngestTtlReconciler.checkPayloadWithinUsageLog(usageLog(10_000), payload(-1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> IngestTtlReconciler.checkPayloadWithinUsageLog(usageLog(-1), payload(-1)))
                .doesNotThrowAnyException();
    }

    @Test
    void equalOrShorterPayloadIsAccepted() {
        assertThatCode(() -> IngestTtlReconciler.checkPayloadWithinUsageLog(usageLog(60), payload(60)))
                .doesNotThrowAnyException();
        assertThatCode(() -> IngestTtlReconciler.checkPayloadWithinUsageLog(usageLog(null), payload(365)))
                .doesNotThrowAnyException();
        assertThatCode(() -> IngestTtlReconciler.checkPayloadWithinUsageLog(usageLog(-1), payload(90)))
                .doesNotThrowAnyException();
    }

    @Test
    void constructionChecksTheBound() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        assertThatThrownBy(() -> reconciler(usageLog(30), payload(90), Optional.of(TTL_YEAR), Optional.of(TTL_90), ddl))
                .isInstanceOf(IllegalStateException.class);
    }

    private IngestTtlReconciler reconciler(UsageLogRetentionProperties usageLog, PayloadRetentionProperties payload,
            Optional<String> usageLogEngine, Optional<String> payloadEngine, DdlExecutor ddl) {
        final ClickHouseTableInspector inspector = mock(ClickHouseTableInspector.class);
        when(inspector.engineFull(USAGE_LOG)).thenReturn(usageLogEngine);
        when(inspector.engineFull(PAYLOAD)).thenReturn(payloadEngine);
        return new IngestTtlReconciler(usageLog, payload, inspector, ddl, renderer);
    }

    private static Optional<TtlDdl> days(int days) {
        return Optional.of(new TtlDdl("request_time", days, TtlUnit.DAY, true));
    }

    private static UsageLogRetentionProperties usageLog(Integer ttlDays) {
        return new UsageLogRetentionProperties(ttlDays, null);
    }

    private static PayloadRetentionProperties payload(int ttlDays) {
        return new PayloadRetentionProperties(ttlDays, null);
    }
}
