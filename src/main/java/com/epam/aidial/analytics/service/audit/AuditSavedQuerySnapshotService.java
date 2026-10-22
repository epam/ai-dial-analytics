package com.epam.aidial.analytics.service.audit;

import com.epam.aidial.analytics.exception.RevisionNotFoundException;
import com.epam.aidial.analytics.service.query.saved.SavedQueryEntity;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a saved query as it stood at a past revision, returning the stored {@link SavedQueryEntity}
 * itself — the controller maps it through the existing {@code SavedQueryMapper.toDto}, which already
 * converts directly from the raw entity, so no new mapper method is needed here.
 */
@Service
@RequiredArgsConstructor
public class AuditSavedQuerySnapshotService {

    private final EntityManager entityManager;

    /**
     * The saved query {@code id} as of the end of {@code revision}, or 404 when there is nothing to
     * return — see {@link AuditSnapshotGuards} for why a missing revision and a saved query absent at
     * it are the same answer.
     */
    @Transactional(readOnly = true)
    public SavedQueryEntity snapshot(String id, int revision) {
        final AuditReader reader = AuditReaderFactory.get(entityManager);
        AuditSnapshotGuards.requireExistingRevision(reader, revision);
        AuditSnapshotGuards.requireCreationWithinTheTrail(
                reader, SavedQueryEntity.class, id, revision, "saved query '" + id + "'");
        final SavedQueryEntity entity = reader.find(SavedQueryEntity.class, id, revision);
        if (entity == null) {
            throw new RevisionNotFoundException("saved query '" + id + "' did not exist at revision " + revision);
        }
        return entity;
    }
}
