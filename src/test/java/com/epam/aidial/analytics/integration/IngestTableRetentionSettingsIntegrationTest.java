package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies against a real ClickHouse that the migrations leave both ingest targets configured to expire
 * data by <em>dropping</em> whole parts rather than rewriting them ({@code ttl_only_drop_parts = 1}, applied
 * by {@code V9__ttl_only_drop_parts.sql}).
 *
 * <p>Why this is worth a test rather than trusting the DDL: a row-level {@code TTL} is applied by rewriting
 * each part, which needs free disk equal to the part being rewritten — so on a full volume the retention
 * mechanism is the one thing that cannot run. The setting is what makes expiry drop parts instead, and it is
 * invisible in the column set the schema validator reconciles, so nothing else in the suite would notice if
 * the migration were dropped or the setting reverted. The assertion reads the live DDL, so it also confirms
 * the pinned server version accepts {@code ALTER TABLE ... MODIFY SETTING} at all.
 *
 * <p>Both tables are asserted because both carry the same one-year {@code TTL} on {@code request_time} with
 * daily partitioning, and therefore the same trap.
 *
 * <p>The payload table and {@code dial_usage_log} additionally carry the force-merge settings of
 * {@code V12__force_merge_settled_partitions.sql}, and {@code rate_analytics} the same ones from
 * {@code V14__force_merge_settled_rate_analytics.sql}, asserted here for the same reason: they are invisible to
 * the schema validator.
 *
 * <p>Requires a running Docker daemon.
 */
class IngestTableRetentionSettingsIntegrationTest extends AbstractClickHouseIntegrationTest {

    /** The setting as ClickHouse renders it inside the table's {@code SETTINGS} clause. */
    private static final String DROP_PARTS = "ttl_only_drop_parts = 1";

    private static final String FORCE_MERGE_AGE = "min_age_to_force_merge_seconds = 172800";

    /**
     * Without it the force merge may take any run of old parts, so a partition holding a newer part is merged
     * piecemeal and can keep several; with it a partition is merged whole, only once settled.
     */
    private static final String FORCE_MERGE_WHOLE_PARTITION = "min_age_to_force_merge_on_partition_only = 1";

    /**
     * The payload table's two thresholds for the vertical merge algorithm. Its 8 non-key columns fall short of
     * the default 11, so without them every merge of its heavy rows is horizontal.
     */
    private static final String VERTICAL_MIN_COLUMNS = "vertical_merge_algorithm_min_columns_to_activate = 1";

    private static final String VERTICAL_MIN_ROWS = "vertical_merge_algorithm_min_rows_to_activate = 1";

    @Test
    void dialUsageLogExpiresByDroppingParts() {
        String ddl = showCreateTable("dial_usage_log");

        // Contains, not equals: the clause also carries index_granularity (and whatever a later server
        // version adds), and the TTL expression itself is V1's business, asserted elsewhere.
        assertThat(ddl).contains(DROP_PARTS);
        assertThat(ddl).contains("TTL toDateTime(request_time)");
    }

    @Test
    void rateAnalyticsExpiresByDroppingParts() {
        String ddl = showCreateTable("rate_analytics");

        assertThat(ddl).contains(DROP_PARTS);
        assertThat(ddl).contains("TTL toDateTime(request_time)");
    }

    @Test
    void payloadTableExpiresByDroppingPartsAfterNinetyDays() {
        String ddl = showCreateTable("dial_usage_log_payload");

        assertThat(ddl).contains(DROP_PARTS);
        // The shorter retention is the reason this table exists: 90 days against dial_usage_log's year.
        assertThat(ddl).contains("TTL toDateTime(request_time) + toIntervalDay(90)");
        // Load-bearing, not cosmetic: with ttl_only_drop_parts a part is dropped only once its NEWEST
        // row has expired, so without daily partitions expiry degrades to "never" as merges widen each
        // part's date span.
        assertThat(ddl).contains("PARTITION BY toYYYYMMDD(request_time)");
    }

