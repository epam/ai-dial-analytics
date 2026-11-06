package com.epam.aidial.analytics.clickhouse.replication;

import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.clickhouse.ClickHouseFailures;
import com.epam.aidial.analytics.clickhouse.ClickHouseWriterClient;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The first step of startup: the session database must use the {@code Replicated} engine. Runs before the PostgreSQL
 * migrations ({@code StorageStartupConfiguration}'s {@code FlywayMigrationStrategy}), which can seed catalog rows, and
 * before any ClickHouse DDL, so a refusal leaves ClickHouse and the catalog untouched.
 *
 * <p>When ClickHouse cannot be reached the engine cannot be checked. With migrations enabled that fails startup, as
 * the migrations would; with migrations disabled the service starts unverified, as it always has without ClickHouse,
 * and logs a WARN. See {@code openspec/specs/clickhouse-replication/spec.md} "The service runs only on a Replicated
 * database".
 */
@Slf4j
@Component
public class DatabaseEngineCheck {

    /** What the check found. */
    public enum Outcome {
        /** The database is {@code Replicated}. */
        REPLICATED,
        /** ClickHouse could not be reached, with migrations disabled. */
        UNREACHABLE
    }

    private final ClickHouseWriterClient writer;
    private final ClickHouseProperties connection;
    private final boolean migrationsEnabled;
    private volatile Outcome outcome;

    public DatabaseEngineCheck(ClickHouseWriterClient writer, ClickHouseProperties connection,
            @Value("${clickhouse.migrations.enabled:true}") boolean migrationsEnabled) {
        this.writer = writer;
        this.connection = connection;
        this.migrationsEnabled = migrationsEnabled;
    }

    /**
     * Checks the engine and remembers a verified outcome; an unreachable server is asked again on the next call.
     *
     * @throws IllegalStateException when the database is not {@code Replicated}, does not exist, or cannot be reached
     *                               with migrations enabled
     */
    public synchronized Outcome check() {
        if (outcome != null) {
            return outcome;
        }
        final String database = connection.sessionDatabase();
        final List<GenericRecord> rows;
        try {
            rows = writer.primaryClient().queryAll("SELECT engine FROM system.databases WHERE name = {db:String}",
                    Map.of("db", database));
        } catch (RuntimeException ex) {
            if (ClickHouseFailures.isUnavailable(ex) && !migrationsEnabled) {
                log.warn("ClickHouse at {}:{} is unreachable, so the engine of database '{}' could not be checked; "
                        + "starting unverified because clickhouse.migrations.enabled is false", connection.host(),
                        connection.port(), database);
                // Not remembered: ClickHouse may still be starting, and the storage phase asks again later.
                return Outcome.UNREACHABLE;
            }
            throw new IllegalStateException("Could not check the engine of ClickHouse database '" + database + "'", ex);
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("ClickHouse database '" + database + "' does not exist. Create it with the "
                    + "Replicated engine; see docs/clickhouse-replication.md");
        }
        final String engine = rows.getFirst().getString("engine");
        if (!"Replicated".equals(engine)) {
            throw new IllegalStateException("ClickHouse database '" + database + "' uses the " + engine + " engine, but "
                    + "the service requires a Replicated database. The service does not change a database's engine "
                    + "itself: follow the manual engine-change procedure in docs/clickhouse-replication.md, then "
                    + "redeploy. Nothing was changed in ClickHouse or in the catalog");
        }
        outcome = Outcome.REPLICATED;
        return outcome;
    }
}
