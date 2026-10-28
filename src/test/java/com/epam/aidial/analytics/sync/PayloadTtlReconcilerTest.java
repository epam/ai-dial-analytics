package com.epam.aidial.analytics.sync;

import com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector;
import com.epam.aidial.analytics.clickhouse.DdlExecutor;
import com.epam.aidial.analytics.configuration.PayloadRetentionProperties;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The payload {@code TTL} decision, over {@code engine_full} strings in the spelling ClickHouse renders.
 */
class PayloadTtlReconcilerTest {

    private static final String HEAD =
            "ReplacingMergeTree(_updated_at) PARTITION BY toYYYYMMDD(request_time) ORDER BY event_id";
    private static final String SETTINGS = " SETTINGS ttl_only_drop_parts = 1, index_granularity = 8192";
    private static final String TTL_90 = HEAD + " TTL toDateTime(request_time) + toIntervalDay(90)" + SETTINGS;
    private static final String NO_TTL = HEAD + SETTINGS;

    private final TableDdlRenderer renderer = new TableDdlRenderer();

    @Test
    void matchingRetentionNeedsNoStatement() {
        assertThat(PayloadTtlReconciler.command(TTL_90, 90, renderer)).isEmpty();
    }

    @Test
    void differingRetentionIsModified() {
        assertThat(PayloadTtlReconciler.command(TTL_90, 30, renderer))
                .contains("MODIFY TTL toDateTime(`request_time`) + INTERVAL 30 DAY");
    }

    @Test
    void missingTtlIsAddedForPositiveRetention() {
        assertThat(PayloadTtlReconciler.command(NO_TTL, 90, renderer))
                .contains("MODIFY TTL toDateTime(`request_time`) + INTERVAL 90 DAY");
    }

    @Test
    void minusOneRemovesAnExistingTtl() {
        assertThat(PayloadTtlReconciler.command(TTL_90, -1, renderer)).contains("REMOVE TTL");
    }

    @Test
    void minusOneLeavesTableWithoutTtlAlone() {
        // The settings carry ttl_only_drop_parts, which must not read as a TTL clause.
        assertThat(PayloadTtlReconciler.command(NO_TTL, -1, renderer)).isEmpty();
    }

    @Test
    void sameLengthInAnotherUnitIsReplaced() {
        final String weeks = HEAD + " TTL toDateTime(request_time) + toIntervalWeek(90)" + SETTINGS;
        assertThat(PayloadTtlReconciler.command(weeks, 90, renderer)).isPresent();
    }

    @Test
    void ttlOverAnotherColumnIsReplaced() {
        final String other = HEAD + " TTL toDateTime(_updated_at) + toIntervalDay(90)" + SETTINGS;
        assertThat(PayloadTtlReconciler.command(other, 90, renderer)).isPresent();
    }

    @Test
    void ttlOfSeveralRulesIsReplaced() {
        final String rules = HEAD + " TTL toDateTime(request_time) + toIntervalDay(90), "
                + "toDateTime(request_time) + toIntervalDay(30) DELETE WHERE event_id = ''" + SETTINGS;
        assertThat(PayloadTtlReconciler.command(rules, 90, renderer)).isPresent();
    }

    @Test
    void ttlClauseEndsAtSettingsOrTheEnd() {
        assertThat(PayloadTtlReconciler.ttlClause(TTL_90)).contains("toDateTime(request_time) + toIntervalDay(90)");
        assertThat(PayloadTtlReconciler.ttlClause(HEAD + " TTL toDateTime(request_time) + toIntervalDay(7)"))
                .contains("toDateTime(request_time) + toIntervalDay(7)");
        assertThat(PayloadTtlReconciler.ttlClause(NO_TTL)).isEmpty();
    }

    @Test
    void reconcileAltersTheConfiguredTable() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(30, Optional.of(TTL_90), ddl).reconcile();
        verify(ddl).execute("ALTER TABLE `dial_usage_log_payload` MODIFY TTL toDateTime(`request_time`) + INTERVAL 30 DAY");
    }

    @Test
    void reconcileIssuesNothingWhenTheTableMatches() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(90, Optional.of(TTL_90), ddl).reconcile();
        verify(ddl, never()).execute(anyString());
    }

    @Test
    void reconcileSkipsMissingTable() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        reconciler(30, Optional.empty(), ddl).reconcile();
        verify(ddl, never()).execute(anyString());
    }

    @Test
    void rejectedAlterPropagates() {
        final DdlExecutor ddl = mock(DdlExecutor.class);
        doThrow(new ClickHouseQueryException("rejected", new RuntimeException())).when(ddl).execute(anyString());
        assertThatThrownBy(() -> reconciler(30, Optional.of(TTL_90), ddl).reconcile())
                .isInstanceOf(ClickHouseQueryException.class);
    }

    private PayloadTtlReconciler reconciler(int ttlDays, Optional<String> engineFull, DdlExecutor ddl) {
        final ClickHouseTableInspector inspector = mock(ClickHouseTableInspector.class);
        when(inspector.engineFull(PayloadRetentionProperties.DEFAULT_TABLE)).thenReturn(engineFull);
        return new PayloadTtlReconciler(new PayloadRetentionProperties(ttlDays, null), inspector, ddl, renderer);
    }
}
