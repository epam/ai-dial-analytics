package com.epam.aidial.analytics.clickhouse.replication;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseWriterClient;
import com.epam.aidial.analytics.clickhouse.DdlSettings;
import com.epam.aidial.analytics.clickhouse.ReplicatedDdl;
import com.epam.aidial.analytics.configuration.ClickHouseDdlProperties;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Converts every non-replicated {@code MergeTree}-family table of the {@code Replicated} session database into its
 * replicated form, at startup, before the migrations. The tables come from {@code system.tables}, not from a list:
 * the session database is the service's own, so catalog tables, OTel sinks, relayout copies and the Flyway history
 * table are all covered.
 *
 * <p>One table converts in six steps, each leaving the database usable:
 * <ol>
 *   <li>create a twin under {@link #PREFIX} from the table's {@code create_table_query}, replicated
 *       ({@link ReplicatedDdl#toReplicated}), with any {@code UUID} clause removed;</li>
 *   <li>{@code ATTACH PARTITION ALL FROM} the table — hardlinks, no data rewritten;</li>
 *   <li>compare physical row counts, without {@code FINAL}; a mismatch drops the twin and retries once;</li>
 *   <li>{@code EXCHANGE TABLES};</li>
 *   <li>compare the counts again, exchanging back on a mismatch;</li>
 *   <li>drop the twin, which then holds the old plain table.</li>
 * </ol>
 * A replicated table still deduplicating inserts receives {@code MODIFY SETTING} instead, which is metadata only. A
 * later start finds an interrupted conversion's leftovers by their prefix and resumes or removes them.
 *
 * <p>Before any DDL it refuses when another replica is registered in the database, active or not, and a plain table
 * exists, unless every other replica is reachable and holds no rows of it: a plain table keeps one copy per replica,
 * so the copies have diverged and only an operator can choose. See {@code openspec/specs/clickhouse-replication/spec.md}
 * "Plain tables in the database are converted to replicated tables at startup".
 */
@Slf4j
@Component
public class ReplicatedTableConversion {

    /** The reserved prefix of a conversion twin; distinct from the relayout's {@code _relayout_}. */
    public static final String PREFIX = "_adas_replicate_";

    private static final String TABLES = "SELECT name, engine, engine_full, create_table_query FROM system.tables "
            + "WHERE database = {db:String} AND NOT is_temporary ORDER BY name";

    /** What one table looks like in {@code system.tables}. */
    record Table(String name, String engine, String engineFull, String createTableQuery) {

        boolean plain() {
            return ReplicatedDdl.isMergeTreeFamily(engine) && !ReplicatedDdl.isReplicated(engine);
        }

        boolean replicated() {
            return ReplicatedDdl.isReplicated(engine);
        }
    }

    private final ClickHouseWriterClient writer;
    private final ClickHouseProperties connection;
    private final ClickHouseDdlProperties ddl;
    private final ClickHouseTopology topology;
    private final Map<String, String> givenUp = new LinkedHashMap<>();

    public ReplicatedTableConversion(ClickHouseWriterClient writer, ClickHouseProperties connection,
            ClickHouseDdlProperties ddl, ClickHouseTopology topology) {
        this.writer = writer;
        this.connection = connection;
        this.ddl = ddl;
        this.topology = topology;
    }

    /** Why each table the last run gave up on stayed plain; read by the table check for its message. */
    public Map<String, String> givenUp() {
        return Map.copyOf(givenUp);
    }

    /**
     * Converts every plain table and switches deduplication off on every replicated one.
     *
     * @throws IllegalStateException when another registered replica may hold rows of a plain table; no DDL was issued
     */
    public void convert() {
        givenUp.clear();
        final Map<String, Table> tables = tables();
        final List<Table> plain = tables.values().stream()
                .filter(Table::plain)
                .filter(table -> !innerTable(table.name()))
                .filter(table -> !table.name().startsWith(PREFIX))
                .toList();
        refuseDivergedCopies(plain);
        resumeLeftovers(tables);
        for (final Table table : tables().values()) {
            if (table.name().startsWith(PREFIX)) {
                continue;
            }
            if (table.plain() && innerTable(table.name())) {
                log.warn("Leaving materialized view storage table '{}' plain: views are not converted", table.name());
            } else if (table.plain()) {
                convertWithRetry(table);
            } else if (table.replicated() && !ReplicatedDdl.disablesDeduplication(table.engineFull())) {
                log.info("Switching insert deduplication off on replicated table '{}'", table.name());
                ddl("ALTER TABLE " + quote(table.name()) + " MODIFY SETTING " + ReplicatedDdl.NO_INSERT_DEDUPLICATION);
            }
        }
    }

    private void refuseDivergedCopies(List<Table> plain) {
        if (plain.isEmpty()) {
            return;
        }
        final List<ClickHouseTopology.Registered> others = topology.registered().stream()
                .filter(replica -> !replica.local())
                .toList();
        if (others.isEmpty()) {
            return;
        }
        final List<String> problems = new ArrayList<>();
        for (final ClickHouseTopology.Registered replica : others) {
            try (ReplicaClients clients = topology.oneOff(replica.host())) {
                for (final Table table : plain) {
                    final Optional<Long> rows = countOn(clients.write(), table.name());
                    if (rows.isEmpty()) {
                        problems.add("'" + table.name() + "' on replica '" + replica.name() + "' ("
                                + (replica.active() ? "active" : "inactive") + ", its copy cannot be inspected)");
                    } else if (rows.get() > 0) {
                        problems.add("'" + table.name() + "' on replica '" + replica.name() + "' (holds " + rows.get()
                                + " rows of its own)");
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to convert plain tables to replicated tables: database '"
                    + connection.sessionDatabase() + "' has other registered replicas whose plain copies may hold rows: "
                    + String.join(", ", problems) + ". A plain table keeps a separate copy on each replica, so the copies "
                    + "have diverged and only an operator can decide which is right; drop a replica gone for good with "
                    + "SYSTEM DROP DATABASE REPLICA, or empty its copies, then restart. No DDL was issued "
                    + "(see docs/clickhouse-replication.md)");
        }
    }

    private Optional<Long> countOn(Client client, String table) {
        try {
            final List<GenericRecord> rows = client.queryAll("SELECT count() AS n FROM " + quote(table),
                    new QuerySettings().serverSetting("final", "0"));
            return Optional.of(rows.isEmpty() ? 0L : rows.getFirst().getLong("n"));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * Resumes or removes an interrupted conversion. A twin beside a plain original was cut short before its exchange:
     * the original still serves, so the twin is dropped and the table converts afresh. A plain twin beside a
     * replicated original was cut short after its exchange: the converted table holding at least the old copy's rows
     * finishes (drops the old copy), fewer exchanges back. Nothing writes in between: the table check refuses to serve
     * while a twin exists.
     */
    private void resumeLeftovers(Map<String, Table> tables) {
        for (final Table twin : tables.values()) {
            if (!twin.name().startsWith(PREFIX)) {
                continue;
            }
            final String name = twin.name().substring(PREFIX.length());
            final Table original = tables.get(name);
            if (original == null) {
                log.warn("Conversion leftover '{}' has no table '{}' beside it; restoring it under its own name",
                        twin.name(), name);
                ddl("RENAME TABLE " + quote(twin.name()) + " TO " + quote(name));
            } else if (original.plain()) {
                log.info("Removing the twin of an interrupted conversion of '{}' before its exchange", name);
                ddl("DROP TABLE " + quote(twin.name()));
            } else if (twin.plain() && count(name) >= count(twin.name())) {
                // The table check refuses to serve while a twin exists, so the converted table has taken no writes
                // since the exchange; at least as many rows as the old copy means nothing was lost.
                log.info("Finishing an interrupted conversion of '{}' after its exchange", name);
                ddl("DROP TABLE " + quote(twin.name()));
            } else if (twin.plain()) {
                // Fewer rows (lost in the copy, or collapsed by a merge of the converted table): the old copy is
                // restored and the table converts afresh below.
                log.warn("An interrupted conversion of '{}' left fewer rows in the converted table; exchanging back", name);
                ddl("EXCHANGE TABLES " + quote(name) + " AND " + quote(twin.name()));
                ddl("DROP TABLE " + quote(twin.name()));
            } else {
                log.warn("Conversion leftover '{}' is replicated beside a replicated '{}'; dropping the leftover",
                        twin.name(), name);
                ddl("DROP TABLE " + quote(twin.name()));
            }
        }
    }

    private void convertWithRetry(Table table) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                if (convertOnce(table)) {
                    return;
                }
                log.warn("Converting '{}': the twin's row count differs from the table's (attempt {} of 2)", table.name(),
                        attempt);
            } catch (RuntimeException ex) {
                log.error("Converting '{}' failed; it stays plain: {}", table.name(), ex.getMessage(), ex);
                givenUp.put(table.name(), "the conversion failed: " + ex.getMessage());
                return;
            }
        }
        givenUp.put(table.name(), "the converted copy's row count differed from the table's twice");
    }

    /** One attempt; false when the pre-exchange counts differ, which leaves the original in service. */
    private boolean convertOnce(Table table) {
        final String name = table.name();
        final String twin = PREFIX + name;
        final long started = System.nanoTime();
        log.info("Converting plain table '{}' ({}) to its replicated form", name, table.engine());
        ddl("DROP TABLE IF EXISTS " + quote(twin));
        try {
            ddl(ReplicatedDdl.toReplicated(ReplicatedDdl.renamed(table.createTableQuery(), connection.sessionDatabase(), twin)));
            ddl("ALTER TABLE " + quote(twin) + " ATTACH PARTITION ALL FROM " + quote(name));
            final long before = count(name);
            if (count(twin) != before) {
                ddl("DROP TABLE " + quote(twin));
                return false;
            }
        } catch (RuntimeException ex) {
            // Before the exchange the original still serves: remove the twin so nothing is left over.
            dropQuietly(twin);
            throw ex;
        }
        ddl("EXCHANGE TABLES " + quote(name) + " AND " + quote(twin));
        final long converted = count(name);
        final long original = count(twin);
        if (converted != original) {
            log.error("Converting '{}': the row counts differ after the exchange ({} against {}); exchanging back", name,
                    converted, original);
            ddl("EXCHANGE TABLES " + quote(name) + " AND " + quote(twin));
            ddl("DROP TABLE " + quote(twin));
            throw new IllegalStateException("the row counts differed after the exchange (" + converted + " against "
                    + original + "); was a previous version still writing?");
        }
        ddl("DROP TABLE " + quote(twin));
        log.info("Converted '{}' to its replicated form: {} rows in {} ms", name, converted,
                (System.nanoTime() - started) / 1_000_000);
        return true;
    }

    /** A materialized view's implicit storage table ({@code .inner.<view>} / {@code .inner_id.<uuid>}). */
    private static boolean innerTable(String name) {
        return name.startsWith(".inner");
    }

    private Map<String, Table> tables() {
        final Map<String, Table> tables = new LinkedHashMap<>();
        for (final GenericRecord row : writer.primaryClient().queryAll(TABLES, Map.of("db", connection.sessionDatabase()))) {
            final Table table = new Table(row.getString("name"), row.getString("engine"), row.getString("engine_full"),
                    row.getString("create_table_query"));
            tables.put(table.name(), table);
        }
        return tables;
    }

    private long count(String table) {
        final List<GenericRecord> rows = writer.primaryClient().queryAll("SELECT count() AS n FROM " + quote(table),
                new QuerySettings().serverSetting("final", "0"));
        return rows.isEmpty() ? 0L : rows.getFirst().getLong("n");
    }

    private void dropQuietly(String table) {
        try {
            ddl("DROP TABLE IF EXISTS " + quote(table));
        } catch (RuntimeException ex) {
            log.warn("Could not remove conversion twin '{}'; the next start removes it: {}", table, ex.getMessage());
        }
    }

    private void ddl(String statement) {
        writer.primaryClient().queryAll(statement, DdlSettings.apply(new QuerySettings(), ddl.taskTimeout()));
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("\\", "\\\\").replace("`", "\\`") + "`";
    }
}
