package com.epam.aidial.analytics.service.table;

/**
 * The catalog's outbound cascade: deleting a table that is a pipeline's {@code target} must also delete
 * that pipeline, through the pipeline registry's own repository rather than the database's
 * {@code ON DELETE CASCADE} on {@code pipeline.target}.
 *
 * <p>One cascade covers both kinds, where the two registries needed one each before {@code V36} folded
 * them: {@code pipeline.target} is UNIQUE across kinds, so an aggregate rollup's source target and an
 * enrich declaration's enrichment target are the same lookup.
 *
 * <p>An interface rather than a direct call for the same reason as {@link PipelineGenerationBump} and
 * {@link PipelineInputDependents}: the deletion belongs to the pipeline registry, and
 * {@code service.pipeline} depends on {@code service.table}, never the reverse. It must nevertheless
 * share {@link TableCatalogService#delete}'s transaction and commit before the table definition itself
 * is deleted, so both removals land in one revision and the pipeline's own {@code Delete} is recorded
 * rather than lost to the schema's cascade, which fires no Hibernate event.
 */
public interface PipelineTargetCascade {

    /**
     * Deletes the pipeline whose {@code target} is {@code target}, if one exists. A no-op when no
     * pipeline targets it — a table need not have a pipeline at all.
     */
    void deletePipelineTargetingTable(String target);
}