    /**
     * Pins the shipped force-merge interval and vertical-merge thresholds of
     * {@code V12__force_merge_settled_partitions.sql}. The
     * mechanics are proven on a scratch table by {@link PayloadForceMergeIntegrationTest}, because a real
     * two-day wait is impossible in a test; this is what ties them to the value the migration ships. It also
     * guards that the {@code MODIFY SETTING} left V10's retention clauses alone.
     */
    @Test
    void payloadTableForceMergesSettledPartitionsAfterTwoDays() {
        String ddl = showCreateTable("dial_usage_log_payload");

        assertThat(ddl).contains(FORCE_MERGE_AGE);
        assertThat(ddl).contains(FORCE_MERGE_WHOLE_PARTITION);
        assertThat(ddl).contains(VERTICAL_MIN_COLUMNS);
        assertThat(ddl).contains(VERTICAL_MIN_ROWS);
        assertThat(ddl).contains(DROP_PARTS);
        assertThat(ddl).contains("PARTITION BY toYYYYMMDD(request_time)");
        assertThat(ddl).contains("TTL toDateTime(request_time) + toIntervalDay(90)");
    }

    /** The same value and the same guard on {@code dial_usage_log}, whose one-year retention must survive the {@code ALTER}. */
    @Test
    void dialUsageLogForceMergesSettledPartitionsAfterTwoDays() {
        String ddl = showCreateTable("dial_usage_log");

        assertThat(ddl).contains(FORCE_MERGE_AGE);
        assertThat(ddl).contains(FORCE_MERGE_WHOLE_PARTITION);
        assertThat(ddl).contains(DROP_PARTS);
        assertThat(ddl).contains("PARTITION BY toYYYYMMDD(request_time)");
        assertThat(ddl).contains("TTL toDateTime(request_time) + toIntervalYear(1)");
        // Light rows: the vertical-merge thresholds are the payload table's alone.
        assertThat(ddl).doesNotContain("vertical_merge_algorithm");
    }

    /** The same value and guard on {@code rate_analytics} (V14), whose retention must survive the {@code ALTER}. */
    @Test
    void rateAnalyticsForceMergesSettledPartitionsAfterTwoDays() {
        String ddl = showCreateTable("rate_analytics");

        assertThat(ddl).contains(FORCE_MERGE_AGE);
        assertThat(ddl).contains(FORCE_MERGE_WHOLE_PARTITION);
        assertThat(ddl).contains(DROP_PARTS);
        assertThat(ddl).contains("PARTITION BY toYYYYMMDD(request_time)");
        assertThat(ddl).contains("TTL toDateTime(request_time) + toIntervalYear(1)");
        // Light rows, like dial_usage_log: the vertical-merge thresholds are the payload table's alone.
        assertThat(ddl).doesNotContain("vertical_merge_algorithm");
    }

    /**
     * Pins the physical shape the follow-up change depends on. Engine, sort key and partitioning are
     * fixed at {@code CREATE TABLE} and cannot be {@code ALTER}ed, so a divergence here would only be
     * discovered when the table is catalogued as an enrichment of {@code dial_usage_log} — at which
     * point the fix is recreating the table and copying every row, the operation this table exists to
     * avoid. Failing the build now is much cheaper.
     */
    @Test
    void payloadTableKeepsTheShapeTheGrainJoinWillNeed() {
        String ddl = showCreateTable("dial_usage_log_payload");

        // event_id alone: a grain join keys on ONE column, and being the sole ORDER BY column is what
        // makes the ReplacingMergeTree collapse per grain correct. SHOW CREATE prints a one-column key bare
        // on 26.3 and parenthesised from 26.8, so the match accepts both spellings and nothing else.
        assertThat(ddl).containsPattern("ORDER BY \\(?event_id\\)?(?![\\w,])");
        // The version column every enrichment carries, stamped by ClickHouse rather than the caller.
        assertThat(ddl).contains("ReplacingMergeTree(_updated_at)");
    }
}
