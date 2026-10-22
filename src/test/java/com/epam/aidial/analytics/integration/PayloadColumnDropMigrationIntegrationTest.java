package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies against a real ClickHouse that {@code V11__drop_dial_usage_log_payload_columns.sql} has removed
 * the five verbatim-content columns from {@code dial_usage_log} and left everything around them alone.
 *
 * <p>Why this needs a test: nothing else in the suite reads {@code dial_usage_log}'s physical column list.
 * {@link com.epam.aidial.analytics.sync.mapping.SchemaValidator} only requires that every column a mapping
 * names exists on the table, and tolerates physical columns no mapping names — so it passes whether these
 * five are present or absent, and the drop would otherwise ship unasserted.
 *
 * <p>The {@code *_bytes} assertions are not padding. {@code request_body} is a prefix of
 * {@code request_body_bytes}, and a careless string replace over a migration, a mapping or a stored
 * declaration has eaten the wrong one before. The two size columns must survive the drop: they are four
 * bytes each and they must outlive the payload they describe, so that "how big were the bodies last
 * quarter" stays answerable past the payload table's 90-day window.
 *
 * <p>Requires a running Docker daemon.
 */
class PayloadColumnDropMigrationIntegrationTest extends AbstractClickHouseIntegrationTest {

    /** The columns V10 moved to {@code dial_usage_log_payload} and V11 dropped from the parent. */
    private static final String[] MOVED_COLUMNS = {
        "jwt_claims", "request_tags", "request_body", "response_body", "assembled_response",
    };

    @Test
    void dialUsageLogNoLongerDeclaresTheMovedColumns() {
        String ddl = showCreateTable("dial_usage_log");

        // Matched with the trailing space of the DDL's column alignment so `request_body` cannot be
        // satisfied by `request_body_bytes` and report a pass over a column that is still there.
        for (String column : MOVED_COLUMNS) {
            assertThat(ddl).doesNotContain("`" + column + "`");
        }
    }

    @Test
    void dialUsageLogKeepsTheBodySizeColumns() {
        String ddl = showCreateTable("dial_usage_log");

        assertThat(ddl).contains("`request_body_bytes`");
        assertThat(ddl).contains("`response_body_bytes`");
    }

    @Test
    void theMovedColumnsLiveOnThePayloadTable() {
        String ddl = showCreateTable("dial_usage_log_payload");

        // The other half of the drop: the content is not gone from the schema, it is addressed through
        // the enrichment instead.
        for (String column : MOVED_COLUMNS) {
            assertThat(ddl).contains("`" + column + "`");
        }
    }

    @Test
    void theDropLeavesTheSortKeyAndIndexesIntact() {
        String ddl = showCreateTable("dial_usage_log");

        // None of the five was in the sort key or carried an index, so the drop must have rewritten
        // neither. If it had, deduplication and every scan-index read would change shape silently.
        assertThat(ddl).contains("ORDER BY (project_id, deployment, request_time, trace_id, core_span_id)");
        assertThat(ddl).contains("INDEX idx_trace_id");
        assertThat(ddl).contains("INDEX idx_ingested_at");
    }
}
