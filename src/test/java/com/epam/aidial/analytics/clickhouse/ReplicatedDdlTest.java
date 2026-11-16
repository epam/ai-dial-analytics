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
    void lowercaseKeywordsAreRecognized() {
        assertThat(ReplicatedDdl.isCreateIfNotExists("create table if not exists t (id UInt32) engine = MergeTree order by id"))
                .isTrue();
        assertThat(ReplicatedDdl.toReplicated("create table t (id UInt32) engine = MergeTree order by id settings index_granularity = 8192"))
                .isEqualTo("create table t (id UInt32) ENGINE = ReplicatedMergeTree order by id settings " + NO_DEDUP
                        + ", index_granularity = 8192");
    }

    /** The word in a table comment is not the SETTINGS clause. */
    @Test
    void settingsInsideTheTableCommentAreNotTheClause() {
        assertThat(ReplicatedDdl.toReplicated("CREATE TABLE t (id UInt32) ENGINE = MergeTree ORDER BY id COMMENT 'no SETTINGS here'"))
                .isEqualTo("CREATE TABLE t (id UInt32) ENGINE = ReplicatedMergeTree ORDER BY id SETTINGS " + NO_DEDUP
                        + " COMMENT 'no SETTINGS here'");
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

    /** A column or key named {@code settings} is not the SETTINGS clause; the clause is the trailing `name = value` list. */
    @Test
    void columnNamedSettingsIsNotTheClause() {
        assertThat(ReplicatedDdl.toReplicated("CREATE TABLE t (tenant String, settings String) ENGINE = MergeTree "
                + "ORDER BY (tenant, settings)"))
                .isEqualTo("CREATE TABLE t (tenant String, settings String) ENGINE = ReplicatedMergeTree "
                        + "ORDER BY (tenant, settings)\nSETTINGS " + NO_DEDUP);
        assertThat(ReplicatedDdl.toReplicated("CREATE TABLE t (settings String) ENGINE = MergeTree ORDER BY settings "
                + "SETTINGS index_granularity = 8192"))
                .isEqualTo("CREATE TABLE t (settings String) ENGINE = ReplicatedMergeTree ORDER BY settings SETTINGS "
                        + NO_DEDUP + ", index_granularity = 8192");
    }

    /** An {@code engine = …} inside a column definition is not the table's engine. */
    @Test
    void engineInsideColumnIsNotTheTableEngine() {
        final String statement = "CREATE TABLE IF NOT EXISTS t (id UInt32, note String DEFAULT 'engine = Log', "
                + "flag UInt8 COMMENT 'engine = Memory') ENGINE = MergeTree ORDER BY id";
        assertThat(ReplicatedDdl.isCreateIfNotExists(statement)).isTrue();
        assertThat(ReplicatedDdl.toReplicated(statement))
                .isEqualTo("CREATE TABLE IF NOT EXISTS t (id UInt32, note String DEFAULT 'engine = Log', "
                        + "flag UInt8 COMMENT 'engine = Memory') ENGINE = ReplicatedMergeTree ORDER BY id\nSETTINGS " + NO_DEDUP);
    }

    /** A column comment is not the table comment the SETTINGS clause goes before. */
    @Test
    void columnCommentIsNotTheTableComment() {
        assertThat(ReplicatedDdl.toReplicated("CREATE TABLE t (id UInt32 COMMENT 'id') ENGINE = MergeTree ORDER BY id"))
                .isEqualTo("CREATE TABLE t (id UInt32 COMMENT 'id') ENGINE = ReplicatedMergeTree ORDER BY id\nSETTINGS "
                        + NO_DEDUP);
    }

    /** Literals keep their {@code ;}, {@code --} and {@code /*}; comments outside them go, block comments included. */
    @Test
    void scriptsSplitOutsideLiteralsAndComments() {
        assertThat(ReplicatedDdl.statements("""
                /* a leading block comment; with a semicolon */
                CREATE TABLE IF NOT EXISTS t (id UInt32 COMMENT 'a; b -- c /* d') ENGINE = MergeTree ORDER BY id; -- trailing
                -- a line comment; with a semicolon
                ALTER TABLE t COMMENT COLUMN id 'it''s; fine';
                ;
                """))
                .containsExactly(
                        "CREATE TABLE IF NOT EXISTS t (id UInt32 COMMENT 'a; b -- c /* d') ENGINE = MergeTree ORDER BY id",
                        "ALTER TABLE t COMMENT COLUMN id 'it''s; fine'");
        assertThat(MigrationPreCreate.preCreates("/* header */ CREATE TABLE IF NOT EXISTS t (id UInt32) ENGINE = MergeTree "
                + "ORDER BY id COMMENT 'x; y'"))
                .containsExactly("CREATE TABLE IF NOT EXISTS t (id UInt32) ENGINE = ReplicatedMergeTree ORDER BY id SETTINGS "
                        + NO_DEDUP + " COMMENT 'x; y'");
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
            for (final String statement : ReplicatedDdl.statements(script)) {
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
