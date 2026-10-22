package com.epam.aidial.analytics.service.audit;

import com.epam.aidial.analytics.exception.RevisionNotFoundException;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a pipeline of either kind as it stood at a past revision, returning the stored
 * {@link PipelineEntity} itself — the controller maps it through the existing
 * {@code PipelineMapper.toDto}, which already converts directly from the raw entity, so no new mapper
 * method is needed here.
 *
 * <p>Returning the raw entity is also what makes the snapshot's contract right rather than merely
 * convenient: that overload resolves no derived grain key, and an enrich pipeline's
 * snapshot must not, since reconstructing either would need historical state for entities this
 * capability does not track.
 */
@Service
@RequiredArgsConstructor
public class AuditPipelineSnapshotService {

    private final EntityManager entityManager;

    /**
     * The pipeline {@code name} as of the end of {@code revision}, or 404 when there is nothing to
     * return — see {@link AuditSnapshotGuards} for why a missing revision and a pipeline absent at it
     * are the same answer.
     */
    @Transactional(readOnly = true)
    public PipelineEntity snapshot(String name, int revision) {
        final AuditReader reader = AuditReaderFactory.get(entityManager);
        AuditSnapshotGuards.requireExistingRevision(reader, revision);
        AuditSnapshotGuards.requireCreationWithinTheTrail(
                reader, PipelineEntity.class, name, revision, "pipeline '" + name + "'");
        final PipelineEntity entity = reader.find(PipelineEntity.class, name, revision);
        if (entity == null) {
            throw new RevisionNotFoundException("pipeline '" + name + "' did not exist at revision " + revision);
        }
        return entity;
    }
}
