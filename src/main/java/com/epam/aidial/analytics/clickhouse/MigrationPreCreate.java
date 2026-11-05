package com.epam.aidial.analytics.clickhouse;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The Flyway {@code beforeEachMigrate} callback that makes a pending migration's {@code CREATE TABLE IF NOT EXISTS}
 * provision a replicated table without editing the migration file, whose checksum is frozen once applied.
 *
 * <p>Immediately before Flyway applies a migration, every {@code CREATE TABLE IF NOT EXISTS <t> … ENGINE = <X>} in its
 * script is issued here first in its replicated form ({@link ReplicatedDdl#toReplicated}). The migration's own
 * statement then finds {@code <t>} and does nothing. A migration that already declares a replicated engine, as every
 * migration written after this hook does, is created here exactly as written, which is the same no-op. See
 * {@code openspec/specs/data-sync/spec.md} "Target tables are provisioned by versioned ClickHouse migrations".
 */
@Slf4j
final class MigrationPreCreate implements Callback {

    private final String location;

    /**
     * A callback reading the migrations from {@code location}.
     *
     * @param location the classpath folder the migrations are read from, without the {@code classpath:} prefix
     */
    MigrationPreCreate(String location) {
        this.location = location;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        final MigrationInfo migration = context.getMigrationInfo();
        if (migration == null || migration.getScript() == null) {
            return;
        }
        final List<String> creates = preCreates(read(migration.getScript()));
        for (final String ddl : creates) {
            log.info("Pre-creating the replicated table of migration {} ({})", migration.getVersion(), migration.getScript());
            try (Statement statement = context.getConnection().createStatement()) {
                statement.execute(ddl);
            } catch (SQLException ex) {
                throw new IllegalStateException("Could not pre-create the replicated table of migration "
                        + migration.getScript(), ex);
            }
        }
    }

    @Override
    public String getCallbackName() {
        return "replicated-pre-create";
    }

    /** The replicated pre-create statements of a migration script: one per {@code CREATE TABLE IF NOT EXISTS}. */
    static List<String> preCreates(String script) {
        final List<String> creates = new ArrayList<>();
        for (final String statement : ReplicatedDdl.stripLineComments(script).split(";")) {
            if (ReplicatedDdl.isCreateIfNotExists(statement)) {
                creates.add(ReplicatedDdl.toReplicated(statement.strip()));
            }
        }
        return creates;
    }

    private String read(String script) {
        final String resource = location + "/" + script;
        try (InputStream in = MigrationPreCreate.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Migration script not found on the classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read migration script " + resource, ex);
        }
    }
}
