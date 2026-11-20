package com.epam.aidial.analytics.service.table;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Spring Data JPA repository for {@link TableDefinitionEntity}, keyed by table {@code name}. */
@Repository
public interface TableDefinitionRepository extends JpaRepository<TableDefinitionEntity, String>, TableDefinitionLocking {

    /** Enrichments that reference {@code sourceTable} — used to flatten schemas and block source deletes. */
    List<TableDefinitionEntity> findBySourceTable(String sourceTable);

    /**
     * The name and type of each of {@code names} that exists, as a scalar projection: it never enters the persistence
     * context, so a writer may ask it before {@link #findForUpdate} makes the first load of each row.
     */
    @Query("SELECT d.name AS name, d.type AS type FROM TableDefinitionEntity d WHERE d.name IN :names")
    List<NameAndType> findTypes(@Param("names") Collection<String> names);

    /** A table's name and type; see {@link #findTypes}. */
    interface NameAndType {
        String getName();

        TableType getType();
    }

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
