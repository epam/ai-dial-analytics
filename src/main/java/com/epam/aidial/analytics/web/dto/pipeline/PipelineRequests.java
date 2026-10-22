package com.epam.aidial.analytics.web.dto.pipeline;

import com.epam.aidial.analytics.service.enrichment.model.TransformPreset;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.model.TruncUnit;
import com.epam.aidial.analytics.web.dto.enrichment.MemberSelectDto;
import com.epam.aidial.analytics.web.dto.enrichment.OutputSpec;
import com.epam.aidial.analytics.web.dto.enrichment.OutputSpecDeserializer;
import com.epam.aidial.analytics.web.dto.enrichment.ReadyWhenDto;
import com.epam.aidial.analytics.web.dto.enrichment.TransformVarDto;
import com.epam.aidial.analytics.web.dto.enrichment.VarSpec;
import com.epam.aidial.analytics.web.dto.enrichment.VarSpecDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request bodies of the pipeline control plane. Grouped in one file because they are one small family of
 * shapes describing a single declaration, and splitting them would scatter the aggregate vocabulary
 * across six files.
 *
 * <p>The aggregate declaration mirrors the design's engine-agnostic shape: {@code group_by} +
 * {@code measures}, naming no ClickHouse construct. Both group-key forms are objects
 * (rather than the design note's bare-string shorthand for a plain column) so that, per the project's API
 * conventions, entry objects can gain fields later without breaking clients.
 *
 * <p><b>What these records no longer accept, and why each is a 400 naming the field rather than a
 * tolerated alias.</b> {@code output_bindings} (derived from the transform's outputs matched to the
 * target's columns), {@code input_bindings} and the retired {@code input_vars} (replaced by
 * {@code transform.inputs}, one map whose keys are the names), {@code freshness} (one legal value, three
 * levels of nesting, no information), {@code priority} (nothing reads it), and the flat {@code sampling},
 * {@code cadence}, {@code batch_scan_limit} and {@code batch_chunk} (now {@code advanced} members under
 * clearer names). Accepting the old spellings for a release was considered and rejected: it could not
 * deliver the compatibility it would exist for — {@code freshness} and {@code priority} have no derived
 * value to check against and would be refused anyway, so a stale caller re-declaring any live aggregate
 * breaks either way — and accepting eight names the read never echoes is precisely the failure
 * {@code StrictRequestJsonConfiguration} was added to prevent. The authoring clients carry the new names
 * in the same release.
 *
 * <p><b>{@code evaluator_name}, {@code evaluator_version} and the flat {@code vars} join that list</b>, and
 * for the strongest form of the reason: they belong to <em>no</em> kind now, so each is an unknown member
 * on either one and is refused at the binding with a 400 naming it, before the cross-kind check is
 * reached. The definition the first two pointed at is authored on the pipeline as
 * {@link CreateTransformRequest}, and {@code vars} moved inside it as {@code transform.inputs} — nested
 * because the top-level {@code inputs} is the table list, the same collision {@code trigger.group_by}
 * already resolves by nesting. The two are misnesting-safe as well as distinct: the table list is an
 * array and the variable map an object, so a member sent at the wrong level is a binding 400 rather than a
 * silent accept.
 *
 * <p><b>One request shape serves both kinds</b>, with the per-kind members simply optional. Three
 * {@code @NotNull}s that the aggregate-only shape carried are therefore gone, and each is load-bearing
 * rather than relaxed for tidiness: {@code group_by} and {@code measures} belong to one kind, and
 * <b>{@code inputs}</b> because a null input is meaningful on the other — it is how an {@code enrich}
 * pipeline says "follow my target's parent". The "required for {@code aggregate}" half of that contract now
 * lives only in the kind-dispatched validation and in the database's
 * {@code pipeline_input_required_unless_enrich} check, so it has to be tested on both sides.
 *
 * <p>What keeps a flat union safe is that <b>a field belonging to the other kind is rejected with 422</b>,
 * never ignored — see {@code PipelineMapper}.
 */
public final class PipelineRequests {

    private PipelineRequests() {
    }

    /**
     * Body of {@code POST /v1/pipelines}, for either kind. Only {@code name}, {@code kind} and
     * {@code target} are required: a pipeline registered disabled is a draft, and every other member may be
     * authored by later patches, with the kind's gate running when the pipeline is armed. An
     * {@code aggregate} pipeline is always created disabled — its dry-run preview is the gate the disabled
     * state exists for, and {@code enabled: true} is refused. An {@code enrich} pipeline honours
     * {@code enabled}, and a registration that arms it is gated in full.
     */
    public record CreatePipelineRequest(
            @Schema(description = "Pipeline name (unique; also the {name} in the API path). An identity "
                    + "rather than a SQL identifier: `^[a-z][a-z0-9_-]{0,63}$`, so both snake_case and "
                    + "kebab-case are accepted. Immutable once registered.",
                    example = "usage_daily_rollup")
            @NotBlank String name,

            @Schema(description = "Pipeline kind; `aggregate` and `enrich` are implemented",
                    example = "aggregate")
            @NotNull PipelineKind kind,

            @Schema(description = "The one table this pipeline writes; it must exist at registration. An "
                    + "`aggregate` pipeline must name a materialized source written `upsert_by_key` whose "
                    + "ordering_key equals its group keys, and an `enrich` pipeline must name an enrichment — "
                    + "both checked when the pipeline is armed (and at preview), not when a disabled draft is "
                    + "stored.",
                    example = "usage_daily")
            @NotBlank String target,

            @Schema(description = "The one table this pipeline reads (v1 reads exactly one source). "
                    + "Required for a form-mode `aggregate` by the time it is armed; a disabled draft may omit it. "
                    + "Optional in SQL mode, where omitting it means the table the statement's FROM reads. Optional "
                    + "for `enrich`, where omitting it means \"read my target enrichment's own parent source\" "
                    + "— a declared input must carry the target's grain key and declare scan metadata.",
                    example = "dial_usage_log")
            List<String> inputs,

            @Schema(description = "Optional membership predicate over the input, in the bounded "
                    + "SQL-boolean grammar. Required when a group key reads a nullable input column: a NULL "
                    + "key cannot be represented in the target (the group keys are its ordering_key, which "
                    + "cannot be nullable), so declaring a filter is how a caller states that the NULLs are "
                    + "accounted for — e.g. `col IS NOT NULL`.", example = "success = true")
            String filter,

            @Schema(description = "Group keys, in order; their output names must equal the target's "
                    + "ordering_key (`aggregate` only). Optional: omit it and the keys are derived from "
                    + "the target's ordering_key, when every key column is an input column of the same "
                    + "name. The derived keys are expanded into the stored declaration and echoed back.")
            @JsonProperty("group_by") @Valid List<GroupKeyRequest> groupBy,

            @Schema(description = "Measures: one aggregate function over one column each (`aggregate` form mode "
                    + "only). Required by the time a form-mode pipeline is armed; a disabled draft may omit it.")
            @Valid List<MeasureRequest> measures,

            @Schema(description = "An aggregate declaration written as one SQL statement (`aggregate` only), in place of `group_by`, "
                    + "`measures` and `filter`; stored and returned verbatim. Rules: exactly one "
                    + "`INSERT INTO <target> (<col>, …) SELECT …` naming every target column except `_updated_at`, "
                    + "mapped to the select list by position; read the input once, enrichment columns as "
                    + "`\"<enrichment>.<column>\"` (double quotes; no backticks), no JOIN, UNION, subquery, `SELECT *` "
                    + "or `FINAL`; `GROUP BY` exactly the target's ordering_key, each key non-nullable (wrap a nullable "
                    + "one in `coalesce(key, <default>)`), at most one time bucket and of step 1, buckets computed in "
                    + "UTC; `count(*)`, not `count()`, and `sum(CASE WHEN … THEN 1 ELSE 0 END)`, not `countIf` or "
                    + "`FILTER (WHERE …)`; no HAVING, DISTINCT, ORDER BY, LIMIT, window functions, non-deterministic "
                    + "functions, `SETTINGS` or `FORMAT`. ADAS adds the incremental window itself. Must not be blank.",
                    example = "INSERT INTO usage_daily (day, deployment, tokens) SELECT date_trunc('day', request_time), "
                            + "deployment, sum(total_tokens) FROM dial_usage_log GROUP BY 1, 2")
            String sql,

            @Schema(description = "Trigger. `schedule` for `aggregate`; `schedule` or `group` for `enrich`. "
                    + "Omitting it, or its `kind`, on an `aggregate` pipeline means `schedule`. For `enrich`, "
                    + "`kind` is required by the time the pipeline is armed, and an `enrich` `schedule` trigger "
                    + "that omits `cron` is stored with every minute at a second derived from the pipeline "
                    + "name; an `aggregate` pipeline's `cron` is required by the time it is armed. A disabled "
                    + "draft may omit them.")
            @Valid TriggerRequest trigger,

            @Schema(description = "The transform this pipeline runs (`enrich` only): the grammar selector "
                    + "and, dispatched on it, everything needed to produce a value for each target column. "
                    + "Required by the time the pipeline is armed; a disabled draft may omit it.")
            @Valid CreateTransformRequest transform,

            @Schema(description = "Execution knobs the runner applies (`enrich` only); every member "
                    + "optional, and an omitted one means the runner's own configured default")
            @Valid AdvancedRequest advanced,

            @Schema(description = "Whether to arm the pipeline on registration; absent means `false` for "
                    + "either kind. For `enrich`, `true` arms it and runs the declaration gate over this body. "
                    + "For `aggregate`, `true` is refused naming the preview endpoint - its disabled state is "
                    + "the dry-run gate - while `false` and an absent value are accepted.")
            Boolean enabled) {}

    /**
     * The transform an {@code enrich} pipeline runs, as authored on {@code POST}: the grammar selector and,
     * dispatched on it, everything needed to produce a value for each target column the pipeline writes.
     *
     * <p><b>One nested block rather than six flat members</b>, and the reason is a collision rather than
     * taste. {@code outputs} already exists at the top level of a pipeline <em>read</em>, where it is the
     * <em>derived</em> mapping — an ordered array, one entry per mapped target column. The authored
     * outputs are an object keyed by target column name. Flattening would put an array and an object under
     * one member name in two projections of the same object, which no typed client or OpenAPI schema can
     * model and which would let a read-modify-write client on {@code view=compiled} post the derived array
     * back as the authored map. Nesting removes the collision by construction, and keeps
     * {@code transform.type} reading as what it is — the grammar selector <em>inside</em> the transform,
     * rather than a second discriminator beside {@code kind}.
     *
     * <p>The block is optional as a whole, which is what makes cross-kind rejection cheap to state: an
     * {@code aggregate} declaration carrying {@code transform.model} is refused with 422 naming
     * {@code transform.model}, through the existing nested-member path.
     *
     * <p><b>{@code output_vars} is deliberately absent from this record</b> — that is what makes it an
     * unknown member on a {@code POST} and therefore a 400 naming the field, exactly as {@code cadence} is
     * refused. A new declaration authors {@code outputs}; the legacy pair exists to be carried by the fold
     * and patched back, never to start from. See {@link PatchTransformRequest}, which accepts it.
     *
     * <p><b>{@code type} carries no {@code @NotNull}, and its absence is refused by the declaration gate
     * instead.</b> It is still required — it selects the <em>grammar</em> of an {@code outputs} value, and
     * both grammars are "a string" on the wire, so a {@code sql} transform whose {@code type} went missing
     * would have its expressions silently read as prose and composed into a response schema; there is no
     * safe inference in either direction. What a bean-level annotation gets wrong is <em>when</em> it
     * fires. {@code @Valid} cascades into this block before the mapper's cross-kind dispatch runs, so an
     * {@code aggregate} declaration that carried a {@code transform} at all — the mistake this block's
     * optionality exists to name — was answered "type must not be null", telling an aggregate author to
     * <em>add</em> a member of a block their kind may not declare. Leaving it to the gate puts the two
     * refusals in the order that diagnoses each correctly: {@code transform.model} belongs to the
     * {@code enrich} kind, and on an {@code enrich} pipeline {@code transform.type} is required. Both are
     * 422s naming the member, which is what the rest of this surface answers.
     *
     * <p><b>Order is load-bearing</b> in both maps. {@code outputs}' declaration order becomes the composed
     * schema's property order, which under {@code strict: true} is the model's generation order — so a
     * field whose prose tells it to derive itself from its siblings must be declarable after them. And
     * {@code inputs}' keys drive substitution, and at group grain their order is each member object's field
     * order. Jackson preserves a parsed object's key order in a {@link LinkedHashMap}, and declaring both
     * parameters as one is what pins that at the boundary.
     */
    public record CreateTransformRequest(
            @Schema(description = "Grammar selector: `llm` (a model call) or `sql` (a deterministic "
                    + "derivation computed in the database)", example = "llm")
            TransformType type,

            @Schema(description = "Request-shaping preset; optional, defaults to chat_completion. Forbidden "
                    + "for `sql`.", example = "chat_completion")
            TransformPreset preset,

            @Schema(description = "DIAL deployment/model the transform calls; required for `llm`, forbidden "
                    + "for `sql`", example = "gemini-2.5-flash-lite")
            String model,

            @Schema(description = "Preset parameters (temperature, max_tokens, …); forbidden for `sql`")
            Map<String, Object> params,

            @Schema(description = "The JSON request body to render, with {{placeholder}} substitutions. Not "
                    + "a bare prompt: it is parsed as a JSON object and params plus response_format are "
                    + "overlaid onto it. Forbidden for `sql`.")
            @JsonProperty("request_template") String requestTemplate,

            @Schema(description = "What the transform receives, keyed by variable name. Each value is a "
                    + "column of the read source's entity (a bare string) or an object {column} / "
                    + "{jsonata}. At row grain each key must appear as a {{placeholder}} in "
                    + "`request_template`; at group grain the keys become fields of each member object "
                    + "inside {{members}} and appear in no placeholder. Refused for `sql`, which renders no "
                    + "request.")
            @JsonProperty("inputs")
            @JsonDeserialize(contentUsing = VarSpecDeserializer.class)
            LinkedHashMap<String, VarSpec> inputs,

            @Schema(description = "What this transform produces, keyed by the target column that receives "
                    + "it. Declaration order is the composed schema's property order, so it is preserved. "
                    + "For `llm` a value is prose (a bare string) or an object {prose?, values?, jsonata?}, "
                    + "with prose defaulting to the target column's description; for `sql` it is the scalar "
                    + "expression as a bare string.")
            @JsonProperty("outputs")
            @JsonDeserialize(contentUsing = OutputSpecDeserializer.class)
            LinkedHashMap<String, OutputSpec> outputs,

            @Schema(description = "A response schema to hold the model to verbatim, instead of the one "
                    + "composed from `outputs` and the target's columns. While it is present it wins, so "
                    + "clearing it on a later PATCH is what switches a declaration over to composition.")
            @JsonProperty("response_schema") Map<String, Object> responseSchema) {}

    /**
     * The transform as authored on {@code PATCH}: {@link CreateTransformRequest} plus {@code output_vars}.
     *
     * <p><b>Two records for two verbs, so the verb-specific rule is enforced by the binding rather than by
     * hand.</b> {@code output_vars} is the pre-{@code outputs} output shape, which the fold migration
     * carried verbatim onto the pipelines that had it. It has to be accepted here, because otherwise a
     * {@code GET view=source} → {@code PATCH} round trip on such a pipeline drops it and leaves a
     * declaration with no outputs at all — refused if the gate is strict, and a silently rewritten prompt
     * if it is not. Round-trip fidelity is the requirement; accepting it is how that is met. A declaration
     * carrying <em>both</em> shapes is refused with 422 on either verb.
     */
    public record PatchTransformRequest(
            @Schema(description = "Grammar selector: `llm` or `sql`", example = "llm")
            TransformType type,

            @Schema(description = "Request-shaping preset; forbidden for `sql`", example = "chat_completion")
            TransformPreset preset,

            @Schema(description = "DIAL deployment/model; required for `llm`, forbidden for `sql`",
                    example = "gemini-2.5-flash-lite")
            String model,

            @Schema(description = "Preset parameters; forbidden for `sql`")
            Map<String, Object> params,

            @Schema(description = "The JSON request body to render; forbidden for `sql`")
            @JsonProperty("request_template") String requestTemplate,

            @Schema(description = "Replacement transform inputs, keyed by variable name; refused for `sql`")
            @JsonProperty("inputs")
            @JsonDeserialize(contentUsing = VarSpecDeserializer.class)
            LinkedHashMap<String, VarSpec> inputs,

            @Schema(description = "Replacement outputs, keyed by the target column that receives each one, "
                    + "in declaration order")
            @JsonProperty("outputs")
            @JsonDeserialize(contentUsing = OutputSpecDeserializer.class)
            LinkedHashMap<String, OutputSpec> outputs,

            @Schema(description = "The pre-`outputs` output shape, accepted so a folded legacy declaration "
                    + "round-trips. Refused on POST, and refused together with `outputs` on either verb.")
            @JsonProperty("output_vars") @Valid List<TransformVarDto> outputVars,

            @Schema(description = "A stored response schema, which wins over composition while present; "
                    + "omitting it on a declaration patch clears it")
            @JsonProperty("response_schema") Map<String, Object> responseSchema) {}

    /**
     * The execution knobs, nested rather than flat so four runner hints stay out of the declaration's
     * required-looking surface in Swagger and in the MCP tool schema.
     *
     * <p><b>These names are the whole vocabulary — both projections serve this block, under these
     * spellings.</b> An earlier draft kept the former ones ({@code cadence}, {@code batch_scan_limit},
     * {@code batch_chunk}, {@code sampling}) on the compiled projection so an Enrichment Runner deployed
     * before this change could keep reading it; that was withdrawn, because it bought one rolling-deploy
     * window at the price of every knob having two names forever. The runner ships in the same window and
     * reads these. Three of the four are renamed because they were ambiguous or actively misleading
     * ({@code sampling} read as a percentage above 1); {@code rate_rpm} is neither, so it keeps its name.
     * {@code scan_every} (once {@code cadence}) is retired outright: a row-grain pipeline's only clock is its
     * {@code trigger.cron}, and a group pipeline's mark scan runs on the runner's own configured cadence.
     *
     * <p>The <em>stored</em> spec is a third thing again: it writes the new names and reads either, through
     * a {@code @JsonAlias} per knob, so no row is migrated. See {@code EnrichSpec}.
     *
     * <p>Every member is null-means-unset. No default is written at this layer: a default here would be
     * indistinguishable from an operator's deliberate choice and would silently override the runner's own
     * configuration.
     */
    public record AdvancedRequest(
            @Schema(description = "Upper bound on rows one scan tick may claim (was `batch_scan_limit`)",
                    example = "1000")
            @JsonProperty("rows_per_scan") Integer rowsPerScan,

            @Schema(description = "How many rows the runner evaluates per work item (was `batch_chunk`)",
                    example = "1")
            @JsonProperty("rows_per_call") Integer rowsPerCall,

            @Schema(description = "Per-pipeline model call ceiling per minute", example = "75")
            @JsonProperty("rate_rpm") Integer rateRpm,

            @Schema(description = "Fraction of eligible rows to evaluate, strictly in (0, 1]. A value above "
                    + "1 is refused rather than read as a percentage; 0 is refused naming `enabled: false`, "
                    + "which is how a pipeline that evaluates nothing is declared.", example = "0.1")
            @JsonProperty("sample_fraction") Double sampleFraction) {}

    /**
     * Body of {@code PATCH /v1/pipelines/{name}}. Every member is optional: sending only {@code enabled}
     * flips the pipeline, while sending declaration members re-declares it.
     *
     * <p>A declaration patch <b>replaces</b> the stored declaration: what it supplies becomes the
     * declaration and what it omits is cleared — except {@code target}, which is kept when omitted. One that
     * leaves the pipeline <b>enabled</b> must supply the whole declaration — it is about to produce rows, and
     * is validated as one object — while one that leaves it disabled is a draft save and may supply any
     * subset, unless the stored declaration is complete, which a draft save may replace whole but not leave
     * incomplete. Whether a declaration patch is permitted while the
     * pipeline is enabled depends on the kind: refused for {@code aggregate}, whose target holds windowed
     * results replaced by key; permitted for {@code enrich}, whose target is a grain-keyed replace-by-grain
     * table where new rows computing the new way and old rows keeping their values is already how it works.
     *
     * <p>{@code name} and {@code kind} are <b>accepted but not patchable</b>: each must equal what the
     * pipeline already has, and a different value is refused. They are part of the vocabulary rather than
     * omitted from it because a full-body patch is the shape callers actually send — it is what the retired
     * {@code PUT /v1/rules/&#123;id&#125;} took, and what any read-modify-write client produces — and since
     * request bodies reject unknown fields, omitting them from the record would turn that shape into a 400
     * naming a field the caller had every reason to include.
     */
    public record PatchPipelineRequest(
            @Schema(description = "Echo of the pipeline's own name. It SHALL equal the `{name}` in the path: "
                    + "a pipeline's name is its identity, so a different value is refused rather than "
                    + "renaming anything.", example = "chat-topic-live")
            String name,

            @Schema(description = "Echo of the pipeline's kind. Immutable — a different transform over the "
                    + "same target is a different pipeline, not a patch of this one — so a different value "
                    + "is refused.", example = "enrich")
            PipelineKind kind,

            @Schema(description = "Enable or disable the pipeline. Enabling runs the declaration gate over "
                    + "the stored declaration (or over this patch's, when it re-declares) for either kind, and "
                    + "for `aggregate` also arms its ADAS-driven schedule.")
            Boolean enabled,

            @Schema(description = "Replacement target (declaration change). Omitted, the stored target is "
                    + "kept; a different value repoints the pipeline. The stored value, sent with no other "
                    + "declaration member, is an echo and re-declares nothing.")
            String target,

            @Schema(description = "Replacement input list (declaration change). Required for a form-mode "
                    + "`aggregate` when the patch arms the pipeline; optional in SQL mode, where omitting it means the "
                    + "statement's FROM. For `enrich`, required on any declaration "
                    + "patch of a pipeline that declares its own read source, armed or not, unless that source is "
                    + "the target's parent, in which case omitting it keeps it — send `[]` to drop it and read the "
                    + "target's parent.")
            List<String> inputs,

            @Schema(description = "Replacement membership predicate (declaration change)")
            String filter,

            @Schema(description = "Replacement group keys (declaration change; `aggregate` only). Optional "
                    + "for the same reason as at registration: omitted, they are derived from the target's "
                    + "ordering_key. A pipeline registered without them stays patchable without them.")
            @JsonProperty("group_by") @Valid List<GroupKeyRequest> groupBy,

            @Schema(description = "Replacement measures (declaration change; `aggregate` form mode only). Required "
                    + "when the patch arms a form-mode pipeline; a draft save may omit it.")
            @Valid List<MeasureRequest> measures,

            @Schema(description = "Replacement SQL declaration (declaration change; `aggregate` only), in place of `group_by`, "
                    + "`measures` and `filter`; stored and returned verbatim. Rules: exactly one "
                    + "`INSERT INTO <target> (<col>, …) SELECT …` naming every target column except `_updated_at`, "
                    + "mapped to the select list by position; read the input once, enrichment columns as "
                    + "`\"<enrichment>.<column>\"` (double quotes; no backticks), no JOIN, UNION, subquery, `SELECT *` "
                    + "or `FINAL`; `GROUP BY` exactly the target's ordering_key, each key non-nullable (wrap a nullable "
                    + "one in `coalesce(key, <default>)`), at most one time bucket and of step 1, buckets computed in "
                    + "UTC; `count(*)`, not `count()`, and `sum(CASE WHEN … THEN 1 ELSE 0 END)`, not `countIf` or "
                    + "`FILTER (WHERE …)`; no HAVING, DISTINCT, ORDER BY, LIMIT, window functions, non-deterministic "
                    + "functions, `SETTINGS` or `FORMAT`. ADAS adds the incremental window itself. A declaration "
                    + "patch carrying none of `sql`, `group_by`, `measures` and `filter` keeps the stored statement; "
                    + "sending `sql` to an enabled pipeline is 409 (disable it first). Must not be blank.")
            String sql,

            @Schema(description = "Replacement trigger (declaration change). Required when the patch leaves "
                    + "the pipeline enabled; a draft save may omit it.")
            @Valid TriggerRequest trigger,

            @Schema(description = "Replacement transform (declaration change; `enrich` only). Required when "
                    + "the patch leaves the pipeline enabled; a draft save may omit it.")
            @Valid PatchTransformRequest transform,

            @Schema(description = "Replacement execution knobs (declaration change; `enrich` only)")
            @Valid AdvancedRequest advanced) {

        /**
         * Whether this patch re-declares the pipeline (as opposed to only flipping {@code enabled}).
         *
         * <p><b>Every declaration member has to be listed here, including the optional ones.</b> A member
         * this misses is not merely ignored: the patch takes the enable-only path and answers success
         * having discarded the part the caller cared about, which is the exact silent mis-answer strict
         * request parsing exists to prevent. That is why {@code advanced} is here even though it is not
         * required by {@code requireComplete} — optional at registration and invisible to a patch are
         * different things.
         *
         * <p>{@code transform} covers the transform's inputs too, since they live inside it as
         * {@code transform.inputs}: a patch that rebinds only the inputs must take the declaration path
         * rather than be read as an enable-only patch that discards them.
         *
         * <p>A {@code target} equal to the stored one does not count on its own, for the reason {@code name}
         * and {@code kind} do not: it is an echo of what the pipeline already has. Counting it would turn
         * {@code {"target": <same>, "enabled": true}} into a declaration patch that clears every other member
         * — on a draft, a 200 that erased the declaration. With another member present it is simply part of
         * the declaration, and the target is sticky either way.
         *
         * <p>A blank {@code target} does not count either: the service reads blank as omitted (sticky), so
         * counting it would turn {@code {"target": ""}} into the same declaration-clearing patch. The echo is
         * compared trimmed, matching the trimmed value the mapper passes on.
         *
         * @param storedTarget the pipeline's current target, against which an echoed {@code target} is read
         */
        public boolean hasDeclaration(String storedTarget) {
            return (StringUtils.isNotBlank(target) && !target.trim().equals(storedTarget)) || inputs != null || filter != null
                    || groupBy != null || measures != null || sql != null || trigger != null
                    || transform != null || advanced != null;
        }
    }

    /**
     * Optional body of {@code POST /v1/pipelines/{name}/preview}, which narrows the sample an {@code enrich}
     * preview takes. Every member is optional and the whole body may be omitted; an {@code aggregate} preview
     * accepts none of them, because its sample is deliberately unwindowed.
     *
     * <p>The two halves are mutually exclusive by the pipeline's grain rather than by shape, and each is
     * <b>refused</b> on the wrong grain rather than ignored: {@code group_key} on a row-grain pipeline, or a
     * window on a group one, means the caller has the wrong pipeline in mind, and silently dropping either would
     * answer a question nobody asked. There is no {@code cursor}: the sample is newest first, so a seek-past
     * cursor has no meaning, and one sent is an unknown member the strict request mapper refuses with a 400.
     */
    public record PreviewRequest(
            @Schema(description = "The group key value to sample, for a `group`-triggered `enrich` pipeline. "
                    + "Optional: without it the preview samples the group of the newest matching input row with "
                    + "a non-empty key, and reports the key it chose as `group_key`.",
                    example = "chat-42")
            @JsonProperty("group_key") String groupKey,

            @Schema(description = "Exclusive lower bound on the input's version column, to sample a "
                    + "particular stretch of the input (row-grain and Class-1 `enrich` pipelines only)",
                    example = "2026-07-01T00:00:00Z")
            Instant from,

            @Schema(description = "Inclusive upper bound on the input's version column; always clamped by "
                    + "the safety-lag horizon (row-grain and Class-1 `enrich` pipelines only)", example = "2026-08-01T00:00:00Z")
            Instant to) {

        /**
         * Whether the caller asked for anything at all — the aggregate arm accepts nothing.
         *
         * <p>{@code hidden} because springdoc reads this as a bean property and would publish
         * {@code empty} as a member of the request schema — one the strict request mapper answers with a
         * 400 naming it, so the document would advertise a field the API refuses. It stays a plain
         * accessor rather than being {@code @JsonIgnore}d for the same reason: ignoring it would turn that
         * 400 into a silent accept.
         */
        @Schema(hidden = true)
        public boolean isEmpty() {
            return groupKey == null && from == null && to == null;
        }
    }

    /**
     * One group key: exactly one of {@code column} (group by it as-is) or {@code trunc} (a time bucket
     * over a date/timestamp column: fixed-width for hour, day and week, a calendar month for month).
     */
    public record GroupKeyRequest(
            @Schema(description = "A declared input column, grouped as-is", example = "deployment")
            String column,

            @Schema(description = "A time bucket over a declared date/timestamp input column")
            @Valid TruncRequest trunc,

            @Schema(description = "The target column this key writes; defaults to `column`, required for "
                    + "a `trunc`", example = "day")
            String as) {}

    /** A time bucket: fixed-width for `hour`, `day` and `week`, a calendar month for `month`. */
    public record TruncRequest(
            @Schema(description = "Declared date/timestamp input column to bucket", example = "request_time")
            @NotBlank String column,

            @Schema(description = "Bucket width (week starts Monday, ISO)", example = "day")
            @NotNull TruncUnit unit) {}

    /**
     * One measure: a single aggregate function over a single input column, optionally scoped to a subset of
     * the group's rows by {@code where} and optionally reading only that column's distinct values.
     */
    public record MeasureRequest(
            @Schema(description = "The target column this measure writes", example = "call_count")
            @NotBlank String name,

            @Schema(description = "Aggregate function from the shared query catalog's aggregate group "
                    + "(count, sum, avg, min, max, group_uniq_array). An aggregate needing more than one "
                    + "argument is rejected, since a measure carries one column.", example = "sum")
            @NotBlank String fn,

            @Schema(description = "Input column to aggregate; omit only for `count`", example = "total_price")
            String column,

            @Schema(description = "Optional row-membership predicate scoping this measure to part of its "
                    + "group, in the same bounded SQL-boolean grammar as the pipeline `filter`. Use it when "
                    + "one measure must read fewer rows than the rest — e.g. summing a whole-chain cost only "
                    + "over the rows that start a chain. A measure carrying a `where` can yield NULL for a "
                    + "group in which nothing matches, so its target column must be nullable.",
                    example = "parent_deployment IS NULL")
            String where,

            @Schema(description = "Deduplicate the input column's values before the function is applied, so "
                    + "the measure aggregates the group's distinct values rather than its rows. Accepted only "
                    + "for an aggregate the catalog marks `distinct_supported` (count, sum, avg) and rejected "
                    + "for the rest; requires `column`, since a column-less `count` is a row count with no "
                    + "value to deduplicate. Composes with `where`: the predicate chooses the rows, this "
                    + "deduplicates the values on them. Use it to count the entities in a group whose grain is "
                    + "coarser than the input's row grain — a `where` cannot express that, because which row "
                    + "represents a distinct value is a property of the group, not of the row. A distinct "
                    + "result is exact at the grain it is stored at but must not be summed across stored rows.",
                    example = "false")
            // A Boolean, not a primitive, and with no null-coercing setter: absent must stay absent so the
            // RESPONSE can omit it. `distinct: false` was echoed on 80 of the 87 live measures, repeating a
            // default often enough to be the single most voluminous member of the whole registry read. The
            // reading is unchanged in both directions — null means false inbound, and the mapper normalises
            // false to null outbound — so a declaration that omits it compiles exactly as before, and one
            // that states `false` explicitly is still accepted.
            Boolean distinct) {}

    /**
     * The trigger: how a pipeline fires, and at which grain.
     *
     * <p>{@code kind} is a raw string rather than the {@code TriggerKind} enum on purpose, and the same
     * choice {@code kind} on the pipeline itself makes: an unrecognised value is then a <b>422 governance
     * failure naming the alternatives</b> rather than the generic 400 an unbindable enum member produces.
     *
     * <p>{@code group_by} lives here rather than at the top level because the group key is a property of
     * the trigger, not of the transform — and because the name collides: an {@code aggregate} pipeline's
     * top-level {@code group_by} is its rollup grain, a list of key parts.
     */
    public record TriggerRequest(
            @Schema(description = "`schedule` (row grain, cron-driven; the only kind an `aggregate` pipeline "
                    + "may use) or `group` (one firing per group key). `on_ingest` is retired and refused.",
                    example = "schedule")
            String kind,

            @Schema(description = "Spring cron expression (6 fields, UTC); only valid with `schedule`. "
                    + "Required for an `aggregate` pipeline; an `enrich` pipeline that omits it runs every "
                    + "minute at a second derived from its name, and the resolved cron is stored and echoed",
                    example = "0 */10 * * * *")
            String cron,

            @Schema(description = "Group key; required for and only valid with `group`, and must equal the "
                    + "target enrichment's grain key", example = "chat_id")
            @JsonProperty("group_by") String groupBy,

            @Schema(description = "Readiness declaration; required for and only valid with `group`")
            @JsonProperty("ready_when") @Valid ReadyWhenDto readyWhen,

            @Schema(description = "Member-selection policy; optional, and only valid with `group`. Omit to "
                    + "leave the group assembly's default policy (the newest members by the source's total "
                    + "order) in place.")
            @JsonProperty("member_select") @Valid MemberSelectDto memberSelect) {}
}
