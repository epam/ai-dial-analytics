package com.epam.aidial.analytics.service.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link DashboardEntity}.
 *
 * <p>Visibility is part of the <strong>lookup</strong>, as it is for saved queries: a dashboard the caller
 * may not see and one that does not exist take the same path and both answer {@code 404}. The predicate
 * has no privilege term. Reads are gated on {@code SAVED_QUERY_READ}, and a write passes the
 * any-write-authority check first, so every caller that reaches it may see {@code public} dashboards.
 */
@Repository
public interface DashboardRepository extends JpaRepository<DashboardEntity, String> {

    /**
     * The dashboard at {@code id} if {@code author} may see it: any {@code PUBLIC} one, or a {@code PRIVATE}
     * one {@code author} owns. A null author owns nothing, so it reaches {@code PUBLIC} dashboards only.
     */
    default Optional<DashboardEntity> findVisible(String id, String author) {
        return findVisibleAt(id, author, DashboardVisibility.PUBLIC);
    }

    /** The list-item projection of every dashboard {@code author} may see, most recently updated first. */
    default List<DashboardSummary> findVisibleSummaries(String author) {
        return findVisibleSummariesAt(author, DashboardVisibility.PUBLIC);
    }

    /**
     * Backs {@link #findVisible}. {@code visible} is passed rather than written as a JPQL enum literal so
     * the query stays free of fully-qualified class names.
     */
    @Query("SELECT d FROM DashboardEntity d WHERE d.id = :id AND (d.visibility = :visible OR d.ownerId = :author)")
    Optional<DashboardEntity> findVisibleAt(
            @Param("id") String id, @Param("author") String author, @Param("visible") DashboardVisibility visible);

    @Query("SELECT new com.epam.aidial.analytics.service.dashboard.DashboardSummary("
            + "d.id, d.name, d.description, d.visibility, d.ownerId, d.updatedAt) "
            + "FROM DashboardEntity d WHERE d.visibility = :visible OR d.ownerId = :author "
            + "ORDER BY d.updatedAt DESC, d.id")
    List<DashboardSummary> findVisibleSummariesAt(
            @Param("author") String author, @Param("visible") DashboardVisibility visible);

    /** Every dashboard's list item, for {@code none} mode, where no owner filter applies. */
    @Query("SELECT new com.epam.aidial.analytics.service.dashboard.DashboardSummary("
            + "d.id, d.name, d.description, d.visibility, d.ownerId, d.updatedAt) "
            + "FROM DashboardEntity d ORDER BY d.updatedAt DESC, d.id")
    List<DashboardSummary> findAllSummaries();
}
