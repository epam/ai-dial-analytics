package com.epam.aidial.analytics.clickhouse.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The PostgreSQL advisory lock one instance holds over the table conversion, the ClickHouse migrations and the table
 * check, so instances starting together — as a {@code Recreate} deploy starts them — run them one at a time: Flyway
 * takes no lock of its own on ClickHouse. Session-level, on a connection held for the whole phase, so a crashed
 * instance's lock goes with its connection. The two-key form keeps it in its own key space, apart from the relayout's
 * single-key locks.
 */
@Slf4j
@Component
public class StoragePhaseLock {

    /** The lock's key space: the first key of the two-key form. */
    static final int NAMESPACE = 0x41444153;

    /** The lock within it. */
    static final int STORAGE_PHASE = 1;

    private final DataSource dataSource;

    public StoragePhaseLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Runs {@code body} holding the lock, waiting for another instance that holds it. */
    public void run(Runnable body) {
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection)) {
                log.info("Another instance is converting or migrating the ClickHouse storage; waiting for it");
                lock(connection);
            }
            try {
                body.run();
            } finally {
                unlock(connection);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not take the ClickHouse storage-phase lock", ex);
        }
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        return call(connection, "SELECT pg_try_advisory_lock(?, ?)");
    }

    private static void lock(Connection connection) throws SQLException {
        call(connection, "SELECT pg_advisory_lock(?, ?) IS NOT NULL");
    }

    /**
     * Releases the lock. A session-level advisory lock outlives {@code close()} on a pooled connection, which only
     * returns it to the pool, so when the unlock fails the physical connection is aborted: closing the session is what
     * releases the lock for the other instances.
     */
    private static void unlock(Connection connection) {
        try {
            if (call(connection, "SELECT pg_advisory_unlock(?, ?)")) {
                return;
            }
            log.warn("The ClickHouse storage-phase lock was not held at release; aborting its connection");
        } catch (SQLException ex) {
            log.warn("Could not release the ClickHouse storage-phase lock; aborting its connection: {}", ex.getMessage());
        }
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException ex) {
            log.warn("Could not abort the ClickHouse storage-phase lock's connection: {}", ex.getMessage());
        }
    }

    private static boolean call(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, NAMESPACE);
            statement.setInt(2, STORAGE_PHASE);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
