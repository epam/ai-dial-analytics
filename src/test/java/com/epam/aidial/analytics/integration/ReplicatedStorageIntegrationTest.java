package com.epam.aidial.analytics.integration;

import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseMigrationRunner;
import com.epam.aidial.analytics.clickhouse.ClickHouseWriterClient;
import com.epam.aidial.analytics.clickhouse.ReplicatedDdl;
import com.epam.aidial.analytics.clickhouse.replication.DatabaseEngineCheck;
import com.epam.aidial.analytics.clickhouse.replication.ReplicatedTableCheck;
import com.epam.aidial.analytics.clickhouse.replication.ReplicatedTableConversion;
import com.epam.aidial.analytics.clickhouse.replication.StorageReadyGate;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replicated storage on the single-replica Testcontainers ClickHouse: what the migrations provisioned, insert
 * deduplication off, the startup conversion of plain tables, the table check and the engine check. See
 * {@code openspec/specs/clickhouse-replication/spec.md}.
 */
class ReplicatedStorageIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String PLAIN = "rs_plain_orders";
    private static final String TWIN = ReplicatedTableConversion.PREFIX + PLAIN;
    private static final String DEDUPING = "rs_still_deduplicating";
    private static final String UPSERT = "rs_tickets";
    private static final String APPEND = "rs_events";

    @Autowired
    private ClickHouseWriterClient writer;

    @Autowired
    private ClickHouseProperties connection;

    @Autowired
    private ReplicatedTableConversion conversion;

    @Autowired
    private ReplicatedTableCheck tableCheck;

    @Autowired
    private StorageReadyGate gate;

    @AfterEach
    void dropScratchTables() {
        for (final String table : List.of(PLAIN, TWIN, DEDUPING, UPSERT, APPEND)) {
            execute("DROP TABLE IF EXISTS " + table + " SYNC");
        }
    }

    /** A plain table can only be created with the defence-in-depth setting lifted for the statement. */
    private void createPlain(String ddl) {
        writer.primaryClient().queryAll(ddl,
                new QuerySettings().serverSetting("database_replicated_allow_only_replicated_engine", "0"));
    }

    private Map<String, String> engine(String table) {
        final var row = writer.primaryClient().queryAll("SELECT engine, engine_full FROM system.tables "
                + "WHERE database = currentDatabase() AND name = {t:String}", Map.of("t", table)).getFirst();
        return Map.of("engine", row.getString("engine"), "engine_full", row.getString("engine_full"));
    }

    @Test
    void theStoragePhaseCompletedAndEveryProvisionedTableIsReplicatedWithoutDeduplication() {
        assertThat(gate.isOpen()).isTrue();
        for (final String table : List.of("dial_usage_log", "dial_usage_log_payload", "rate_analytics",
                "_dial_internal_ingested_blob", "_dial_internal_failed_ingest", ClickHouseMigrationRunner.HISTORY_TABLE)) {
            final Map<String, String> engine = engine(table);
            assertThat(engine.get("engine")).as(table).startsWith("Replicated").endsWith("MergeTree");
            assertThat(ReplicatedDdl.disablesDeduplication(engine.get("engine_full"))).as(table).isTrue();
        }
    }

    /** On 26.x a replicated table deduplicating by default would keep {@code closed}. */
    @Test
    void repeatedValueIsNotLostOnAnUpsertTable() throws Exception {
        execute("CREATE TABLE " + UPSERT + " (ticket_id String, status String, _updated_at DateTime64(3) DEFAULT now64(3)) "
                + "ENGINE = ReplicatedReplacingMergeTree(_updated_at) ORDER BY ticket_id SETTINGS "
                + ReplicatedDdl.NO_INSERT_DEDUPLICATION);
        for (final String status : List.of("open", "closed", "open")) {
            execute("INSERT INTO " + UPSERT + " (ticket_id, status) VALUES ('00000000-0000-4000-8000-000000000001', '"
                    + status + "')");
            Thread.sleep(5);
        }
        assertThat(queryScalar("SELECT status FROM " + UPSERT + " FINAL", "status")).isEqualTo("open");
    }

    @Test
    void identicalAppendBatchesAndIdenticalInsertSelectsAreAllKept() {
        execute("CREATE TABLE " + APPEND + " (k UInt32) ENGINE = ReplicatedMergeTree ORDER BY k SETTINGS "
                + ReplicatedDdl.NO_INSERT_DEDUPLICATION);
        execute("INSERT INTO " + APPEND + " VALUES (1), (2), (3)");
        execute("INSERT INTO " + APPEND + " VALUES (1), (2), (3)");
        assertThat(queryScalar("SELECT count() AS c FROM " + APPEND, "c")).isEqualTo("6");
        execute("INSERT INTO " + APPEND + " SELECT number FROM numbers(3)");
        execute("INSERT INTO " + APPEND + " SELECT number FROM numbers(3)");
        assertThat(queryScalar("SELECT count() AS c FROM " + APPEND, "c")).isEqualTo("12");
    }

    @Test
    void plainTableConvertsWithItsRowsAndDefinitionUnchanged() {
        createPlain("CREATE TABLE " + PLAIN + " (id UInt32, day Date, v String) ENGINE = ReplacingMergeTree "
                + "PARTITION BY toYYYYMM(day) ORDER BY id TTL day + INTERVAL 100 YEAR SETTINGS ttl_only_drop_parts = 1");
        execute("INSERT INTO " + PLAIN + " SELECT number, today(), toString(number) FROM numbers(1000)");

        conversion.convert();

        final Map<String, String> engine = engine(PLAIN);
        assertThat(engine.get("engine")).isEqualTo("ReplicatedReplacingMergeTree");
        assertThat(ReplicatedDdl.disablesDeduplication(engine.get("engine_full"))).isTrue();
        assertThat(engine.get("engine_full")).contains("PARTITION BY toYYYYMM(day)").contains("ORDER BY id")
                .contains("ttl_only_drop_parts = 1").contains("TTL day + toIntervalYear(100)");
        assertThat(queryScalar("SELECT count() AS c FROM " + PLAIN + " SETTINGS final = 0", "c")).isEqualTo("1000");
        assertThat(queryScalar("SELECT count() AS c FROM system.tables WHERE database = currentDatabase() AND name = '"
                + TWIN + "'", "c")).isEqualTo("0");
    }

    /** Stopped after the attach, before the exchange: the original serves, and the next start converts afresh. */
    @Test
    void conversionInterruptedAfterTheAttachIsResumed() {
        createPlain("CREATE TABLE " + PLAIN + " (id UInt32) ENGINE = MergeTree ORDER BY id");
        execute("INSERT INTO " + PLAIN + " SELECT number FROM numbers(10)");
        execute("CREATE TABLE " + TWIN + " (id UInt32) ENGINE = ReplicatedMergeTree ORDER BY id SETTINGS "
                + ReplicatedDdl.NO_INSERT_DEDUPLICATION);
        execute("ALTER TABLE " + TWIN + " ATTACH PARTITION ALL FROM " + PLAIN);
        assertThat(queryScalar("SELECT count() AS c FROM " + PLAIN, "c")).isEqualTo("10");

        conversion.convert();

        assertThat(engine(PLAIN).get("engine")).isEqualTo("ReplicatedMergeTree");
        assertThat(queryScalar("SELECT count() AS c FROM " + PLAIN, "c")).isEqualTo("10");
        assertThat(queryScalar("SELECT count() AS c FROM system.tables WHERE database = currentDatabase() AND name = '"
                + TWIN + "'", "c")).isEqualTo("0");
    }

    @Test
    void replicatedTableWithoutTheSettingGetsItWithoutRewritingData() {
        execute("CREATE TABLE " + DEDUPING + " (id UInt32) ENGINE = ReplicatedMergeTree ORDER BY id");
        execute("INSERT INTO " + DEDUPING + " VALUES (1)");
        final String partBefore = queryScalar("SELECT name FROM system.parts WHERE active AND database = currentDatabase() "
                + "AND table = '" + DEDUPING + "'", "name");

        conversion.convert();

        assertThat(ReplicatedDdl.disablesDeduplication(engine(DEDUPING).get("engine_full"))).isTrue();
        assertThat(queryScalar("SELECT name FROM system.parts WHERE active AND database = currentDatabase() "
                + "AND table = '" + DEDUPING + "'", "name")).isEqualTo(partBefore);
    }

    @Test
    void tableCheckRefusesPlainOwnedTableNamingItAndTheReason() {
        createPlain("CREATE TABLE " + PLAIN + " (id UInt32) ENGINE = MergeTree ORDER BY id");
        // A plain table the service owns: the history table stands in, renamed aside for the check.
        execute("RENAME TABLE " + ClickHouseMigrationRunner.HISTORY_TABLE + " TO rs_history_aside");
        try {
            execute("RENAME TABLE " + PLAIN + " TO " + ClickHouseMigrationRunner.HISTORY_TABLE);
            assertThatThrownBy(() -> tableCheck.check(Map.of(ClickHouseMigrationRunner.HISTORY_TABLE, "the reason given")))
                    .hasMessageContaining(ClickHouseMigrationRunner.HISTORY_TABLE)
                    .hasMessageContaining("MergeTree")
                    .hasMessageContaining("the reason given");
        } finally {
            execute("DROP TABLE IF EXISTS " + ClickHouseMigrationRunner.HISTORY_TABLE + " SYNC");
            execute("RENAME TABLE rs_history_aside TO " + ClickHouseMigrationRunner.HISTORY_TABLE);
        }
    }

    /** The container's {@code default} database is {@code Atomic}: refused, naming it and the procedure. */
    @Test
    void atomicDatabaseIsRefusedNamingItsEngineAndTheProcedure() {
        final ClickHouseProperties atomic = new ClickHouseProperties(connection.host(), connection.port(), "default",
                connection.user(), connection.password());
        assertThatThrownBy(() -> new DatabaseEngineCheck(writer, atomic, true).check())
                .hasMessageContaining("'default'")
                .hasMessageContaining("Atomic")
                .hasMessageContaining("docs/clickhouse-replication.md");
    }

    /**
     * Each shipped migration's {@code CREATE TABLE}, created as written and in the replicated form the pre-create hook
     * issues, yields the same table but for the engine and its settings: columns, sort key, primary key, partition
     * key and TTL.
     */
    @Test
    void preCreatedMigrationTablesMatchTheirOriginalDefinition() throws Exception {
        final List<String> compared = new ArrayList<>();
        for (final Resource resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/clickhouse/*.sql")) {
            final String script = ReplicatedDdl.stripLineComments(
                    new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            for (final String statement : script.split(";")) {
                if (!ReplicatedDdl.isCreateIfNotExists(statement)) {
                    continue;
                }
                final String table = ReplicatedDdl.createdTable(statement).orElseThrow();
                final String original = "rs_original_" + table;
                final String replicated = "rs_replicated_" + table;
                try {
                    createPlain(ReplicatedDdl.renamed(statement.strip(), connection.sessionDatabase(), original));
                    execute(ReplicatedDdl.toReplicated(ReplicatedDdl.renamed(statement.strip(), connection.sessionDatabase(),
                            replicated)));
                    assertThat(shape(replicated)).as(table).isEqualTo(shape(original));
                    assertThat(engine(replicated).get("engine")).as(table).startsWith("Replicated");
                    compared.add(table);
                } finally {
                    execute("DROP TABLE IF EXISTS " + original + " SYNC");
                    execute("DROP TABLE IF EXISTS " + replicated + " SYNC");
                }
            }
        }
        assertThat(compared).contains("dial_usage_log", "dial_usage_log_payload", "_dial_internal_ingested_blob");
    }

    /** What the engine swap must not change: columns with types, defaults and codecs, keys and the TTL. */
    private String shape(String table) {
        final StringBuilder shape = new StringBuilder();
        writer.primaryClient().queryAll("SELECT name, type, default_kind, default_expression, compression_codec "
                + "FROM system.columns WHERE database = currentDatabase() AND table = {t:String} ORDER BY position",
                Map.of("t", table)).forEach(row -> shape.append(row.getString("name")).append(' ')
                .append(row.getString("type")).append(' ').append(row.getString("default_kind")).append(' ')
                .append(row.getString("default_expression")).append(' ').append(row.getString("compression_codec"))
                .append('\n'));
        final var keys = writer.primaryClient().queryAll("SELECT sorting_key, primary_key, partition_key, engine_full "
                + "FROM system.tables WHERE database = currentDatabase() AND name = {t:String}", Map.of("t", table)).getFirst();
        final String engineFull = keys.getString("engine_full");
        final int ttl = engineFull.indexOf(" TTL ");
        final int settings = engineFull.indexOf(" SETTINGS ");
        shape.append(keys.getString("sorting_key")).append(" | ").append(keys.getString("primary_key")).append(" | ")
                .append(keys.getString("partition_key")).append(" | ")
                .append(ttl < 0 ? "" : engineFull.substring(ttl, settings < 0 ? engineFull.length() : settings));
        return shape.toString();
    }
}
