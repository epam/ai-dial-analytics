package com.epam.aidial.analytics.service.enrichment.model;

import com.epam.aidial.analytics.service.pipeline.PipelineDeclaration;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;

import java.util.List;

/**
 * One ENRICH pipeline's declaration as a flat view over the two places it is stored: the registry row and
 * the {@code spec} JSON it carries.
 *
 * <p>The split is deliberate at the storage layer — the trigger fields are columns because they carry
 * database constraints, while the transform is JSON — and it is noise everywhere else. The governance gate asks 35 questions of a declaration and not one of them
 * cares which half an answer came from, so the alternative was threading an {@code (entity, spec)} pair
 * through twenty method signatures.
 *
 * <p>Read-only and cheap: it holds the two objects rather than copying their fields, so it can be built per
 * request without a second decode.
 */
public record EnrichDeclaration(PipelineEntity pipeline, EnrichSpec spec) {

    public String name() {
        return pipeline.getName();
    }

    /** The target enrichment this declaration writes. */
    public String target() {
        return pipeline.getTarget();
    }

    /**
     * The <b>declared</b> read source, or null for "follow the target enrichment's parent". Deliberately
     * not the resolved value: the null is what the version-column bump and the delete guard key off.
     */
    public String declaredSource() {
        return pipeline.getInputTable();
    }

    public String filterSql() {
        return pipeline.getFilterSql();
    }

    public TriggerKind triggerKind() {
        return pipeline.getTriggerKind();
    }

    public String triggerCron() {
        return pipeline.getTriggerCron();
    }

    /** The group key a {@code GROUP}-triggered declaration fires per; null at row grain. */
    public String triggerGroupBy() {
        return pipeline.getTriggerGroupBy();
    }

    public ReadyWhen readyWhen() {
        return pipeline.getReadyWhen();
    }

    public MemberSelect memberSelect() {
        return pipeline.getMemberSelect();
    }

    /**
     * The transform this declaration runs, created empty if the stored spec carries none — which only a
     * declaration written before the fold and never folded could, and the migration's post-condition
     * rules that out.
     */
    public EnrichSpec.TransformSpec transform() {
        return spec.transform();
    }

    public List<InputBinding> inputBindings() {
        return spec.getInputBindings();
    }

    public List<OutputBinding> outputBindings() {
        return spec.getOutputBindings();
    }

    public Double sampling() {
        return spec.getSampling();
    }

    public Integer batchScanLimit() {
        return spec.getBatchScanLimit();
    }

    public Integer batchChunk() {
        return spec.getBatchChunk();
    }

    public Integer rateRpm() {
        return spec.getRateRpm();
    }

    public long generation() {
        return pipeline.getGeneration();
    }

    public boolean enabled() {
        return pipeline.isEnabled();
    }

    /**
     * The detached projection form, for the column-naming questions the catalog asks from inside a delete
     * transaction — see {@link PipelineDeclaration} for why a managed entity must not be used there.
     */
    public PipelineDeclaration projection() {
        return new PipelineDeclaration(name(), pipeline.getKind(), declaredSource(), target(),
                triggerGroupBy(), filterSql(), readyWhen(), memberSelect(), pipeline.getSpec());
    }
}
