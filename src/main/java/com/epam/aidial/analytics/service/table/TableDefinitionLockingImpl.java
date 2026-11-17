package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableBusyException;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.springframework.beans.factory.annotation.Value;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

/**
 * {@link TableDefinitionLocking} on PostgreSQL. The JPA lock-timeout hint is not a bounded wait there (only {@code 0},
 * {@code NOWAIT}, is rendered), so the wait is bounded by the session's {@code lock_timeout}, set for the locking
 * statement alone and restored after it.
 */
public class TableDefinitionLockingImpl implements TableDefinitionLocking {

    /** PostgreSQL's {@code lock_not_available}, raised when {@code lock_timeout} expires. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${tables.lock-timeout:10s}")
    private Duration lockTimeout;

    /** Refuses at startup a timeout that renders as {@code 0ms} or less, which would remove the bound or fail every write. */
    @PostConstruct
    void validateLockTimeout() {
        if (lockTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("tables.lock-timeout (" + lockTimeout + ") must be at least 1ms; PostgreSQL takes "
                    + "lock_timeout=0 as no limit, so a shorter value would switch the bound off rather than tighten it");
        }
    }

    @Override
    public Optional<TableDefinitionEntity> findForUpdate(String name) {
        final Object previous = entityManager.createNativeQuery("SELECT current_setting('lock_timeout')").getSingleResult();
        setLockTimeout(lockTimeout.toMillis() + "ms");
        final Optional<TableDefinitionEntity> locked;
        try {
            locked = entityManager.createQuery("SELECT d FROM TableDefinitionEntity d WHERE d.name = :name", TableDefinitionEntity.class)
                    .setParameter("name", name)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultList().stream().findFirst();
        } catch (PersistenceException ex) {
            if (lockNotAvailable(ex)) {
                throw new TableBusyException("another change to table '" + name + "' is in progress; retry the request");
            }
            throw ex;
        }
        setLockTimeout(String.valueOf(previous));
        return locked;
    }

    private void setLockTimeout(String value) {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :value, true)").setParameter("value", value).getSingleResult();
    }

    private static boolean lockNotAvailable(Throwable ex) {
        for (Throwable cause = ex; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
