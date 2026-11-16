package com.epam.aidial.analytics.clickhouse.replication;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * The PostgreSQL advisory lock one instance holds over the table conversion, the ClickHouse migrations and the table
 * check, so instances starting together — as a {@code Recreate} deploy starts them — run them one at a time: Flyway
 * takes no lock of its own on ClickHouse. Session-level, on a connection held for the whole phase, so a crashed
 * instance's lock goes with its connection. The two-key form keeps it in its own key space, apart from the relayout's
 * single-key locks.
 *
 * <p>The session could also end under a live instance: an idle-session timeout, a pooler or a dropped connection
 * would release the lock without telling anyone. While the phase runs, the lock's connection is therefore kept busy
 * with a check every {@link #HEARTBEAT} that the lock is still held, and the phase calls {@link #ensureHeld()} before
 * each step that issues DDL, which fails the phase once the lock is lost rather than run that DDL beside another
 * instance.
 */
@Slf4j
@Component
public class StoragePhaseLock {

    /** The lock's key space: the first key of the two-key form. */
    static final int NAMESPACE = 0x41444153;

    /** The lock within it. */
    static final int STORAGE_PHASE = 1;

    /** How often the lock's connection checks the lock is still held while the phase runs. */
    static final Duration HEARTBEAT = Duration.ofSeconds(10);

    /** Whether this session holds the two-key advisory lock ({@code objsubid = 2} marks the two-key form). */
    private static final String HELD = "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' "
            + "AND pid = pg_backend_pid() AND classid = ?::oid AND objid = ?::oid AND objsubid = 2 AND granted)";

    private final DataSource dataSource;
    private volatile Held current;

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
            final Held held = new Held(connection);
            final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("clickhouse-storage-lock").factory());
            heartbeat.scheduleWithFixedDelay(held::check, HEARTBEAT.toMillis(), HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
            current = held;
            try {
                body.run();
            } finally {
                current = null;
                heartbeat.shutdownNow();
                awaitQuietly(heartbeat);
                unlock(connection);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not take the ClickHouse storage-phase lock", ex);
        }
    }

    /**
     * Fails when the lock {@link #run} holds has been lost, checking it now; does nothing outside {@link #run}.
     *
     * @throws IllegalStateException when the lock's session ended or no longer holds the lock
     */
    public void ensureHeld() {
        final Held held = current;
        if (held != null) {
            held.ensure();
        }
    }

    /** The lock as one {@link #run} holds it; its connection is used by one thread at a time. */
    private static final class Held {

        private final Connection connection;
        private String lost;

        Held(Connection connection) {
            this.connection = connection;
        }

        synchronized void check() {
            if (lost != null) {
                return;
            }
            try {
                if (!call(connection, HELD)) {
                    lost = "the session no longer holds it";
                }
            } catch (SQLException | RuntimeException ex) {
                lost = "its session failed: " + ex.getMessage();
            }
            if (lost != null) {
                log.error("The ClickHouse storage-phase lock was lost ({}); the phase stops before its next step", lost);
            }
        }

        synchronized void ensure() {
            check();
            if (lost != null) {
                throw new IllegalStateException("The ClickHouse storage-phase lock was lost (" + lost + "); another instance "
                        + "may be converting or migrating the storage, so this one stops. A restart takes the lock again");
            }
        }
    }

    private static void awaitQuietly(ScheduledExecutorService executor) {
        try {
            executor.awaitTermination(HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
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
