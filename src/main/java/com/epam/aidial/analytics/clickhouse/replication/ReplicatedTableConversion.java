package com.epam.aidial.analytics.clickhouse.replication;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseFailures;
import com.epam.aidial.analytics.clickhouse.ClickHouseIdentifiers;
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
 *   <li>compare physical row counts, without {@code FINAL} and with merges stopped on both tables; a mismatch drops
 *       the twin and retries once;</li>
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
    private static final int RESTART_ATTEMPTS = 3;

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
        convert(() -> { });
    }

    /**
     * As {@link #convert()}, running {@code checkpoint} before the first DDL and before each table's: the storage-phase
     * lock's {@link StoragePhaseLock#ensureHeld}, which stops the conversion once the lock is lost.
     */
    public void convert(Runnable checkpoint) {
        givenUp.clear();
        final Map<String, Table> tables = tables();
        final List<Table> plain = tables.values().stream()
                .filter(Table::plain)
                .filter(table -> !innerTable(table.name()))
                .filter(table -> !table.name().startsWith(PREFIX))
                .toList();
        refuseDivergedCopies(plain);
        checkpoint.run();
        resumeLeftovers(tables);
        for (final Table table : tables().values()) {
            if (table.name().startsWith(PREFIX)) {
                continue;
            }
            checkpoint.run();
            if (table.plain() && innerTable(table.name())) {
                log.warn("Leaving materialized view storage table '{}' plain: views are not converted", table.name());
            } else if (table.plain()) {
                convertWithRetry(table);
            } else if (table.replicated() && !ReplicatedDdl.disablesDeduplication(table.engineFull())) {
                log.info("Switching insert deduplication off on replicated table '{}'", table.name());
                ddl("ALTER TABLE " + ClickHouseIdentifiers.quote(table.name()) + " MODIFY SETTING " + ReplicatedDdl.NO_INSERT_DEDUPLICATION);
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
            final List<GenericRecord> rows = client.queryAll("SELECT count() AS n FROM " + ClickHouseIdentifiers.quote(table),
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
                ddl("RENAME TABLE " + ClickHouseIdentifiers.quote(twin.name()) + " TO " + ClickHouseIdentifiers.quote(name));
            } else if (original.plain()) {
                log.info("Removing the twin of an interrupted conversion of '{}' before its exchange", name);
                ddl("DROP TABLE " + ClickHouseIdentifiers.quote(twin.name()));
            } else if (twin.plain() && holdsAtLeast(name, twin.name())) {
                // The table check refuses to serve while a twin exists, so the converted table has taken no writes
                // since the exchange; at least as many rows as the old copy means nothing was lost.
                log.info("Finishing an interrupted conversion of '{}' after its exchange", name);
                ddl("DROP TABLE " + ClickHouseIdentifiers.quote(twin.name()));
            } else if (twin.plain()) {
                // Fewer rows (lost in the copy, or collapsed by a merge of the converted table): the old copy is
                // restored and the table converts afresh below.
                log.warn("An interrupted conversion of '{}' left fewer rows in the converted table; exchanging back", name);
                ddl("EXCHANGE TABLES " + ClickHouseIdentifiers.quote(name) + " AND " + ClickHouseIdentifiers.quote(twin.name()));
                ddl("DROP TABLE " + ClickHouseIdentifiers.quote(twin.name()));
            } else {
                log.warn("Conversion leftover '{}' is replicated beside a replicated '{}'; dropping the leftover",
                        twin.name(), name);
                ddl("DROP TABLE " + ClickHouseIdentifiers.quote(twin.name()));
            }
            // An interrupted conversion may have left merges stopped on the table that stays (until a server restart).
            restartMerges(name);
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
                log.error("Converting '{}' failed: {}", table.name(), ex.getMessage(), ex);
                givenUp.put(table.name(), "the conversion failed: " + ex.getMessage());
                return;
            }
        }
        givenUp.put(table.name(), "the converted copy's row count differed from the table's twice");
    }

    /**
     * One attempt; false when the pre-exchange counts differ, which leaves the original in service. Merges are stopped
     * on the table and its twin from before the attach until both counts have been compared, so a merge collapsing
     * rows or a TTL deleting them cannot move one count between the two reads. When merges cannot be restarted on the
     * table that stays, the attempt fails before the twin is dropped, so the next start restarts them on resume.
     */
    private boolean convertOnce(Table table) {
        final String name = table.name();
        final String twin = PREFIX + name;
        final long started = System.nanoTime();
        log.info("Converting plain table '{}' ({}) to its replicated form", name, table.engine());
        ddl("DROP TABLE IF EXISTS " + ClickHouseIdentifiers.quote(twin));
        final long converted;
        try {
            ddl(ReplicatedDdl.toReplicated(ReplicatedDdl.renamed(table.createTableQuery(), connection.sessionDatabase(), twin)));
        } catch (RuntimeException ex) {
            dropQuietly(twin);
            throw ex;
        }
        final boolean stopped = stopMerges(name, twin);
        boolean restarted = true;
        try {
            try {
                ddl("ALTER TABLE " + ClickHouseIdentifiers.quote(twin) + " ATTACH PARTITION ALL FROM " + ClickHouseIdentifiers.quote(name));
                final long before = count(name);
                if (count(twin) != before) {
                    ddl("DROP TABLE " + ClickHouseIdentifiers.quote(twin));
                    return false;
                }
            } catch (RuntimeException ex) {
                // Before the exchange the original still serves: remove the twin so nothing is left over.
                dropQuietly(twin);
                throw ex;
            }
            ddl("EXCHANGE TABLES " + ClickHouseIdentifiers.quote(name) + " AND " + ClickHouseIdentifiers.quote(twin));
            converted = count(name);
            final long original = count(twin);
            if (converted < original) {
                // Rows went missing in the copy. The old copy is restored, and the converted one is kept beside it
                // rather than dropped: anything written to it since the exchange is still there, and the table check
                // refuses to serve while it exists, so an operator can reconcile it (or the next start removes it).
                log.error("Converting '{}': the converted table holds fewer rows than the old copy after the exchange ({} "
                        + "against {}); exchanging back and keeping the converted copy as '{}'", name, converted, original,
                        twin);
                ddl("EXCHANGE TABLES " + ClickHouseIdentifiers.quote(name) + " AND " + ClickHouseIdentifiers.quote(twin));
                throw new IllegalStateException("the converted table held fewer rows than the old copy after the exchange ("
                        + converted + " against " + original + "); was a previous version still writing?");
            }
            if (converted > original) {
                // Something wrote to the converted table after the exchange (a previous version still running): it
                // holds every row of the old copy and the new writes, so it is kept, as a resumed conversion would.
                log.warn("Converting '{}': the converted table took {} write(s) after the exchange; was a previous "
                        + "version still writing? Keeping the converted table", name, converted - original);
            }
        } finally {
            if (stopped) {
                restarted = restartMerges(name);
                startMerges(twin);
            }
        }
        if (!restarted) {
            // The twin stays, so the table check keeps refusing to serve and the next start's resume restarts merges.
            throw new IllegalStateException("merges could not be restarted on the converted table; its old copy is kept as '"
                    + twin + "' so the next start restarts them");
        }
        ddl("DROP TABLE " + ClickHouseIdentifiers.quote(twin));
        log.info("Converted '{}' to its replicated form: {} rows in {} ms", name, converted,
                (System.nanoTime() - started) / 1_000_000);
        return true;
    }

    /** Whether {@code converted} holds at least the rows of {@code original}, compared with merges stopped on both. */
    private boolean holdsAtLeast(String converted, String original) {
        final boolean stopped = stopMerges(converted, original);
        try {
            return count(converted) >= count(original);
        } finally {
            if (stopped) {
                startMerges(converted, original);
            }
        }
    }

    /**
     * Stops background merges, TTL deletes included, on {@code tables} on the primary, so their row counts hold still.
     * Best effort: without the writer's {@code SYSTEM MERGES} grant the counts are compared with merges running, and a
     * merge that collapses rows between the two reads shows as a mismatch, which is retried or refuses startup, never
     * accepted. {@code SYSTEM STOP MERGES} is local to the server and lasts until {@link #startMerges} or its restart.
     *
     * @return whether merges were stopped on every table
     */
    private boolean stopMerges(String... tables) {
        int done = 0;
        try {
            for (final String table : tables) {
                ddl("SYSTEM STOP MERGES " + ClickHouseIdentifiers.quote(table));
                done++;
            }
            return true;
        } catch (RuntimeException ex) {
            log.warn("Could not stop merges for the conversion's row-count comparison ({}); comparing with merges running. "
                    + "Grant the writer SYSTEM MERGES ON <database>.* (see docs/clickhouse-server.md)", ex.getMessage());
            for (int i = 0; i < done; i++) {
                startMerges(tables[i]);
            }
            return false;
        }
    }

    /**
     * Restarts merges on a table that stays in service, retrying: merges left stopped last until a server restart, and
     * parts then pile up until inserts are refused. An access-denied failure counts as done, since without the
     * {@code SYSTEM MERGES} grant nothing was stopped.
     *
     * @return false, after an ERROR, when every attempt failed
     */
    private boolean restartMerges(String table) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= RESTART_ATTEMPTS; attempt++) {
            try {
                ddl("SYSTEM START MERGES " + ClickHouseIdentifiers.quote(table));
                return true;
            } catch (RuntimeException ex) {
                if (ClickHouseFailures.isAccessDenied(ex)) {
                    return true;
                }
                last = ex;
            }
        }
        log.error("Could not restart merges on '{}' after {} attempts; parts accumulate until inserts are refused. Run "
                + "SYSTEM START MERGES {} on the primary: {}", table, RESTART_ATTEMPTS, ClickHouseIdentifiers.quote(table),
                last.getMessage(), last);
        return false;
    }

    /**
     * Restarts merges on {@code tables} about to be dropped, or checked again by a caller that restarts the kept one
     * with {@link #restartMerges}; best effort, so failures only log.
     */
    private void startMerges(String... tables) {
        for (final String table : tables) {
            try {
                ddl("SYSTEM START MERGES " + ClickHouseIdentifiers.quote(table));
            } catch (RuntimeException ex) {
                log.debug("Could not restart merges on '{}': {}", table, ex.getMessage());
            }
        }
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
        final List<GenericRecord> rows = writer.primaryClient().queryAll("SELECT count() AS n FROM " + ClickHouseIdentifiers.quote(table),
                new QuerySettings().serverSetting("final", "0"));
        return rows.isEmpty() ? 0L : rows.getFirst().getLong("n");
    }

    private void dropQuietly(String table) {
        try {
            ddl("DROP TABLE IF EXISTS " + ClickHouseIdentifiers.quote(table));
        } catch (RuntimeException ex) {
            log.warn("Could not remove conversion twin '{}'; the next start removes it: {}", table, ex.getMessage());
        }
    }

    private void ddl(String statement) {
        writer.primaryClient().queryAll(statement, DdlSettings.apply(new QuerySettings(), ddl.taskTimeout()));
    }
}
