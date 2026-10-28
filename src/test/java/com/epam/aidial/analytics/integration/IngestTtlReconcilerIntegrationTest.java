package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector;
import com.epam.aidial.analytics.clickhouse.DdlExecutor;
import com.epam.aidial.analytics.configuration.PayloadRetentionProperties;
import com.epam.aidial.analytics.configuration.UsageLogRetentionProperties;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import com.epam.aidial.analytics.sync.IngestTtlReconciler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The usage-log and payload {@code TTL} reconciliation against the real server, on copies of the migrated tables so
 * the shared container's own tables keep their provisioned {@code TTL}s for every other test.
 *
 * <p>Requires a running Docker daemon.
 */
class IngestTtlReconcilerIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PAYLOAD_COPY = "payload_ttl_reconciler_it";
    private static final String USAGE_LOG_COPY = "usage_log_ttl_reconciler_it";

    @Autowired
    private ClickHouseTableInspector inspector;
    @Autowired
    private DdlExecutor ddl;
    @Autowired
    private TableDdlRenderer renderer;

    @BeforeEach
    void createCopies() {
        dropCopies();
        ddl.execute("CREATE TABLE " + PAYLOAD_COPY + " AS " + PayloadRetentionProperties.DEFAULT_TABLE);
        ddl.execute("CREATE TABLE " + USAGE_LOG_COPY + " AS " + UsageLogRetentionProperties.DEFAULT_TABLE);
    }

    @AfterEach
    void dropCopies() {
        ddl.execute("DROP TABLE IF EXISTS " + PAYLOAD_COPY);
        ddl.execute("DROP TABLE IF EXISTS " + USAGE_LOG_COPY);
    }

    @Test
    void theMigratedTablesAlreadyCarryTheDefaults() {
        // The startup reconciliation ran with the defaults, so it found 90 days and one year and left both alone.
        assertThat(engineFull(PayloadRetentionProperties.DEFAULT_TABLE))
                .contains("TTL toDateTime(request_time) + toIntervalDay(90)")
                .contains("ttl_only_drop_parts = 1");
        assertThat(engineFull(UsageLogRetentionProperties.DEFAULT_TABLE))
                .contains("TTL toDateTime(request_time) + toIntervalYear(1)")
                .contains("ttl_only_drop_parts = 1");
    }

    @Test
    void shorterRetentionReplacesTheTtlAndKeepsDropParts() {
        reconcile(null, 30);
        assertThat(engineFull(PAYLOAD_COPY))
                .contains("TTL toDateTime(request_time) + toIntervalDay(30)")
                .contains("ttl_only_drop_parts = 1");
    }

    @Test
    void usageLogDayCountReplacesTheYearAndKeepsItsSettings() {
        final String before = engineFull(USAGE_LOG_COPY);
        reconcile(180, 90);
        final String after = engineFull(USAGE_LOG_COPY);
        assertThat(after).contains("TTL toDateTime(request_time) + toIntervalDay(180)");
        assertThat(settings(after)).isEqualTo(settings(before)).contains("ttl_only_drop_parts = 1");
    }

    @Test
    void unsetUsageLogRetentionLeavesTheYearAlone() {
        final String before = engineFull(USAGE_LOG_COPY);
        reconcile(null, 90);
        assertThat(engineFull(USAGE_LOG_COPY)).isEqualTo(before);
    }

    @Test
    void matchingRetentionIsLeftAlone() {
        reconcile(180, 30);
        final String payloadAfter = engineFull(PAYLOAD_COPY);
        final String usageLogAfter = engineFull(USAGE_LOG_COPY);
        reconcile(180, 30);
        assertThat(engineFull(PAYLOAD_COPY)).isEqualTo(payloadAfter);
        assertThat(engineFull(USAGE_LOG_COPY)).isEqualTo(usageLogAfter);
    }

    @Test
    void minusOneRemovesTheTtlAndUnsetRestoresIt() {
        reconcile(-1, -1);
        assertThat(engineFull(PAYLOAD_COPY)).doesNotContain(" TTL ").contains("ttl_only_drop_parts = 1");
        assertThat(engineFull(USAGE_LOG_COPY)).doesNotContain(" TTL ").contains("ttl_only_drop_parts = 1");

        reconcile(null, 90);
        assertThat(engineFull(PAYLOAD_COPY)).contains("TTL toDateTime(request_time) + toIntervalDay(90)");
        assertThat(engineFull(USAGE_LOG_COPY)).contains("TTL toDateTime(request_time) + toIntervalYear(1)");
    }

    @Test
    void retentionSetByHandIsReverted() {
        ddl.execute("ALTER TABLE " + PAYLOAD_COPY + " MODIFY TTL toDateTime(request_time) + INTERVAL 45 DAY");
        ddl.execute("ALTER TABLE " + USAGE_LOG_COPY + " MODIFY TTL toDateTime(request_time) + INTERVAL 200 DAY");
        reconcile(null, 90);
        assertThat(engineFull(PAYLOAD_COPY)).contains("TTL toDateTime(request_time) + toIntervalDay(90)");
        assertThat(engineFull(USAGE_LOG_COPY)).contains("TTL toDateTime(request_time) + toIntervalYear(1)");
    }

    @Test
    void missingTableIsSkippedAndTheOtherStillReconciled() {
        new IngestTtlReconciler(new UsageLogRetentionProperties(180, USAGE_LOG_COPY),
                new PayloadRetentionProperties(30, "payload_ttl_absent_it"), inspector, ddl, renderer)
                .reconcile();
        assertThat(inspector.exists("payload_ttl_absent_it")).isFalse();
        assertThat(engineFull(USAGE_LOG_COPY)).contains("TTL toDateTime(request_time) + toIntervalDay(180)");
    }

    private void reconcile(Integer usageLogDays, int payloadDays) {
        new IngestTtlReconciler(new UsageLogRetentionProperties(usageLogDays, USAGE_LOG_COPY),
                new PayloadRetentionProperties(payloadDays, PAYLOAD_COPY), inspector, ddl, renderer).reconcile();
    }

    private String engineFull(String table) {
        return inspector.engineFull(table).orElseThrow();
    }

    private static String settings(String engineFull) {
        return engineFull.substring(engineFull.indexOf(" SETTINGS "));
    }
}
