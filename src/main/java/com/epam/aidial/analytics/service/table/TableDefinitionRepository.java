package com.epam.aidial.analytics.service.table;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Spring Data JPA repository for {@link TableDefinitionEntity}, keyed by table {@code name}. */
@Repository
public interface TableDefinitionRepository extends JpaRepository<TableDefinitionEntity, String> {

    /**
     * The definition under a {@code SELECT … FOR UPDATE} row lock, which every writer of a definition takes as the
     * first load of that row in its transaction, so no writer overwrites an access change with stale state. A
     * locking query returns an entity already in the persistence context without re-reading it; a writer that
     * cannot avoid an earlier load locks with {@code EntityManager.refresh(entity, PESSIMISTIC_WRITE)} instead.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM TableDefinitionEntity d WHERE d.name = :name")
    Optional<TableDefinitionEntity> findForUpdate(@Param("name") String name);

    /** Enrichments that reference {@code sourceTable} — used to flatten schemas and block source deletes. */
    List<TableDefinitionEntity> findBySourceTable(String sourceTable);

    /** Definitions of a given type and status (e.g. materialized sources for query discovery). */
    List<TableDefinitionEntity> findByTypeAndStatus(TableType type, TableStatus status);

    /**
     * The row-policy documents of every table that carries at least one policy, as a scalar projection.
     * Asked on every user read, so it reads two columns of the few rows that have policies rather than loading
     * every definition — and, being a projection, it cannot dirty a managed entity inside a write transaction.
     */
    @Query(value = "SELECT name AS name, row_policies::text AS policies, access::text AS access FROM table_definition "
            + "WHERE row_policies <> '{}'::jsonb", nativeQuery = true)
    List<StoredRowPolicies> findStoredRowPolicies();

    /**
     * One table's stored row-policy document and its grant lists; see {@link #findStoredRowPolicies}. The grant
     * lists travel with the policies because whether a role without a policy lifts the confinement depends on
     * whether that role may read the table.
     */
    interface StoredRowPolicies {
        String getName();

        String getPolicies();

        String getAccess();
    }
}
