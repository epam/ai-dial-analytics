package com.epam.aidial.analytics.service.audit;

import com.epam.aidial.analytics.service.audit.AuditResourceMapper.AuditResource;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.github.f4b6a3.uuid.UuidCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.envers.EntityTrackingRevisionListener;
import org.hibernate.envers.RevisionType;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Stamps each revision with the identity that caused it, and derives the activity feed from the entity
 * changes Envers reports within it.
 *
 * <p><strong>How this becomes a Spring bean.</strong> Envers resolves a revision listener through
 * Hibernate's {@code ManagedBeanRegistry} only when {@code hibernate.cdi.extensions} is enabled;
 * otherwise it reflects a fresh no-arg instance that Spring knows nothing about. That property is
 * therefore set in {@code application.yml}, and Spring Boot already registers its {@code
 * SpringBeanContainer} as Hibernate's bean container, so this component is injected normally. The
 * alternative — a static holder for the {@code ApplicationContext} — was rejected because a static is
 * shared by every Spring context in the JVM, and cached test contexts would then cross-wire: the last
 * context to start would own the listener for all of them.
 *
 * <p><strong>Why the revision entity is the working set.</strong> Dedup and parent linking both read
 * {@link AuditRevisionEntity#getActivities()} rather than a {@code ThreadLocal} map. The revision entity
 * is already exactly the per-transaction scope those two need, and it is handed to every
 * {@link #entityChanged} call — so a {@code ThreadLocal} would add a second scope with a worse lifecycle
 * (Envers offers no after-flush hook to clear one, leaving residue on pooled threads). The collection
 * holds one entry per changed entity, single digits in practice, so a linear scan beats the map.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditRevisionListener implements EntityTrackingRevisionListener {

    /** Recorded when the security context holds no authentication — never a fabricated identity. */
    static final String UNKNOWN_AUTHOR = "unknown";

    private final SecurityClaimsExtractor claims;
    private final AuditResourceMapper resources;
    private final AuditImportScope importScope;

    /**
     * Stamps the author, and the catalog import when {@link AuditImportScope} has one open on this thread.
     * The {@code @RevisionTimestamp} is already set by Envers before this runs, so it is deliberately left
     * alone.
     *
     * <p>Extraction is wrapped because an audit trail must not be able to break the operation it
     * observes: a failure to read the caller's identity degrades the author to {@code unknown} rather
     * than failing the caller's transaction.
     *
     * <p><strong>Invariant this depends on, because nothing enforces it:</strong> an audited write must
     * commit on the thread holding the caller's security context. Envers builds the revision in
     * {@code AuditProcess.doBeforeTransactionCompletion} — at <em>commit</em>, not when the entity was
     * changed — so this reads {@code SecurityContextHolder} then. Every audited write today commits on
     * the request thread, where that is the same context; but a write handed to another thread, or
     * committing after {@code SystemIdentity.runAsSystem} restored the previous context, would record
     * {@code unknown} — or, worse, whichever principal happens to occupy that thread-local — and nothing
     * would report it. A future audited write on an {@code @Async} or pooled path must carry the
     * identity explicitly rather than assume this still holds.
     */
    @Override
    public void newRevision(Object revisionEntity) {
        final AuditRevisionEntity revision = (AuditRevisionEntity) revisionEntity;
        String author = null;
        String email = null;
        boolean authenticated = false;
        try {
            // isAuthenticated() rather than a null check on the principal: in `none` mode Spring Security's
            // anonymous filter supplies a token whose name is the literal "anonymousUser", and recording
            // that as the author would be exactly the fabricated identity this fallback exists to avoid.
            authenticated = claims.isAuthenticated();
            author = claims.getAuthor();
            email = claims.getEmail();
        } catch (RuntimeException e) {
            log.warn("Failed to read the caller's identity for the audit trail; recording '{}'", UNKNOWN_AUTHOR, e);
        }
        final boolean attributable = authenticated && author != null && !author.isBlank();
        revision.setAuthor(attributable ? author : UNKNOWN_AUTHOR);
        // An email without a principal would be an identity assembled from half a context; if the caller
        // is unattributable, say so completely.
        revision.setEmail(attributable ? email : null);
        revision.setImportId(importScope.current().orElse(null));
    }

    @Override
    public void entityChanged(
            Class entityClass, String entityName, Object entityId, RevisionType revisionType, Object revisionEntity) {
        final AuditRevisionEntity revision = (AuditRevisionEntity) revisionEntity;
        resources.resolve(entityClass, entityId)
                .ifPresent(resource -> record(revision, resource, resources.activityType(revisionType)));
    }

    /**
     * Records one activity for {@code resource}, collapsing it into the entry already present for that
     * resource in this revision when there is one.
     */
    private void record(AuditRevisionEntity revision, AuditResource resource, ActivityType type) {
        final Optional<AuditActivityEntity> existing = find(revision, resource);
        if (existing.isPresent()) {
            // Create and Delete outrank Update: "this row came into existence" and "this row ceased to
            // exist" are the facts an auditor is looking for, and an Update recorded alongside either is
            // an implementation detail of how the transaction reached that state. A later Update never
            // displaces what is already recorded. The activity id is kept either way, so children that
            // already adopted this activity as their parent stay pointing at something real.
            if (type != ActivityType.Update) {
                existing.get().setActivityType(type);
            }
            return;
        }

        final AuditActivityEntity activity = new AuditActivityEntity();
        activity.setActivityId(UuidCreator.getTimeOrderedEpoch());
        activity.setActivityType(type);
        activity.setResourceType(resource.type());
        activity.setResourceId(resource.id());
        activity.setEpochTimestampMs(revision.getTimestamp());
        activity.setInitiatedAuthor(revision.getAuthor());
        activity.setInitiatedEmail(revision.getEmail());
        activity.setImportId(revision.getImportId());
        activity.setRevision(revision);
        revision.getActivities().add(activity);

        link(revision, activity);
    }

    /**
     * Resolves the parent link in both directions, because Envers guarantees no order between the
     * changes it reports: a new child adopts a parent activity already recorded for it, and a new
     * activity back-fills the parentless children already recorded for it, should it turn out to be
     * one of their parents. Assuming either direction would silently lose the link whenever the
     * transaction flushed the other way round. Neither direction is scoped to one resource type — the
     * mapper's {@code parentOf} already knows which shapes have a parent at all, so this asks it rather
     * than repeating that knowledge here.
     */
    private void link(AuditRevisionEntity revision, AuditActivityEntity activity) {
        final AuditResource thisResource = new AuditResource(activity.getResourceType(), activity.getResourceId());
        for (final AuditActivityEntity other : revision.getActivities()) {
            if (other != activity && other.getParentActivityId() == null && isChildOf(other, thisResource)) {
                other.setParentActivityId(activity.getActivityId());
            }
        }
        resources.parentOf(thisResource)
                .flatMap(parent -> find(revision, parent))
                .ifPresent(parent -> activity.setParentActivityId(parent.getActivityId()));
    }

    /** Whether {@code activity}'s parent, if it has one, is {@code candidateParent}. */
    private boolean isChildOf(AuditActivityEntity activity, AuditResource candidateParent) {
        return resources.parentOf(new AuditResource(activity.getResourceType(), activity.getResourceId()))
                .filter(candidateParent::equals)
                .isPresent();
    }

    private Optional<AuditActivityEntity> find(AuditRevisionEntity revision, AuditResource resource) {
        return revision.getActivities().stream()
                .filter(activity -> activity.getResourceType() == resource.type()
                        && activity.getResourceId().equals(resource.id()))
                .findFirst();
    }
}
