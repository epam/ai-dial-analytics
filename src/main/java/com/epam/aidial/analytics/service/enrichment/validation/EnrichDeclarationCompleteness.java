package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.EnrichDeclaration;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Whether a stored {@code enrich} declaration names every member its kind requires — the question the
 * pipeline-scoped data plane and the compiled projection ask of it, now that a disabled pipeline may be
 * stored before it passes the gate.
 *
 * <p><b>Absence only, never validity.</b> A complete declaration is answered as it is found: an enabled
 * pipeline passed the gate when it was armed, but its stored row can drift from it afterwards — a column
 * retyped, an enrichment it reads retired — and re-judging it here would stop its runner for drift the
 * operator did not cause. What is refused is a declaration that cannot be compiled at all — a query built
 * from members that are not there is not a query, and two of the callers failed on one outright (a null
 * group key is a 500 on group assembly) rather than refusing.
 *
 * <p>The one exception is {@link #requireCurrentOutputMapping}: a stored mapping that no longer describes
 * the stored transform counts as absent for the one caller that writes through it in the database.
 *
 * <p>A missing {@code transform} is listed by {@link #missingMembers} like any other member, which is what
 * the compiled listing reports. The data-plane callers go through {@link #requireRunnable}, which asks
 * {@link PipelineSpecCodec#requireTransform} first, so for them that refusal and its message own the
 * absence and {@link #requireComplete} never sees it.
 */
public final class EnrichDeclarationCompleteness {

    private EnrichDeclarationCompleteness() {
    }

    /**
     * The members this declaration lacks, in the wire spelling a caller would add them under — empty when
     * it is complete.
     */
    public static List<String> missingMembers(EnrichDeclaration declaration) {
        final List<String> missing = missingAuthoredMembers(declaration);
        // Derived rather than authored, so it is named by what derives it: outputs that resolve to target
        // columns. Reported only when the transform itself is not already missing its outputs, so one
        // absence is not reported twice under two names.
        if (!missing.contains("transform") && !missing.contains("transform.outputs")
                && (declaration.outputBindings() == null || declaration.outputBindings().isEmpty())) {
            missing.add("output mapping (transform.outputs resolving to target columns)");
        }
        return missing;
    }

    /**
     * The members an author writes that this declaration lacks — {@link #missingMembers} without the
     * derived output mapping, which a draft may lack for reasons the author does not control (its target
     * has no columns yet).
     */
    public static List<String> missingAuthoredMembers(EnrichDeclaration declaration) {
        final List<String> missing = new ArrayList<>();
        final EnrichSpec.TransformSpec transform = declaration.transform();
        if (transform == null) {
            missing.add("transform");
        } else {
            if (transform.getType() == null) {
                missing.add("transform.type");
            }
            if (transform.effectiveOutputVars().isEmpty()) {
                missing.add("transform.outputs");
            }
        }
        final TriggerKind trigger = declaration.triggerKind();
        if (trigger == null) {
            missing.add("trigger.kind");
        } else if (trigger == TriggerKind.SCHEDULE && StringUtils.isBlank(declaration.triggerCron())) {
            missing.add("trigger.cron");
        } else if (trigger == TriggerKind.GROUP && StringUtils.isBlank(declaration.triggerGroupBy())) {
            missing.add("trigger.group_by");
        }
        return missing;
    }

    /**
     * The data plane's one entry to a stored declaration: its transform, refused with the shared guard's
     * message when absent, and then the whole declaration held to {@link #requireComplete}.
     *
     * @param operation what the caller was about to do, for the message
     * @return the declared transform
     */
    public static EnrichSpec.TransformSpec requireRunnable(ResolvedEnrichPipeline resolved, String operation) {
        return requireRunnable(resolved, operation, transform -> { });
    }

    /**
     * As {@link #requireRunnable(ResolvedEnrichPipeline, String)}, with the caller's check of which path the
     * transform belongs on asked between the two — "use the other endpoint" is a more useful first answer
     * than the members a declaration would still need on an endpoint that would refuse it anyway.
     *
     * @param routeCheck throws when this caller does not serve the transform's type
     */
    public static EnrichSpec.TransformSpec requireRunnable(
            ResolvedEnrichPipeline resolved, String operation, Consumer<EnrichSpec.TransformSpec> routeCheck) {
        final EnrichSpec.TransformSpec transform = PipelineSpecCodec.requireTransform(
                resolved.pipeline().getName(), resolved.spec(), resolved.pipeline().isEnabled());
        routeCheck.accept(transform);
        requireComplete(resolved.declaration(), operation);
        return transform;
    }

    /**
     * Refuses an incomplete declaration with 422 {@code pipeline_validation_failed} naming what is absent.
     *
     * @param operation what the caller was about to do, for the message
     */
    public static void requireComplete(EnrichDeclaration declaration, String operation) {
        final List<String> missing = missingMembers(declaration);
        if (!missing.isEmpty()) {
            throw new PipelineValidationException("pipeline '" + declaration.name() + "' cannot " + operation
                    + ": its declaration is incomplete, missing " + String.join(", ", missing) + ". Complete it"
                    + " with PATCH /v1/pipelines/" + declaration.name() + "; POST /v1/pipelines/"
                    + declaration.name() + "/preview reports the next problem");
        }
    }

    /**
     * Refuses a stored output mapping that no longer describes the stored transform, as if it were absent.
     *
     * <p>A declaration write keeps a derived mapping it could not re-derive rather than erasing it — that
     * is what keeps the catalog's drop/rename guard armed — so the mapping can outlive the transform it came
     * from. Writing through it would put a newly authored expression into a column the previous transform
     * bound, which is a write the stored declaration does not describe.
     */
    public static void requireCurrentOutputMapping(EnrichDeclaration declaration, String operation) {
        if (!EnrichSpecValidator.outputBindingsMatchTransform(declaration.spec())) {
            throw new PipelineValidationException("pipeline '" + declaration.name() + "' cannot " + operation
                    + ": its stored output mapping was derived from a transform it no longer declares, so it"
                    + " does not say which target column each current output writes. Patch the pipeline with"
                    + " outputs that resolve to target columns, or arm it, to re-derive the mapping");
        }
    }
}
