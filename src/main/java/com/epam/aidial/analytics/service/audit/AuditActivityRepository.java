package com.epam.aidial.analytics.service.audit;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link AuditActivityEntity}.
 *
 * <p>ADAS's first {@link JpaSpecificationExecutor}: the feed's filters are composed at runtime from an
 * arbitrary list of terms, which is exactly what derived query methods cannot express. It ships with
 * {@code spring-boot-starter-data-jpa} and needs no new dependency.
 *
 * <p><strong>Read-only structurally, not merely by intent.</strong> It extends the bare
 * {@link org.springframework.data.repository.Repository} marker and declares the two reads it needs,
 * rather than {@code JpaRepository} — which would publish {@code save}, {@code delete},
 * {@code deleteById}, {@code deleteAll} and {@code deleteAllInBatch} on the injected bean. Under a
 * capability whose whole premise is that no code and no endpoint may remove an audit entry, leaving
 * those methods reachable makes the premise a comment: a cleanup helper calling {@code deleteAll()}
 * would compile, read as ordinary test hygiene, and erase the trail. Activities are written solely by
 * the revision cascade, so nothing here needs a write method.
 */
@Repository
public interface AuditActivityRepository
        extends org.springframework.data.repository.Repository<AuditActivityEntity, UUID>,
        JpaSpecificationExecutor<AuditActivityEntity> {

    /** One activity by id, or empty. */
    Optional<AuditActivityEntity> findById(UUID activityId);

    /** Every activity; used by tests that assert what a catalog operation recorded. */
    List<AuditActivityEntity> findAll();
}
