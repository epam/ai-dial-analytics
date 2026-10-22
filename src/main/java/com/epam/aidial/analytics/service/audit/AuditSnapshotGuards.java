package com.epam.aidial.analytics.service.audit;

import com.epam.aidial.analytics.exception.RevisionNotFoundException;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.exception.RevisionDoesNotExistException;
import org.hibernate.envers.query.AuditEntity;

import java.util.List;

/**
 * Shared utilities for every entity-specific snapshot service and the controllers that invoke them.
 *
 * <p>Contains the two revision-validity checks every snapshot service needs
 * ({@link AuditSnapshotService}, {@link AuditPipelineSnapshotService},
 * {@link AuditSavedQuerySnapshotService}), factored out once a third snapshot service made the
 * hand-written copies worth collapsing into one, plus the path-variable parser shared across the three
 * snapshot controller methods.
 */
public final class AuditSnapshotGuards {

    private AuditSnapshotGuards() {
    }

    /**
     * Parses a revision path-variable into an {@code int}, answering {@code 404 revision_not_found}
     * for anything that cannot be one.
     *
     * <p>An {@code int} {@code @PathVariable} binding failure raises
     * {@code MethodArgumentTypeMismatchException}, which {@code DefaultExceptionHandler} does not map,
     * so {@code /revision/abc} would otherwise answer {@code 500} for a plainly client-side mistake.
     * Taking a {@code String} and parsing explicitly gives the right status instead.
     */
    public static int parseRevision(String revision) {
        try {
            return Integer.parseInt(revision);
        } catch (NumberFormatException e) {
            throw new RevisionNotFoundException("revision '" + revision + "' does not exist");
        }
    }

    /**
     * Rejects a revision that was never issued, <em>before</em> reading any state at it.
     *
     * <p>This check is not redundant, which is the whole reason it exists: Envers resolves an entity at
     * a revision by taking its latest audit row at or before that number, so a revision far in the
     * future resolves happily to the current state. Without this, {@code /revision/99999} would answer
     * {@code 200} with today's state — the most misleading possible reply, since a client diffing that
     * "past" state against the present would conclude nothing had ever changed.
     */
    static void requireExistingRevision(AuditReader reader, int revision) {
        // Checked here rather than left to Envers, which refuses a non-positive revision with a bare
        // IllegalArgumentException — the same exception it raises when the class handed to it is not the
        // configured revision entity. Catching that type wholesale would turn a misconfigured or renamed
        // @RevisionEntity into a permanent, silent 404 on every snapshot instead of a loud failure the
        // first time one is asked for.
        if (revision <= 0) {
            throw new RevisionNotFoundException("revision " + revision + " does not exist");
        }
        try {
            reader.findRevision(AuditRevisionEntity.class, revision);
        } catch (RevisionDoesNotExistException e) {
            throw new RevisionNotFoundException("revision " + revision + " does not exist");
        }
    }

    /**
     * Refuses to reconstruct a resource whose creation is not itself in the trail.
     *
     * <p>The trail starts at deployment, so a resource that already existed then has history only from
     * whatever touched it afterwards — and, for a table specifically, its <em>columns</em> get audit
     * rows only when they are individually changed. Without this check such a resource answers
     * {@code 200} with a definition read from the one row that exists (and, for a table, an
     * <em>empty</em> column list, because {@code column_mapping_aud} has nothing for it). That reply is
     * not merely incomplete — for a table it is self-contradicting (an {@code orderingKey} naming
     * columns the same response omits) — and a client diffing it against the present would conclude
     * everything had been dropped.
     *
     * <p>The discriminator is a {@code Create} row at or before the revision: the trail can only rebuild
     * a resource whose whole life it witnessed. This also subsumes "before it was created" — the same
     * 404, for the same reason.
     *
     * @param resourceDescription names the resource in the exception message, e.g. {@code "table 'x'"}
     */
    static void requireCreationWithinTheTrail(
            AuditReader reader, Class<?> entityClass, Object id, int revision, String resourceDescription) {
        final List<?> creations = reader.createQuery()
                .forRevisionsOfEntity(entityClass, false, true)
                .add(AuditEntity.id().eq(id))
                .add(AuditEntity.revisionNumber().le(revision))
                .add(AuditEntity.revisionType().eq(RevisionType.ADD))
                .getResultList();
        if (creations.isEmpty()) {
            throw new RevisionNotFoundException("no tracked state for " + resourceDescription + " at revision "
                    + revision + "; its history does not begin within the audit trail");
        }
    }
}
