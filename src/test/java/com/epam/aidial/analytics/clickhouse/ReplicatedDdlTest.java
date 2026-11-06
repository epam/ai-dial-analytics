package com.epam.aidial.analytics.clickhouse;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicatedDdlTest {

    private static final String NO_DEDUP = ReplicatedDdl.NO_INSERT_DEDUPLICATION;

    @Test
    void plainMergeTreeBecomesReplicatedWithSettingsAppended() {
        assertThat(ReplicatedDdl.toReplicated("CREATE TABLE IF NOT EXISTS t\n(id UInt32)\nENGINE = MergeTree\nORDER BY id;"))
                .isEqualTo("CREATE TABLE IF NOT EXISTS t\n(id UInt32)\nENGINE = ReplicatedMergeTree\nORDER BY id"
                        + "\nSETTINGS " + NO_DEDUP);
    }

    @Test
    void versionArgumentIsKeptAndExistingSettingsMerged() {
        assertThat(ReplicatedDdl.toReplicated("CREATE TABLE IF NOT EXISTS t (id UInt32, v DateTime64(3)) "
                + "ENGINE = ReplacingMergeTree(v) PARTITION BY toYYYYMM(v) ORDER BY id SETTINGS ttl_only_drop_parts = 1"))
                .isEqualTo("CREATE TABLE IF NOT EXISTS t (id UInt32, v DateTime64(3)) "
                        + "ENGINE = ReplicatedReplacingMergeTree(v) PARTITION BY toYYYYMM(v) ORDER BY id SETTINGS "
                        + NO_DEDUP + ", ttl_only_drop_parts = 1");
    }

    /** {@code create_table_query} of a replicated table spells its Keeper arguments; the twin takes the defaults. */
    @Test
    void explicitKeeperArgumentsAreDroppedAndAnExistingWindowReplaced() {
        assertThat(ReplicatedDdl.toReplicated("CREATE TABLE db.t (`id` UInt32, `v` DateTime) ENGINE = "
                + "ReplicatedReplacingMergeTree('/clickhouse/tables/{uuid}/{shard}', '{replica}', v) ORDER BY id "
                + "SETTINGS replicated_deduplication_window = 100, index_granularity = 8192"))
                .isEqualTo("CREATE TABLE db.t (`id` UInt32, `v` DateTime) ENGINE = ReplicatedReplacingMergeTree(v) "
                        + "ORDER BY id SETTINGS " + NO_DEDUP + ", index_granularity = 8192");
    }

    @Test
    void settingsGoBeforeTrailingComment() {
        assertThat(ReplicatedDdl.toReplicated("CREATE TABLE t (id UInt32) ENGINE = MergeTree ORDER BY id COMMENT 'x'"))
                .isEqualTo("CREATE TABLE t (id UInt32) ENGINE = ReplicatedMergeTree ORDER BY id SETTINGS " + NO_DEDUP
                        + " COMMENT 'x'");
    }

    @Test
    void deduplicationSettingsWrittenLastOrInTheMiddleLeaveNoDanglingComma() {
        assertThat(ReplicatedDdl.toReplicated("CREATE TABLE IF NOT EXISTS t (id UInt32) ENGINE = ReplicatedMergeTree ORDER BY id "
                + "SETTINGS ttl_only_drop_parts = 1, replicated_deduplication_window = 0, "
                + "replicated_deduplication_window_for_async_inserts = 0"))
                .isEqualTo("CREATE TABLE IF NOT EXISTS t (id UInt32) ENGINE = ReplicatedMergeTree ORDER BY id SETTINGS "
                        + NO_DEDUP + ", ttl_only_drop_parts = 1");
        assertThat(ReplicatedDdl.toReplicated("CREATE TABLE t (id UInt32) ENGINE = MergeTree ORDER BY id SETTINGS "
                + "index_granularity = 8192, replicated_deduplication_window = 100, storage_policy = 'a,b' COMMENT 'x, y'"))
                .isEqualTo("CREATE TABLE t (id UInt32) ENGINE = ReplicatedMergeTree ORDER BY id SETTINGS " + NO_DEDUP
                        + ", index_granularity = 8192, storage_policy = 'a,b' COMMENT 'x, y'");
        assertThat(ReplicatedDdl.toReplicated("CREATE TABLE t (id UInt32) ENGINE = MergeTree ORDER BY id SETTINGS "
                + "replicated_deduplication_window = 100 COMMENT 'x'"))
                .isEqualTo("CREATE TABLE t (id UInt32) ENGINE = ReplicatedMergeTree ORDER BY id SETTINGS " + NO_DEDUP
                        + " COMMENT 'x'");
    }

    @Test
    void renamedTwinStripsTheUuidClause() {
        assertThat(ReplicatedDdl.renamed("CREATE TABLE adas.orders UUID '00000000-0000-4000-8000-000000000001' "
                + "(`id` UInt32) ENGINE = MergeTree ORDER BY id", "adas", "_adas_replicate_orders"))
                .isEqualTo("CREATE TABLE `adas`.`_adas_replicate_orders` (`id` UInt32) ENGINE = MergeTree ORDER BY id");
    }

    @Test
    void refusesStatementWithoutMergeTreeEngine() {
        assertThatThrownBy(() -> ReplicatedDdl.toReplicated("CREATE TABLE t (x UInt8) ENGINE = Memory"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ReplicatedDdl.isCreateIfNotExists("CREATE TABLE IF NOT EXISTS t (x UInt8) ENGINE = Memory")).isFalse();
    }

    @Test
    void recognisesReplicatedAndDeduplicationFreeEngines() {
        assertThat(ReplicatedDdl.isReplicated("ReplicatedReplacingMergeTree")).isTrue();
        assertThat(ReplicatedDdl.isReplicated("ReplacingMergeTree")).isFalse();
        assertThat(ReplicatedDdl.isMergeTreeFamily("View")).isFalse();
        assertThat(ReplicatedDdl.disablesDeduplication("ReplicatedMergeTree ORDER BY id SETTINGS index_granularity = "
                + "8192, " + NO_DEDUP)).isTrue();
        assertThat(ReplicatedDdl.disablesDeduplication("ReplicatedMergeTree ORDER BY id SETTINGS "
                + "replicated_deduplication_window = 0")).isFalse();
    }

    /**
     * Every {@code CREATE TABLE} the shipped migrations declare transforms, so a fresh install pre-creates each one
     * replicated; a later migration that already declares a replicated engine transforms to itself plus nothing new.
     */
    @Test
    void everyShippedMigrationCreateTransforms() throws Exception {
        final List<String> creates = new ArrayList<>();
        for (final Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/clickhouse/*.sql")) {
            final String script = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (final String statement : ReplicatedDdl.stripLineComments(script).split(";")) {
                if (ReplicatedDdl.createdTable(statement).isPresent()) {
                    assertThat(ReplicatedDdl.isCreateIfNotExists(statement))
                            .as("%s declares CREATE TABLE IF NOT EXISTS with a MergeTree-family engine", resource.getFilename())
                            .isTrue();
                    creates.addAll(MigrationPreCreate.preCreates(statement));
                }
            }
        }
        assertThat(creates).hasSizeGreaterThanOrEqualTo(5).allSatisfy(ddl -> assertThat(ddl)
                .contains("ENGINE = Replicated")
                .containsOnlyOnce("SETTINGS")
                .contains(NO_DEDUP));
    }
}
