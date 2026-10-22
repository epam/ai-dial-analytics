package com.epam.aidial.analytics.service.audit;

import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.query.saved.SavedQueryEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingId;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.envers.RevisionType;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Translates a changed entity, as Envers reports it, into the {@code (resourceType, resourceId)} pair
 * the activity feed names it by — and an Envers {@link RevisionType} into an {@link ActivityType}.
 *
 * <p>The mapping is an explicit, exhaustive switch rather than anything reflective: an entity this does
 * not know is <em>dropped</em>, not guessed at. Dropping rather than throwing is deliberate — ADAS will
 * gain audited entities for other capabilities later, and a mapper that kills the caller's transaction
 * when it meets an unfamiliar one is a worse failure than a missing feed entry. The coverage contract
 * is enforced by tests instead.
 */
@Component
public class AuditResourceMapper {

    /**
     * Separates the two components of a composite resource id. A colon rather than a dot: neither a
     * table's name nor a ClickHouse column's physical name can contain one, but a dot reads as a
     * qualified name and invites a client to re-parse it as one, whereas the id is meant to be opaque.
     */
    private static final String ID_SEPARATOR = ":";

    private final PipelineRepository pipelines;

    /**
     * {@code @Lazy}, deliberately: this mapper is reached from {@link AuditRevisionListener}, which Envers
     * resolves while Hibernate is still building the {@code entityManagerFactory} bean — and a Spring
     * Data JPA repository is itself backed by that same {@code entityManagerFactory}. Injecting the
     * repository eagerly here is a real circular-dependency failure at context startup, not just a style
     * concern; the lazy proxy defers resolving the real repository bean until a method is actually called
     * on it, which happens only at runtime, long after the factory has finished bootstrapping.
     */
    public AuditResourceMapper(@Lazy PipelineRepository pipelines) {
        this.pipelines = pipelines;
    }

    /** The resource a changed entity is, or empty when this entity is not part of the audited surface. */
    public Optional<AuditResource> resolve(Class<?> entityClass, Object entityId) {
        if (TableDefinitionEntity.class.equals(entityClass)) {
            return Optional.of(new AuditResource(ActivityResourceType.Table, String.valueOf(entityId)));
        }
        if (ColumnMappingEntity.class.equals(entityClass) && entityId instanceof ColumnMappingId id) {
            return Optional.of(new AuditResource(
                    ActivityResourceType.TableColumn, id.getTableName() + ID_SEPARATOR + id.getSourceName()));
        }
        if (PipelineEntity.class.equals(entityClass)) {
            return Optional.of(new AuditResource(ActivityResourceType.Pipeline, String.valueOf(entityId)));
        }
        if (SavedQueryEntity.class.equals(entityClass)) {
            return Optional.of(new AuditResource(ActivityResourceType.SavedQuery, String.valueOf(entityId)));
        }
        return Optional.empty();
    }

    /**
     * The resource that owns {@code resource} for parent-activity linking, when one exists.
     *
     * <p>{@code TableColumn} encodes its parent in its own id (a {@code <parent>:<local>} pair), so it
     * resolves by splitting it. {@code Pipeline} does not: a pipeline's name carries no trace of the
     * source table whose {@code version_column} repoint may have bumped its generation, so that one case
     * resolves the pipeline's <b>effective</b> read source — its own declared {@code input_table}, or,
     * when it declares none, its target enrichment's {@code source_table} — because a pipeline bumped
     * through the derive-through-target half of {@code PipelineGenerationService#onVersionColumnRepointed}
     * has no {@code input_table} of its own to read. That resolution lives in the registry's repository
     * ({@code findEffectiveInputTable}) rather than being re-derived here, so the two cannot disagree, and
     * it is a scalar projection rather than an entity load for the reasons documented there. This runs
     * inside the same transaction that produced the bump, before commit, so the rows are still present and
     * current.
     *
     * <p>The lookup is asked of every {@code Pipeline} activity, not only a bumped one, and answers a
     * table for an ordinary create/patch/delete too. That costs nothing and links nothing: the parent is
     * attached only when an activity for that table exists in the <em>same revision</em>, which is exactly
     * the repoint shape. A pipeline deleted with its own target table is the one case where a parent
     * plausibly exists and none is linked — its target is not its read source — so such an activity is
     * parentless, which the feed treats as normal rather than as an error.
     */
    public Optional<AuditResource> parentOf(AuditResource resource) {
        return switch (resource.type()) {
            case TableColumn -> parentByIdPrefix(resource, ActivityResourceType.Table);
            case Pipeline -> parentOfPipeline(resource);
            case Table, SavedQuery -> Optional.empty();
        };
    }

    private Optional<AuditResource> parentByIdPrefix(AuditResource resource, ActivityResourceType parentType) {
        final int separator = resource.id().indexOf(ID_SEPARATOR);
        return separator < 0
                ? Optional.empty()
                : Optional.of(new AuditResource(parentType, resource.id().substring(0, separator)));
    }

    private Optional<AuditResource> parentOfPipeline(AuditResource resource) {
        return pipelines.findEffectiveInputTable(resource.id())
                .filter(StringUtils::isNotBlank)
                .map(inputTable -> new AuditResource(ActivityResourceType.Table, inputTable));
    }

    /** The activity type an Envers revision type means. */
    public ActivityType activityType(RevisionType revisionType) {
        return switch (revisionType) {
            case ADD -> ActivityType.Create;
            case MOD -> ActivityType.Update;
            case DEL -> ActivityType.Delete;
        };
    }

    /** A resource named as the feed names it. */
    public record AuditResource(ActivityResourceType type, String id) {}
}
