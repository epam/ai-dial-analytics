package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.pipeline.model.PipelineSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts a pipeline's transform declaration between its typed {@link PipelineSpec} form and the text
 * stored in the {@code spec} {@code jsonb} column, dispatching on the pipeline's {@link PipelineKind}.
 *
 * <p><b>Why the kind decides, and not something inside the JSON.</b> One column carries two types, and
 * the discriminator has to live outside the value because Hibernate binds a JSON column through the
 * <em>declared field type</em>: there is no hook that can see a sibling column, and an
 * {@code AttributeConverter} could not do it either — it receives only the column value. The alternative
 * considered was a Jackson type-id stamped into the JSON, which is more type-safe at the entity but buys
 * that by putting every existing aggregate declaration behind a data migration it does not need. The
 * discriminator already exists as a column, is already what {@code PipelineService} branches on, and is
 * already enforced by a database CHECK, so reusing it costs nothing and rewrites nothing.
 *
 * <p>The consequence worth stating: a stored declaration written before the fold is read back byte for
 * byte, because nothing stamped it and nothing needs to.
 *
 * <p>Uses the <strong>application's</strong> {@link JsonMapper} for the same reason
 * {@code SavedQueryJson} does — Hibernate's format mapper is Jackson 2 while this codebase's databind is
 * Jackson 3. Both specs' annotations are the shared {@code com.fasterxml.jackson.annotation} ones, so a
 * row Hibernate wrote before the fold parses identically here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PipelineSpecCodec {

    private final JsonMapper jsonMapper;

    /** Serializes a declaration for the {@code spec} column. */
    public String write(PipelineSpec spec) {
        try {
            return jsonMapper.writeValueAsString(spec);
        } catch (JacksonException e) {
            throw new PipelineValidationException("the pipeline declaration could not be serialized");
        }
    }

    /**
     * Parses a stored declaration back into the type this pipeline's {@code kind} selects.
     *
     * <p>A failure means a stored row cannot be read — it was written by this same mapper under this same
     * kind — so it is data corruption rather than caller error and surfaces as a server error.
     */
    public PipelineSpec read(PipelineKind kind, String json) {
        return switch (kind) {
            case AGGREGATE -> read(json, AggregateSpec.class);
            case ENRICH -> readEnrich(json);
            case EXTERNAL -> throw new IllegalStateException("pipeline kind " + kind.code() + " has no stored declaration");
        };
    }

    /** The declaration of a pipeline, typed by its own stored kind. */
    public PipelineSpec read(PipelineEntity pipeline) {
        return read(pipeline.getKind(), pipeline.getSpec());
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return jsonMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("stored pipeline declaration is not readable as " + type.getSimpleName(), e);
        }
    }

    /**
     * The aggregate declaration of a pipeline asserted to be one. Callers that have already dispatched on
     * kind use this rather than casting the {@link PipelineSpec}, so a kind mix-up is one failure here
     * instead of a {@code ClassCastException} several frames away.
     */
    public AggregateSpec readAggregate(PipelineEntity pipeline) {
        requireKind(pipeline, PipelineKind.AGGREGATE);
        return read(pipeline.getSpec(), AggregateSpec.class);
    }

    /** The enrich declaration of a pipeline asserted to be one; see {@link #readAggregate}. */
    public EnrichSpec readEnrich(PipelineEntity pipeline) {
        requireKind(pipeline, PipelineKind.ENRICH);
        return readEnrich(pipeline.getSpec());
    }

    /**
     * Parses an enrich declaration and <b>normalises it</b>, which is what makes parse-then-write
     * lossless for a spec written before the transform block existed.
     *
     * <p>{@link EnrichSpec#normalize()} moves a top-level {@code input_bindings} list into the transform.
     * Without it, enabling such a pipeline — which re-runs the gate over the spec it parsed and stores the
     * object the gate leaves behind — would rewrite the declaration with an <em>empty</em> binding list,
     * and the catalog's drop/rename guard derives its edges from exactly that list. Every read of a stored
     * enrich declaration goes through here, so there is no path that skips it.
     */
    private EnrichSpec readEnrich(String json) {
        final EnrichSpec spec = read(json, EnrichSpec.class);
        spec.normalize();
        return spec;
    }

    /**
     * The stored {@code transform} of an enrich declaration, or a loud refusal naming the pipeline.
     *
     * <p><b>What this catches.</b> An {@code ENRICH} declaration that parses but carries no
     * {@code transform} is not a shape this build can produce: the gate refuses one on every write, and
     * the fold migration asserts after its {@code UPDATE} that no {@code ENRICH} row is left without one.
     * The way it happens anyway is a <em>rolling</em> ADAS deploy. The outgoing pod's {@code EnrichSpec}
     * has no {@code transform} member and ignores unknown properties, so a plain
     * {@code PATCH {"enabled": true}} served by it re-serializes the declaration without the block — and
     * with {@code input_bindings} emptied, because those moved inside the block too and the old top-level
     * member is no longer written. The declaration that comes back is therefore not merely missing its
     * prompt: it also tells the catalog's drop/rename guard that this pipeline reads no columns.
     *
     * <p><b>Why here and not in {@link #read}.</b> Refusing on every parse would make one damaged row
     * fail {@code GET /v1/pipelines} for the whole registry and take the sync feed with it, which is
     * strictly worse for recovery than a per-pipeline refusal: the operator needs to list the pipelines
     * in order to find and re-declare the damaged one. So the parse stays tolerant and this guard is
     * called from the paths where an empty transform would do damage rather than merely be visible — the
     * declaration gate (register, enable, declaration patch), the dry-run preview, the row scan and the
     * Class-1 materialize.
     *
     * @throws PipelineValidationException (422 {@code pipeline_validation_failed}) naming the pipeline and
     *     the repair, because the only fix is an operator re-declaring the transform
     */
    public static EnrichSpec.TransformSpec requireTransform(String pipelineName, EnrichSpec spec) {
        return requireTransform(pipelineName, spec, true);
    }

    /**
     * As {@link #requireTransform(String, EnrichSpec)}, told whether the pipeline is <b>enabled</b> — which
     * decides what the absence means.
     *
     * <p>The refusal is the same in both states and asserts neither cause. What the state decides is the
     * log: on an enabled pipeline the absence is still the pre-fold signature — arming runs the gate, which
     * refuses a declaration with no transform — and it is logged at ERROR, because nothing else reports it.
     * On a <b>disabled</b> pipeline it is ordinarily a draft that has not declared its transform yet, the
     * first thing an author saves, and nothing is logged at ERROR for an expected state.
     */
    public static EnrichSpec.TransformSpec requireTransform(String pipelineName, EnrichSpec spec, boolean enabled) {
        final EnrichSpec.TransformSpec transform = spec == null ? null : spec.getTransform();
        if (transform != null) {
            return transform;
        }
        // One message for both causes, because they are the same bytes: an author's draft that has not
        // declared its transform yet, or a pipeline a pre-fold write stripped. The refusal names the repair
        // both share and does not assert which cause it is. The log level is what separates them: the gate
        // refuses to arm a declaration with no transform, so an ENABLED one did not get there through the
        // API — ERROR, because nothing retries this and nothing else reports it — while a disabled one is an
        // ordinary draft, and an ERROR per draft per call would bury the damage the log exists for.
        if (enabled) {
            log.error("Enrich pipeline '{}' is enabled and has no 'transform' in its stored declaration. The gate"
                    + " refuses to arm one, so this is the signature of a pre-fold write landing on a folded"
                    + " pipeline — an ADAS pod that predates the fold served a write (a bare enabled-only patch"
                    + " is enough) and re-serialized the declaration without the transform block and with its"
                    + " input bindings emptied. The pipeline is refused rather than run. Re-declare it with"
                    + " PATCH /v1/pipelines/{} carrying the full declaration; pipeline_aud holds the last"
                    + " revision that had one.", pipelineName, pipelineName);
        } else {
            log.debug("Enrich pipeline '{}' is disabled and declares no transform yet; refused", pipelineName);
        }
        throw new PipelineValidationException("pipeline '" + pipelineName + "' has no 'transform' in its"
                + " stored declaration, so it declares no work and is refused rather than run. Declare one with"
                + " PATCH /v1/pipelines/" + pipelineName + ". If this pipeline had a transform before, a write"
                + " served by a pre-fold ADAS pod may have dropped it and emptied the transform's input"
                + " bindings; its previous declaration is in the audit trail — re-declare it in full and"
                + " re-check the bound input columns");
    }

    private static void requireKind(PipelineEntity pipeline, PipelineKind expected) {
        if (pipeline.getKind() != expected) {
            throw new IllegalStateException("pipeline '" + pipeline.getName() + "' is " + pipeline.getKind().code()
                    + ", not " + expected.code());
        }
    }
}
