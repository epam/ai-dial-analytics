package com.epam.aidial.analytics.service.enrichment.model;

import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;

import java.util.Map;

/**
 * An ENRICH pipeline together with everything the runner needs to act on it without a second
 * round-trip: its typed declaration, and the scan source, grain key and source version column derived
 * from the target enrichment. Returned by pipeline reads and the sync feed.
 *
 * <p>There is no resolved evaluator, because there is nothing left to resolve: the transform is authored
 * on the pipeline and arrives inside {@link #spec}. What used to cost a Postgres lookup per pipeline (or
 * one batched fetch plus an in-memory version pick for the feed) is now a field read.
 *
 * <p>{@code versionColumn} is the source's catalog-declared version column
 * ({@code table_definition.version_column}) — the column a scan page carries under its own name, which
 * is also a group's activity timestamp. It is surfaced because it is a per-source fact: a caller that
 * had to configure the name itself could hold only one value for every source it scans. It is null when
 * the source declares no scan metadata, exactly as {@code source} is null when the target has no parent.
 *
 * <p>The read/sync paths populate every field. The data-plane resolves populate only what that path
 * reads to avoid needless lookups, leaving the rest null: the scan resolve omits {@code versionColumn}
 * (it reads the source's cursor columns directly, where a missing declaration is an error rather than an
 * absent field), and the write resolve omits {@code source} and {@code versionColumn} too.
 *
 * <p>{@code spec} is carried resolved rather than left to each caller to decode, so the stored JSON is
 * parsed once per request instead of once per consumer of the same pipeline.
 */
public record ResolvedEnrichPipeline(
        PipelineEntity pipeline,
        EnrichSpec spec,
        String source,
        String grainKey,
        String versionColumn,
        Map<String, Object> composedResponseSchema) {

    /** The narrower resolves, which compose no schema. */
    public ResolvedEnrichPipeline(
            PipelineEntity pipeline, EnrichSpec spec, String source, String grainKey, String versionColumn) {
        this(pipeline, spec, source, grainKey, versionColumn, null);
    }

    /** The transform this pipeline runs, created empty if the stored spec somehow carries none. */
    public EnrichSpec.TransformSpec transform() {
        return spec == null ? null : spec.transform();
    }

    /**
     * The response schema the model is actually held to: the declaration's <b>stored</b> schema when it
     * has one, and the schema composed from this pipeline's target and the transform's {@code outputs}
     * otherwise.
     *
     * <p><b>The stored one keeps winning while it is present</b>, and that is a decision rather than a
     * fallback order. A composed schema is not a faithful reproduction of a stored one — stored enum
     * order, stored type spelling and the property order {@code jsonb} imposed can each differ — and
     * under {@code response_format: {type: json_schema, strict: true}} property order is generation
     * order. So serving a composed schema for a declaration that stores one would silently change what a
     * live prompt looks like, on the pipeline that spends the most money. This {@code ??} is the whole of
     * the coexistence: two shapes in storage, one answer here.
     *
     * <p>What <em>has</em> changed with the fold is that "stored" is no longer a synonym for "immutable":
     * the schema is an ordinary authored member now, so a declaration patch that omits it clears it and
     * the pipeline composes thereafter. That is the only migration route off the legacy shape, and it is
     * the operator's explicit act, because a declaration patch supplies the whole declaration.
     */
    public Map<String, Object> effectiveResponseSchema() {
        if (spec == null || spec.getTransform() == null) {
            return composedResponseSchema;
        }
        return spec.getTransform().getResponseSchema() != null
                ? spec.getTransform().getResponseSchema()
                : composedResponseSchema;
    }

    /**
     * The flat view over the registry row and its stored declaration, for the many call sites that ask
     * questions of the declaration without caring which half holds the answer.
     */
    public EnrichDeclaration declaration() {
        return new EnrichDeclaration(pipeline, spec);
    }
}
