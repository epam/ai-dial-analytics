package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableBusyException;

import java.util.Optional;

/** The definition row lock every writer of a {@code table_definition} row takes first; see {@link #findForUpdate}. */
public interface TableDefinitionLocking {

    /**
     * The definition under a {@code SELECT … FOR UPDATE} row lock, which every writer of a definition takes as the
     * first load of that row in its transaction, so no writer overwrites an access change with stale state. A
     * locking query returns an entity already in the persistence context without re-reading it, so a writer must
     * not load the row before calling this.
     *
     * <p>The wait for a lock another transaction holds is bounded by {@code tables.lock-timeout} only here: any other
     * way of locking the row, such as {@code EntityManager.refresh(entity, PESSIMISTIC_WRITE)}, waits without limit.
     *
     * @throws TableBusyException when the lock is not granted within that time (409 table_busy)
     */
    Optional<TableDefinitionEntity> findForUpdate(String name);
}
