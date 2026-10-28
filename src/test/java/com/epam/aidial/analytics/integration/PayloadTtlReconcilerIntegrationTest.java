package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector;
import com.epam.aidial.analytics.clickhouse.DdlExecutor;
import com.epam.aidial.analytics.configuration.PayloadRetentionProperties;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import com.epam.aidial.analytics.sync.PayloadTtlReconciler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payload {@code TTL} reconciliation against the real server, on a copy of the migrated payload table so the
 * shared container's own table keeps its 90-day {@code TTL} for every other test.
 *
 * <p>Requires a running Docker daemon.
 */
class PayloadTtlReconcilerIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String COPY = "payload_ttl_reconciler_it";

    @Autowired
    private ClickHouseTableInspector inspector;
    @Autowired
    private DdlExecutor ddl;
    @Autowired
    private TableDdlRenderer renderer;

    @BeforeEach
    void createCopy() {
        ddl.execute("DROP TABLE IF EXISTS " + COPY);
        ddl.execute("CREATE TABLE " + COPY + " AS " + PayloadRetentionProperties.DEFAULT_TABLE);
    }

    @AfterEach
    void dropCopy() {
        ddl.execute("DROP TABLE IF EXISTS " + COPY);
    }

    @Test
    void theMigratedPayloadTableAlreadyCarriesTheDefault() {
        // The startup reconciliation ran with the default, so it found 90 days and left the table alone.
        assertThat(engineFull(PayloadRetentionProperties.DEFAULT_TABLE))
                .contains("TTL toDateTime(request_time) + toIntervalDay(90)")
                .contains("ttl_only_drop_parts = 1");
    }

    @Test
    void shorterRetentionReplacesTheTtlAndKeepsDropParts() {
        reconcile(30);
        assertThat(engineFull(COPY))
                .contains("TTL toDateTime(request_time) + toIntervalDay(30)")
                .contains("ttl_only_drop_parts = 1");
    }

    @Test
    void matchingRetentionIsLeftAlone() {
        reconcile(30);
        final String after = engineFull(COPY);
        reconcile(30);
        assertThat(engineFull(COPY)).isEqualTo(after);
    }

    @Test
    void minusOneRemovesTheTtlAndPositiveValueRestoresIt() {
        reconcile(-1);
        assertThat(engineFull(COPY)).doesNotContain(" TTL ").contains("ttl_only_drop_parts = 1");

        reconcile(90);
        assertThat(engineFull(COPY)).contains("TTL toDateTime(request_time) + toIntervalDay(90)");
    }

    @Test
    void retentionSetByHandIsReverted() {
        ddl.execute("ALTER TABLE " + COPY + " MODIFY TTL toDateTime(request_time) + INTERVAL 45 DAY");
        reconcile(90);
        assertThat(engineFull(COPY)).contains("TTL toDateTime(request_time) + toIntervalDay(90)");
    }

    @Test
    void missingTableIsSkipped() {
        new PayloadTtlReconciler(new PayloadRetentionProperties(30, "payload_ttl_absent_it"), inspector, ddl, renderer)
                .reconcile();
        assertThat(inspector.exists("payload_ttl_absent_it")).isFalse();
    }

    private void reconcile(int ttlDays) {
        new PayloadTtlReconciler(new PayloadRetentionProperties(ttlDays, COPY), inspector, ddl, renderer).reconcile();
    }

    private String engineFull(String table) {
        return inspector.engineFull(table).orElseThrow();
    }
}
