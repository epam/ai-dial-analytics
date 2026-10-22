package com.epam.aidial.analytics.service.query.saved;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link SavedQueryEntity}.
 *
 * <p>Visibility is expressed as part of the <strong>lookup</strong>, never as a check after fetching.
 * A saved query the caller may not see and one that does not exist therefore take the identical code
 * path and both surface as {@code 404}: there is no branch that could accidentally answer {@code 403}
 * and confirm that some other person's saved query exists under that id, and no refactor can introduce
 * one without deleting the predicate outright.
 */
@Repository
public interface SavedQueryRepository extends JpaRepository<SavedQueryEntity, String> {

    /**
     * The saved query at {@code id} if {@code author} may see it: any {@code COMMON} row, or a
     * {@code PERSONAL} row that {@code author} owns. Empty for both "not visible" and "does not exist".
     */
    default Optional<SavedQueryEntity> findVisible(String id, String author) {
        return findVisibleInScope(id, author, SavedQueryScope.COMMON);
    }

    /** This caller's saved queries at {@code scope}, most recently updated first. */
    List<SavedQueryEntity> findByScopeAndOwnerIdOrderByUpdatedAtDesc(SavedQueryScope scope, String ownerId);

    /**
     * Every saved query at {@code scope}, most recently updated first. Used for the {@code COMMON} list
     * (which has no owner term) and for the {@code none}-mode fail-open list.
     */
    List<SavedQueryEntity> findByScopeOrderByUpdatedAtDesc(SavedQueryScope scope);

    /**
     * Backs {@link #findVisible}. {@code common} is passed rather than written as a JPQL enum literal so
     * the query stays free of fully-qualified class names.
     */
    @Query("SELECT s FROM SavedQueryEntity s WHERE s.id = :id "
            + "AND (s.scope = :common OR s.ownerId = :author)")
    Optional<SavedQueryEntity> findVisibleInScope(
            @Param("id") String id, @Param("author") String author, @Param("common") SavedQueryScope common);
}
