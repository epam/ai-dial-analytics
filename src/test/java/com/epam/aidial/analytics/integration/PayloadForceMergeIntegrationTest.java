package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves against a real ClickHouse the mechanics {@code V12__force_merge_settled_partitions.sql} relies
 * on: {@code min_age_to_force_merge_seconds} with {@code min_age_to_force_merge_on_partition_only = 1}, added
 * by {@code ALTER ... MODIFY SETTING} to a table that never carried them, collapses a settled multi-part day
 * partition of a {@code ReplacingMergeTree(_updated_at)} to one part holding one row per key — the latest —
 * and leaves alone a partition that still holds a part younger than the interval. The vertical-merge
 * thresholds V12 sets on the payload table alongside are applied too, and the collapse is asserted to have
 * run with the vertical algorithm.
 *
 * <p>The shipped interval is two days, which no test can wait out, so the mechanics run on scratch tables of
 * the payload table's shape with a threshold of seconds, and
 * {@link IngestTableRetentionSettingsIntegrationTest} pins the shipped value on the real table.
 *
 * <p>The partitions are deliberately lopsided — one large part plus a few small ones — because that is the
 * shape a settled payload day has and the one the automatic merge selector leaves unmerged. A shape it would
 * merge on its own would let the collapse pass without the setting doing anything. Merges are stopped while
 * the parts are seeded and aged so that nothing merges before the setting is in place.
 *
 * <p>Requires a running Docker daemon.
 */
class PayloadForceMergeIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String COLLAPSE_TABLE = "force_merge_probe_collapse";

    private static final String REAPPLY_TABLE = "force_merge_probe_reapply";

    /** Every part of this day is older than the threshold once the setting lands. */
    private static final String SETTLED_DAY = "2026-01-01";

    private static final String SETTLED_PARTITION = "20260101";

    /** This day receives a late write just before the setting lands, so it holds one young part. */
    private static final String OPEN_DAY = "2026-01-02";

    private static final String OPEN_PARTITION = "20260102";

    private static final int ALL_KEYS = 20_000;

    /** The keys a later write rewrites; each rewrite is a small part next to the large first one. */
    private static final int REWRITTEN_KEYS = 1_000;

    /**
     * Seconds, not days. Long enough that the late write into {@link #OPEN_DAY} is still young when the
     * settled day's merge is observed — the negative case is only meaningful inside that window.
     */
    private static final int THRESHOLD_SECONDS = 15;

    /** Generous: the merge itself takes milliseconds; this only bounds a slow background scheduler. */
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    /** The vertical-merge thresholds V12 sets on the payload table next to the force merge. */
    private static final String VERTICAL = "vertical_merge_algorithm_min_columns_to_activate = 1, vertical_merge_algorithm_min_rows_to_activate = 1";

    private static final String RESET = " RESET SETTING min_age_to_force_merge_seconds, min_age_to_force_merge_on_partition_only, "
            + "vertical_merge_algorithm_min_columns_to_activate, vertical_merge_algorithm_min_rows_to_activate";

    @BeforeEach
    @AfterEach
    void dropScratchTables() {
        execute("DROP TABLE IF EXISTS " + COLLAPSE_TABLE + " SYNC");
        execute("DROP TABLE IF EXISTS " + REAPPLY_TABLE + " SYNC");
    }

    @Test
    void settledPartitionCollapsesToOneRowPerKeyWhilePartitionWithYoungPartIsLeftAlone() throws InterruptedException {
        execute(createScratch(COLLAPSE_TABLE));
        final String original = showCreateTable(COLLAPSE_TABLE);
        execute("SYSTEM STOP MERGES " + COLLAPSE_TABLE);

        insertVersion(COLLAPSE_TABLE, SETTLED_DAY, ALL_KEYS, 1);
        insertVersion(COLLAPSE_TABLE, SETTLED_DAY, REWRITTEN_KEYS, 2);
        insertVersion(COLLAPSE_TABLE, SETTLED_DAY, REWRITTEN_KEYS, 3);
        insertVersion(COLLAPSE_TABLE, OPEN_DAY, ALL_KEYS, 1);
        assertThat(activeParts(COLLAPSE_TABLE, SETTLED_PARTITION)).isEqualTo(3);
        assertThat(rows(COLLAPSE_TABLE, SETTLED_PARTITION)).isEqualTo(ALL_KEYS + 2 * REWRITTEN_KEYS);

        awaitUntil("every seeded part to be older than the threshold",
                () -> youngestPartAgeSeconds(COLLAPSE_TABLE, "1") > THRESHOLD_SECONDS);
        // The late write: the open day now has an old part and a young one.
        insertVersion(COLLAPSE_TABLE, OPEN_DAY, REWRITTEN_KEYS, 2);
        execute("ALTER TABLE " + COLLAPSE_TABLE + " MODIFY SETTING min_age_to_force_merge_seconds = " + THRESHOLD_SECONDS
                + ", min_age_to_force_merge_on_partition_only = 1, " + VERTICAL);
        execute("SYSTEM START MERGES " + COLLAPSE_TABLE);

        awaitUntil("the settled partition to collapse to one part", () -> activeParts(COLLAPSE_TABLE, SETTLED_PARTITION) == 1);

        // One non-key column is far below the default threshold of 11, so this merge is vertical only because of the setting.
        execute("SYSTEM FLUSH LOGS");
        assertThat(queryScalar("SELECT any(merge_algorithm) AS algorithm FROM system.part_log WHERE database = currentDatabase() AND table = '"
                + COLLAPSE_TABLE + "' AND event_type = 'MergeParts' AND partition_id = '" + SETTLED_PARTITION + "'", "algorithm"))
                .isEqualTo("Vertical");

        // One part, one row per key, and that row is the latest version: the rewritten keys hold v3, v2 is gone.
        assertThat(rows(COLLAPSE_TABLE, SETTLED_PARTITION)).isEqualTo(ALL_KEYS);
        assertThat(count(COLLAPSE_TABLE, "_partition_id = '" + SETTLED_PARTITION + "' AND body = 'v3'")).isEqualTo(REWRITTEN_KEYS);
        assertThat(count(COLLAPSE_TABLE, "_partition_id = '" + SETTLED_PARTITION + "' AND body = 'v2'")).isZero();

        // The negative case holds only while the late part is younger than the threshold; past it the open day
        // would legitimately be merged too, so a slow scheduler has to fail loudly rather than pass vacuously.
        assertThat(youngestPartAgeSeconds(COLLAPSE_TABLE, "partition = '" + OPEN_PARTITION + "'"))
                .as("the late part must still be younger than the threshold for the negative case to mean anything")
                .isLessThan(THRESHOLD_SECONDS);
        assertThat(activeParts(COLLAPSE_TABLE, OPEN_PARTITION)).isEqualTo(2);
        assertThat(rows(COLLAPSE_TABLE, OPEN_PARTITION)).isEqualTo(ALL_KEYS + REWRITTEN_KEYS);

        // The rollback the docs give: the declaration goes back to exactly what it was, other settings kept.
        execute("ALTER TABLE " + COLLAPSE_TABLE + RESET);
        final String reset = showCreateTable(COLLAPSE_TABLE);
        assertThat(reset).doesNotContain("min_age_to_force_merge");
        assertThat(reset).isEqualTo(original);
    }

    @Test
    void reapplyingTheSettingsIsHarmless() {
        execute(createScratch(REAPPLY_TABLE));
        final String modify = "ALTER TABLE " + REAPPLY_TABLE
                + " MODIFY SETTING min_age_to_force_merge_seconds = 172800, min_age_to_force_merge_on_partition_only = 1, " + VERTICAL;

        execute(modify);
        final String applied = showCreateTable(REAPPLY_TABLE);
        assertThat(applied).contains("min_age_to_force_merge_seconds = 172800");
        assertThat(applied).contains("min_age_to_force_merge_on_partition_only = 1");
        assertThat(applied).contains("vertical_merge_algorithm_min_columns_to_activate = 1");
        assertThat(applied).contains("ttl_only_drop_parts = 1");

        execute(modify);
        assertThat(showCreateTable(REAPPLY_TABLE)).isEqualTo(applied);
    }

    /**
     * The payload table's engine, sort key and partitioning, and its version column stamped by default. No
     * TTL: the fixed past dates the partitions use would be expired on arrival. {@code min_bytes_for_wide_part = 0}
     * stands in for the payload table's size: ClickHouse merges vertically only into a wide part, which a real
     * payload partition always is and these few MiB of rows otherwise would not be.
     */
    private static String createScratch(String table) {
        return "CREATE TABLE " + table + " (event_id UUID, request_time DateTime64(3, 'UTC'), "
                + "_updated_at DateTime64(3) DEFAULT now64(3), body String) "
                + "ENGINE = ReplacingMergeTree(_updated_at) PARTITION BY toYYYYMMDD(request_time) ORDER BY event_id "
                + "SETTINGS ttl_only_drop_parts = 1, min_bytes_for_wide_part = 0";
    }

    /**
     * Writes keys {@code 0..keys-1} into {@code day} as version {@code version}: one insert, so one new part.
     * The version is written explicitly rather than stamped, so "latest" does not depend on insert timing.
     */
    private void insertVersion(String table, String day, int keys, int version) {
        execute("INSERT INTO " + table + " (event_id, request_time, _updated_at, body) "
                + "SELECT toUUID(concat('00000000-0000-0000-0000-', leftPad(toString(number), 12, '0'))), "
                + "toDateTime64('" + day + " 12:00:00', 3, 'UTC'), toDateTime64('2026-01-01 00:00:0" + version + "', 3), "
                + "'v" + version + "' FROM numbers(" + keys + ")");
    }

    private long activeParts(String table, String partition) {
        return Long.parseLong(queryScalar("SELECT count() AS n FROM system.parts WHERE database = currentDatabase() AND table = '"
                + table + "' AND active AND partition = '" + partition + "'", "n"));
    }

    /** Raw rows, as stored: no {@code FINAL}, so duplicates that have not been merged away are counted. */
    private long rows(String table, String partition) {
        return count(table, "_partition_id = '" + partition + "'");
    }

    private long count(String table, String where) {
        return Long.parseLong(queryScalar("SELECT count() AS n FROM " + table + " WHERE " + where, "n"));
    }

    /** Age in seconds of the youngest active part matching {@code where}, as the force-merge check sees it. */
    private long youngestPartAgeSeconds(String table, String where) {
        return Long.parseLong(queryScalar("SELECT toInt64(now() - max(modification_time)) AS age FROM system.parts "
                + "WHERE database = currentDatabase() AND table = '" + table + "' AND active AND " + where, "age"));
    }

    private static void awaitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out after " + TIMEOUT + " waiting for " + what);
            }
            Thread.sleep(250);
        }
    }
}
