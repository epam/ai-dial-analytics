package com.epam.aidial.analytics.service.pipeline.model;

import com.epam.aidial.analytics.service.enrichment.model.InputBinding;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.enrichment.model.TransformOutput;
import com.epam.aidial.analytics.service.enrichment.model.TransformPreset;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TransformVar;
import com.epam.aidial.analytics.service.enrichment.support.OutputShapes;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The enrich declaration stored as a pipeline's {@code spec} JSONB: the transform it runs, what that
 * transform reads and writes back, and the lane settings the Enrichment Runner executes it under. The
 * counterpart of {@link AggregateSpec}, selected by the pipeline's {@code kind}.
 *
 * <p><b>What is here and what is not</b> follows one line: the transform is here, the trigger is not.
 * {@code trigger_kind}, {@code trigger_cron}, the group key, {@code ready_when} and {@code member_select}
 * stay real columns on the entity, because they carry the database's structural guards (a cron iff a
 * schedule, a group key iff a group trigger) and JSON cannot be checked. Everything left describes what
 * the transform does with a row, which nothing outside the service layer constrains.
 *
 * <p>An enrichment's definition is {@link #transform} below, authored on the pipeline itself: there is no
 * second object to point at and no foreign key to keep.
 *
 * <p>Most members are optional and a live declaration typically leaves most unset, so a value read back
 * as null means "not declared" and the runner applies its own configured fallback. That is why none of
 * them carries a default here: a default written at this layer would be indistinguishable from an
 * operator's deliberate choice, and would silently override the runner's configuration.
 *
 * <p>Unknown properties are tolerated so a spec written by a later version stays readable.
 *
 * <h2>The three renamed knobs carry an alias, and the direction matters</h2>
 *
 * <p>{@code rows_per_scan}, {@code rows_per_call} and {@code sample_fraction} are
 * written under those names and read under those names <em>or</em> their former spellings
 * ({@code batch_scan_limit}, {@code batch_chunk}, {@code sampling}), via
 * {@link JsonAlias}. Every row stored before this change keeps parsing, for as long as it is never
 * rewritten, and no data migration exists.
 *
 * <p>An alias reads <b>old rows with new code</b>, which is the safe direction and the only one in play.
 * It would not survive the reverse — reverted code has no alias, so a row this code had already rewritten
 * would read back with an empty binding list, and {@code PipelineDeclaration.inputBindings(codec)} feeds
 * the catalog's drop/rename dependency guard, where an empty list quietly makes the columns an enrich
 * pipeline reads droppable.
 *
 * <h2>{@code input_bindings} moved, so the top-level member is a permanent read shim</h2>
 *
 * <p>The transform's inputs now live at {@code transform.input_bindings}, beside its outputs. A spec
 * written before that move carries them at the top level, and {@link JsonAlias} cannot reach them —
 * it renames a key <em>within</em> one object and cannot read a member that used to sit at the parent
 * level. {@link #legacyInputBindings} is therefore a real read-only shim: write-only to Jackson, so it
 * never round-trips back out, and consulted by {@link #getInputBindings()} when the nested list is empty.
 * Without it every pre-fold spec would read back with <b>empty</b> bindings, which is the silent
 * DDL-data-loss case above rather than a cosmetic gap. A spec is never rewritten <em>in order to</em>
 * migrate it, so the shim is permanent — but a spec that <em>is</em> rewritten for some other reason must
 * not lose them either, which is what {@link #normalize()} handles.
 *
 * <p>{@code output_bindings} stays at the top level and is deliberately <b>not</b> moved into the block:
 * it is not authored at all. It is derived by the declaration gate from the transform's outputs matched to
 * the target's columns, and the write-side guard reads it directly.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public final class EnrichSpec implements PipelineSpec {

    /**
     * Where a pre-fold spec kept the transform's input bindings. Read-only by construction
     * ({@code WRITE_ONLY} means Jackson binds it inbound and never serializes it), so a declaration this
     * build rewrites lands wholly inside {@link #transform}. See the class javadoc.
     */
    @JsonProperty(value = "input_bindings", access = JsonProperty.Access.WRITE_ONLY)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<InputBinding> legacyInputBindings = new ArrayList<>();

    /**
     * The transform this pipeline runs, authored on the pipeline itself. Null on a disabled draft that has
     * not declared one yet, or on a declaration a pre-fold write stripped (see {@link #transform()}).
     */
    @JsonProperty("transform")
    private TransformSpec transform;

    /** Which target enrichment column each output is written to — derived by the gate, never authored. */
    @JsonProperty("output_bindings")
    private List<OutputBinding> outputBindings = new ArrayList<>();

    /** Fraction of eligible rows to evaluate, or null for all of them. */
    @JsonProperty("sample_fraction")
    @JsonAlias("sampling")
    private Double sampling;

    /** Upper bound on rows one scan tick may claim; null means the runner's configured default. */
    @JsonProperty("rows_per_scan")
    @JsonAlias("batch_scan_limit")
    private Integer batchScanLimit;

    /** How many rows the runner evaluates per work item; null means its configured default. */
    @JsonProperty("rows_per_call")
    @JsonAlias("batch_chunk")
    private Integer batchChunk;

    /** Per-pipeline model call ceiling per minute; null means the runner's configured default. */
    @JsonProperty("rate_rpm")
    private Integer rateRpm;

    /**
     * The transform's input bindings, from wherever this spec stores them: the nested list when it has
     * any, and the pre-fold top-level list otherwise.
     *
     * <p>{@code @JsonIgnore} because this is a resolution, not a member — serializing it would write a
     * third spelling of the same fact into the stored declaration.
     */
    @JsonIgnore
    public List<InputBinding> getInputBindings() {
        if (transform != null && !transform.getInputBindings().isEmpty()) {
            return transform.getInputBindings();
        }
        return legacyInputBindings == null ? List.of() : legacyInputBindings;
    }

    /** Writes the bindings where this build stores them — inside the transform, creating it if absent. */
    @JsonIgnore
    public void setInputBindings(List<InputBinding> bindings) {
        mutableTransform().setInputBindings(bindings == null ? new ArrayList<>() : new ArrayList<>(bindings));
    }

    /**
     * Moves a pre-fold spec's top-level bindings into the transform, so that <b>parsing and re-writing a
     * stored declaration cannot lose them</b>.
     *
     * <p>That round trip is not hypothetical: enabling a pipeline re-runs the gate over the spec it parsed
     * and stores the object the gate leaves behind, so without this a folded pre-fold declaration would be
     * rewritten with an empty binding list — which {@code EnrichSpec}'s own read shim exists to prevent,
     * and which the catalog's drop/rename guard reads as "this pipeline references no columns".
     *
     * <p>Called from {@code PipelineSpecCodec.read}, the one place a stored spec becomes this type, rather
     * than from a getter: a normalisation with a single explicit call site is findable, and one hidden in
     * an accessor is a side effect nobody expects to have run.
     */
    public void normalize() {
        if (legacyInputBindings != null && !legacyInputBindings.isEmpty()
                && mutableTransform().getInputBindings().isEmpty()) {
            mutableTransform().setInputBindings(new ArrayList<>(legacyInputBindings));
        }
    }

    /**
     * The transform, or a <b>detached</b> empty one when this spec carries none — never null, and
     * <b>never written back into the spec</b>.
     *
     * <p>This used to be a lazy <em>mutating</em> getter, and the difference is not stylistic. During a
     * rolling ADAS deploy the outgoing pod still serves writes, and its {@code EnrichSpec} has no
     * {@code transform} member at all while tolerating unknown properties — so a plain
     * {@code PATCH {"enabled": true}} reaching it re-serializes the declaration <em>without</em> the block
     * the fold migration just wrote (and with {@code input_bindings} reset to {@code []}, which is the
     * catalog drop/rename guard's data-loss case). A read on the new build that then manufactured a
     * transform and stored it would turn that loss into a pipeline which parses, validates and runs while
     * declaring no model, no prompt and no outputs: <em>defanged</em> rather than broken, which is the one
     * outcome nothing downstream can detect. A detached instance keeps every existing caller's
     * "null-transform behaves like an empty one" convenience without letting a read author storage.
     *
     * <p>Callers that need to know whether the declaration really has one ask {@link #getTransform()},
     * and the loud refusal that belongs on that answer lives in {@code PipelineSpecCodec.requireTransform},
     * called from the paths where an empty transform would do damage.
     */
    @JsonIgnore
    public TransformSpec transform() {
        return transform == null ? new TransformSpec() : transform;
    }

    /**
     * The transform, <b>created and attached</b> if this spec has none — the authoring accessor.
     *
     * <p>Separate from {@link #transform()} so that creating a block is always an explicit act of a write
     * path ({@link #setInputBindings}, {@link #normalize()}, a fixture composing a declaration) and never
     * a side effect of reading one. See {@link #transform()} for what a read that authored storage cost.
     */
    @JsonIgnore
    public TransformSpec mutableTransform() {
        if (transform == null) {
            transform = new TransformSpec();
        }
        return transform;
    }

    /**
     * The transform an {@code enrich} pipeline declares: its grammar selector and, dispatched on that,
     * everything needed to produce a value for each target column it writes.
     *
     * <p><b>Nested rather than flat</b>, and that is the decisive shape decision rather than tidiness.
     * {@code outputs} already exists at the top level of a pipeline <em>read</em>, where it is the
     * <em>derived</em> mapping — an ordered array, one entry per mapped target column. The authored
     * outputs are not that: they are an object keyed by target column name whose values are prose or
     * expression strings. Flattening would put an array and an object under one member name in two
     * projections of the same object, which the registry's one-spelling rule forbids and which would let a
     * read-modify-write client on the compiled projection post the derived array back as the authored map.
     *
     * <p>Two shapes of output coexist in storage indefinitely — the current {@link #outputs} and the
     * legacy {@link #outputVars} a folded pre-fold definition carries — so <b>do not read either raw
     * field</b>. Resolve through {@link #effectiveOutputVars()}.
     *
     * <p>{@link #responseSchema} is the schema a folded legacy declaration <em>stores</em>, and it keeps
     * winning over composition. It is an ordinary authored member now that it lives here: a declaration
     * patch that omits it clears it, and that pipeline composes from {@link #outputs} thereafter. That is
     * the only migration route off the legacy shape, and it changes a live prompt, so it is the operator's
     * explicit act rather than a side effect.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TransformSpec {

        /** The grammar selector: {@code llm} calls a model, {@code sql} derives in the database. */
        @JsonProperty("type")
        private TransformType type;

        /** DIAL deployment the transform calls; {@code llm} only. */
        @JsonProperty("model")
        private String model;

        /** Request-shaping preset; {@code llm} only. */
        @JsonProperty("preset")
        private TransformPreset preset;

        /** Parameters shaping the preset's request; {@code llm} only. */
        @JsonProperty("params")
        private Map<String, Object> params;

        /** The request template rendered per row or per group; {@code llm} only. */
        @JsonProperty("request_template")
        private String requestTemplate;

        /** What the transform's variables are bound to, over the read source's declared fields. */
        @JsonProperty("input_bindings")
        private List<InputBinding> inputBindings = new ArrayList<>();

        /**
         * The current declaration of what this transform produces: an <b>ordered</b> array, keyed by the
         * target column that receives each entry. Null or empty on a folded declaration whose definition
         * predates this shape, which keeps {@link #outputVars} (and {@link #responseSchema}) authoritative
         * for it forever.
         */
        @JsonProperty("outputs")
        private List<TransformOutput> outputs;

        /** The legacy output shape, carried verbatim by the fold and never synthesised. */
        @JsonProperty("output_vars")
        private List<TransformVar> outputVars = new ArrayList<>();

        /** The response schema this declaration stores, which wins over composition while it is present. */
        @JsonProperty("response_schema")
        private Map<String, Object> responseSchema;

        /** This transform's outputs as vars, whichever shape they are stored in. */
        @JsonIgnore
        public List<TransformVar> effectiveOutputVars() {
            return OutputShapes.effectiveOutputVars(outputs, outputVars);
        }

        /** This transform's response transform per output name, in declaration order. */
        @JsonIgnore
        public Map<String, String> jsonataByOutputName() {
            return OutputShapes.jsonataByOutputName(outputs, outputVars);
        }
    }
}
