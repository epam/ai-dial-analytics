package com.epam.aidial.analytics.service.table;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Spring Data JPA repository for {@link ColumnMappingEntity} rows of a catalog table. */
@Repository
public interface ColumnMappingRepository extends JpaRepository<ColumnMappingEntity, ColumnMappingId> {

    /** The exposed columns of {@code tableName}, ordered by their declared position. */
    List<ColumnMappingEntity> findByTableNameOrderByOrdinal(String tableName);

    /**
     * The exposed columns of several tables at once, ordered by table then declared position.
     *
     * <p>Exists for the enrich <b>sync feed</b>, which composes a response schema per pipeline and must
     * stay at a constant number of queries however many pipelines it returns — the property the batched
     * resolve is built around. Calling the single-table finder per pipeline would quietly make the
     * runner's feed cost one query per enabled pipeline.
     */
    List<ColumnMappingEntity> findByTableNameInOrderByTableNameAscOrdinalAsc(Collection<String> tableNames);

    /**
     * Exposed-column counts per table, in one aggregate query (tables with no columns have no row), with
     * the sensitive ones counted separately so a caller without the clearance is told the count it can see.
     */
    @Query("select c.tableName as tableName, count(c) as columnCount, "
            + "sum(case when c.sensitive = true then 1 else 0 end) as sensitiveCount "
            + "from ColumnMappingEntity c group by c.tableName")
    List<TableColumnCount> countByTable();

    /** Removes all column mappings for {@code tableName} (used when deleting a table definition). */
    void deleteByTableName(String tableName);

    /** Projection for {@link #countByTable()}: one table's exposed-column count. */
    interface TableColumnCount {
        String getTableName();

        long getColumnCount();

        long getSensitiveCount();
    }
}
