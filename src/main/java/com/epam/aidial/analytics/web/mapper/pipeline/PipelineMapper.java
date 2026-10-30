package com.epam.aidial.analytics.web.mapper.pipeline;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.InputBinding;
import com.epam.aidial.analytics.service.enrichment.model.MemberSelect;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.enrichment.model.ReadyWhen;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TransformOutput;
import com.epam.aidial.analytics.service.enrichment.model.TransformPreset;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TransformVar;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineFreshnessDiagnostics;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.pipeline.model.GroupKey;
import com.epam.aidial.analytics.service.pipeline.model.Measure;
import com.epam.aidial.analytics.service.pipeline.model.PipelineRegistration;
import com.epam.aidial.analytics.service.pipeline.model.PipelineSpec;
import com.epam.aidial.analytics.service.pipeline.preview.PipelinePreview;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.enrichment.MemberSelectDto;
import com.epam.aidial.analytics.web.dto.enrichment.OutputSpec;
import com.epam.aidial.analytics.web.dto.enrichment.OutputSpecBody;
import com.epam.aidial.analytics.web.dto.enrichment.PipelineOutputDto;
import com.epam.aidial.analytics.web.dto.enrichment.ReadyWhenDto;
import com.epam.aidial.analytics.web.dto.enrichment.TransformVarDto;
import com.epam.aidial.analytics.web.dto.enrichment.VarSpec;
import com.epam.aidial.analytics.web.dto.enrichment.VarSpecBody;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineDto;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.AdvancedRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.CreatePipelineRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.CreateTransformRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.GroupKeyRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.MeasureRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.PatchPipelineRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.PatchTransformRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.TriggerRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineResponses.PipelineListDto;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineResponses.PreviewColumnDto;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineResponses.PreviewDto;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineResponses.PreviewNoteDto;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineView;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueMappingStrategy;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Maps between the pipeline control-plane DTOs and the registry domain, for both kinds.
 *
 * <p>An abstract MapStruct mapper rather than an interface: the leaf declaration entries line up by name
 * in both directions — {@link GroupKey} and {@link Measure} with the nested {@code trunc} bucket,
 * {@link InputBinding}, {@link OutputBinding}, {@link ReadyWhen} and {@link MemberSelect} — so MapStruct
 * generates that field copying, and a null entry list maps to an empty one
 * ({@link NullValueMappingStrategy#RETURN_DEFAULT}), which carries the "absent means none" reading.
 * The top-level conversions stay hand-written because they are not field copying: the request
 * <em>flattens</em> into a service-layer registration record while rejecting shapes the registry cannot
 * serve, and the response composes a derived, nested read-only state view.
 *
 * <h2>Cross-kind rejection lives here</h2>
 *
 * <p>One flat request shape means a caller can send any field to any kind and the binding will accept it.
 * {@link #requireKindFields} is what makes that safe, and it is the <b>only</b> thing that does: a field
 * ignored rather than refused reads back absent from the stored pipeline, so a caller who mistook one
 * kind's vocabulary for another's would be told their declaration was accepted while the part they cared
 * about was discarded.
 *
 * <p>One enum-shaped member arrives as a raw string and is parsed here rather than bound by Jackson —
 * {@code trigger.kind} — so an unrecognised value is a <b>422 naming the alternatives</b> rather than the
 * generic 400 an unbindable enum member produces.
 */
@Mapper(componentModel = "spring",
        nullValueIterableMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
public abstract class PipelineMapper {

    /**
     * The one aggregate function whose omitted {@code column} does not default to the measure's name: a
     * column-less {@code count} is a row count, which no column name can express.
     */
    private static final String COUNT_FN = "count";

    /** Wire members that belong to the aggregate transform alone. */
    private static final List<String> AGGREGATE_ONLY = List.of("group_by", "measures", "sql");

    /**
     * The form-mode members of an {@code aggregate} declaration, each exclusive with {@code sql}. {@code filter}
     * is listed although it is not aggregate-only: it is shared with {@code enrich}, so the cross-kind check
     * never sees it, and a statement carries its own {@code WHERE}.
     */
    private static final List<String> FORM_MODE = List.of("group_by", "measures", "filter");

    /**
     * Wire members that belong to the enrich transform alone.
     *
     * <p>The nested members are listed <b>individually</b> as {@code transform.<member>} and
     * {@code advanced.<member>}, not as the blocks holding them. This list's whole purpose is to tell a
     * caller <em>which field</em> they misplaced — degrading "`transform.model` belongs to the enrich kind"
     * to "`transform` belongs to the enrich kind" throws away the half that is actionable.
     *
     * <p>The retired spellings are deliberately <b>absent</b> from this list: {@code cadence},
     * {@code evaluator_name}, {@code evaluator_version} and the flat {@code vars} are no longer members of
     * any request record, so each is refused at the binding with a 400 naming it on <em>either</em> kind,
     * before the cross-kind check is reached. Nothing has to be double-listed, and there is no window in
     * which a retired spelling stops being refused on the kind it never belonged to.
     */
    private static final List<String> ENRICH_ONLY = List.of(
            "transform.type", "transform.preset", "transform.model", "transform.params",
            "transform.request_template", "transform.inputs", "transform.outputs",
            "transform.output_vars", "transform.response_schema",
            "advanced.rows_per_scan", "advanced.rows_per_call", "advanced.rate_rpm", "advanced.sample_fraction");

    /**
     * The retired row-grain trigger kind, refused by name rather than as an unknown value so the author is told
     * what replaced it: {@code on_ingest} differed from {@code schedule} only in its clock.
     */
    private static final String RETIRED_TRIGGER_KIND = "on_ingest";

    /**
     * Field injection rather than the constructor, because MapStruct generates the implementation's
     * constructor and cannot be handed extra arguments.
     */
    @Autowired
    private PipelineSpecCodec specCodec;

    public PipelineRegistration toRegistration(CreatePipelineRequest request) {
        requireKindFields(request.kind(), aggregateFieldsOf(request), enrichFieldsOf(request));
        requireOneAggregateMode(request.kind(), request.sql(), request.groupBy(), request.measures(), request.filter());
        requireEnabledNotArmedAtRegistration(request);
        final TriggerRequest trigger = request.trigger();
        return new PipelineRegistration(
                request.name(),
                request.kind(),
                request.target(),
                singleInput(request.inputs()),
                request.filter(),
                toSpec(request.kind(), request.groupBy(), request.measures(), request.sql(),
                        transformFieldsOf(request.transform()), request.advanced()),
                triggerKindOf(trigger),
                trigger == null ? null : trigger.cron(),
                trigger == null ? null : trigger.groupBy(),
                trigger == null ? null : toReadyWhen(trigger.readyWhen()),
                trigger == null ? null : toMemberSelect(trigger.memberSelect()),
                Boolean.TRUE.equals(request.enabled()));
    }

    /**
     * The registration a catalog import creates from a bundled pipeline: the {@code POST} path's mapping over
     * the bundle's declaration, with the transform read through the {@code PATCH} shape so a folded legacy
     * declaration's {@code output_vars} pass through unchanged rather than being converted to
     * {@code outputs} (which would change the response format the model is given). Cross-kind members are
     * refused as on {@code POST}, and so is an {@code sql} beside a form-mode member (a version-1 bundle carries no
     * {@code sql}, so every aggregate in it maps in form mode). Always disabled, whatever the bundle recorded: arming is a separate
     * {@code PATCH}, which runs the arm-time gate.
     */
    public PipelineRegistration toRegistration(BundlePipeline bundled) {
        final BundlePipeline.Declaration declaration = bundled.pipeline();
        final TransformFields transform = transformFieldsOf(declaration.transform());
        requireKindFields(declaration.kind(), present(AGGREGATE_ONLY, declaration.groupBy(), declaration.measures(), declaration.sql()),
                enrichFields(transform, declaration.advanced()));
        requireOneAggregateMode(declaration.kind(), declaration.sql(), declaration.groupBy(), declaration.measures(),
                declaration.filter());
        final TriggerRequest trigger = declaration.trigger();
        return new PipelineRegistration(
                declaration.name(),
                declaration.kind(),
                declaration.target(),
                singleInput(declaration.inputs()),
                declaration.filter(),
                toSpec(declaration.kind(), declaration.groupBy(), declaration.measures(), declaration.sql(), transform,
                        declaration.advanced()),
                triggerKindOf(trigger),
                trigger == null ? null : trigger.cron(),
                trigger == null ? null : trigger.groupBy(),
                trigger == null ? null : toReadyWhen(trigger.readyWhen()),
                trigger == null ? null : toMemberSelect(trigger.memberSelect()),
                false);
    }

    /**
     * The declaration half of a patch, or null when the patch only flips {@code enabled}. A patch that
     * re-declares <b>and leaves the pipeline armed</b> must supply the whole declaration: a declaration about
     * to produce rows is validated as one object. One that leaves it disabled is a draft save and may supply
     * any subset of an incomplete declaration (the service refuses one that would leave a complete
     * declaration incomplete) — the patch still replaces rather than merges, so every stored state is one a caller
     * described whole.
     *
     * <p>{@code kind} is left null on the registration record — it is not patchable, so the service carries
     * the stored one over rather than the wire restating it — but the <em>stored</em> pipeline is passed in
     * here, because cross-kind rejection, completeness and the identity echo all depend on its kind. Its
     * read source matters for the same reason: whether omitting {@code inputs} is legal depends on whether
     * this pipeline has one to lose, and whether losing it would repoint anything. So does its target, because
     * a body that only echoes it back is not a declaration (see
     * {@link PatchPipelineRequest#hasDeclaration(String)}). And whether the pipeline is armed <b>after</b>
     * this patch — the patch's own {@code enabled} when it carries one, else the stored flag — is what scopes
     * the completeness requirement.
     *
     * <p>{@code target} is passed on trimmed, or null when blank (the service then keeps the stored one), so
     * the value the echo check compared is the value the service repoints to.
     *
     * <p>{@code targetParent} supplies the parent source of the target this patch leaves the pipeline writing —
     * the one it names, else the stored one — or null when that table has none. It is what an omitted
     * {@code inputs} would follow, so it decides whether the omission repoints anything (see
     * {@link #patchedInput}). A supplier, because only that one path needs it: it is asked at most once, and
     * not at all for any other patch.
     */
    public PipelineRegistration toRegistration(PipelineEntity stored, PatchPipelineRequest request,
            Supplier<String> targetParent) {
        final String name = stored.getName();
        final PipelineKind kind = stored.getKind();
        final boolean armed = request.enabled() != null ? request.enabled() : stored.isEnabled();
        requireIdentityEcho(name, kind, request);
        if (!request.hasDeclaration(stored.getTarget())) {
            return null;
        }
        requireKindFields(kind, aggregateFieldsOf(request), enrichFieldsOf(request));
        requireOneAggregateMode(kind, request.sql(), request.groupBy(), request.measures(), request.filter());
        final String sql = kind == PipelineKind.AGGREGATE ? patchedSql(stored, request) : null;
        // A declaration patch of an ENABLED aggregate is a state conflict whatever it carries, so it is left to the
        // service's 409 rather than answered 422 for a member the caller would only have to add to meet the 409.
        final boolean editOfEnabledAggregate = kind == PipelineKind.AGGREGATE && stored.isEnabled();
        if (armed && !editOfEnabledAggregate) {
            requireComplete(kind, request, sql);
        }
        // Unconditional, unlike completeness: an omitted `inputs` on a pipeline that declares its read
        // source is a repoint the caller never wrote, not an unfinished member — a draft included.
        final String input = kind == PipelineKind.ENRICH
                ? patchedInput(request, stored.getInputTable(), targetParent)
                : singleInput(request.inputs());
        final TriggerRequest trigger = request.trigger();
        return new PipelineRegistration(
                name,
                null,
                StringUtils.trimToNull(request.target()),
                input,
                request.filter(),
                toSpec(kind, request.groupBy(), request.measures(), sql,
                        transformFieldsOf(request.transform()), request.advanced()),
                triggerKindOf(trigger),
                trigger == null ? null : trigger.cron(),
                trigger == null ? null : trigger.groupBy(),
                trigger == null ? null : toReadyWhen(trigger.readyWhen()),
                trigger == null ? null : toMemberSelect(trigger.memberSelect()),
                false);
    }

    /**
     * A stored pipeline as a catalog bundle entry: the {@code view=source} projection without its runtime
     * members ({@code generation}, {@code state}, {@code enabled}, timestamps, {@code notes}), so that
     * {@link #toRegistration(BundlePipeline)} recreates the same declaration. The transform's
     * {@code inputs} move from the response's object form to the request's, which accepts it unchanged.
     */
    public BundlePipeline toBundle(PipelineEntity pipeline) {
        // toDto(pipeline) is pinned to the SOURCE projection, which keeps `inputs` at the stored value; a
        // compiled projection would serve the resolved inputs and the round trip would no longer be identity.
        final PipelineDto dto = toDto(pipeline);
        return new BundlePipeline(new BundlePipeline.Declaration(
                dto.name(), dto.kind(), dto.target(), dto.inputs(), dto.filter(), dto.trigger(), dto.groupBy(),
                dto.measures(), dto.sql(), toPatchTransform(dto.transform()), dto.advanced()), pipeline.isEnabled());
    }

    private static PatchTransformRequest toPatchTransform(PipelineDto.PipelineTransformDto transform) {
        if (transform == null) {
            return null;
        }
        LinkedHashMap<String, VarSpec> inputs = null;
        if (transform.inputs() != null) {
            inputs = new LinkedHashMap<>();
            for (Map.Entry<String, VarSpecBody> entry : transform.inputs().entrySet()) {
                inputs.put(entry.getKey(), new VarSpec(null, entry.getValue()));
            }
        }
        return new PatchTransformRequest(transform.type(), transform.preset(), transform.model(), transform.params(),
                transform.requestTemplate(), inputs,
                transform.outputs() == null ? null : new LinkedHashMap<>(transform.outputs()),
                transform.outputVars(), transform.responseSchema());
    }

    /**
     * Maps a pipeline with no freshness report and no enrich resolution — for a response where the
     * enrichments it reads are not being described, and so nothing would be lost by omitting them.
     *
     * <p>Necessarily the {@code SOURCE} projection: with no resolution in hand there is no resolved
     * response schema, no grain key and no version column to serve, so a compiled projection here would be
     * one missing exactly the members that make it compiled. Answering a write with what was written is
     * also the more useful of the two.
     */
    public PipelineDto toDto(PipelineEntity pipeline) {
        return toDto(pipeline, null, PipelineFreshnessDiagnostics.Report.NONE, PipelineView.SOURCE);
    }

    /**
     * The full response for one pipeline, in the requested projection.
     *
     * <p><b>The two projections differ in resolution, not in naming.</b> {@code transform} and
     * {@code advanced} are the authored declaration and are served by both — which is what lets a
     * read-modify-write client patch the transform back unchanged. The derived member set served only by
     * {@code COMPILED} is exactly five: {@code response_schema}, {@code outputs}, {@code grain_key},
     * {@code version_column} and the resolved {@code inputs}. They are left null — and so omitted — on
     * {@code SOURCE}, and there is no member whose spelling depends on the projection.
     *
     * <p><b>Every one of the five is gated on the projection as well as on having something to serve.</b>
     * Gating only on {@code resolved != null} would make purity a caller obligation: it happens to hold
     * today because no caller resolves for {@code SOURCE}, so a resolution passed in for any other reason —
     * a freshness clamp, a write response that already had one in hand — would start leaking derived
     * members onto the authored projection, where a read-modify-write client posts them straight back.
     * The guard belongs here, once, rather than in each caller.
     *
     * @param resolved the enrich resolution (resolved input, grain key, version column, composed schema),
     *     or null for a kind that has none. Passed in rather than looked up here because resolving it
     *     costs Postgres round trips the aggregate path must not pay.
     */
    public PipelineDto toDto(PipelineEntity pipeline, ResolvedEnrichPipeline resolved,
            PipelineFreshnessDiagnostics.Report freshness, PipelineView view) {
        return toDto(pipeline, resolved, freshness, view, List.of());
    }

    /**
     * As above, carrying the advisory {@code notes} a write response adds.
     *
     * <p>An empty list is served as <b>absent</b> rather than as {@code []}, which is what keeps the notes a
     * property of the write: a read has none to report, and an empty array there would read as "analysed,
     * nothing to say" about a response that never analysed anything. See {@link PipelineDto#notes()}.
     */
    public PipelineDto toDto(PipelineEntity pipeline, ResolvedEnrichPipeline resolved,
            PipelineFreshnessDiagnostics.Report freshness, PipelineView view, List<String> notes) {
        final boolean enrich = pipeline.getKind() == PipelineKind.ENRICH;
        final AggregateSpec aggregate = enrich ? null : specOf(pipeline, AggregateSpec.class);
        final EnrichSpec enrichSpec = enrich ? specOf(pipeline, EnrichSpec.class) : null;
        final boolean compiled = view == PipelineView.COMPILED;
        final EnrichSpec compiledSpec = compiled ? enrichSpec : null;
        return new PipelineDto(
                pipeline.getName(),
                pipeline.getKind(),
                pipeline.getTarget(),
                inputsOf(pipeline, resolved, compiled),
                pipeline.getFilterSql(),
                toTriggerDto(pipeline),
                pipeline.isEnabled(),
                pipeline.getGeneration(),
                pipeline.getCreatedAt(),
                pipeline.getUpdatedAt(),
                aggregate == null ? null : absentIfEmpty(toGroupKeyDtos(aggregate.getGroupBy())),
                aggregate == null ? null : absentIfEmpty(toMeasureDtos(aggregate.getMeasures())),
                aggregate == null ? null : aggregate.getSql(),
                enrichSpec == null ? null : toTransformDto(enrichSpec, compiled),
                compiledSpec == null || resolved == null ? null : resolved.effectiveResponseSchema(),
                compiledSpec == null || resolved == null ? null : resolved.grainKey(),
                compiledSpec == null || resolved == null ? null : resolved.versionColumn(),
                compiledSpec == null ? null : toOutputDtos(compiledSpec),
                enrichSpec == null ? null : toAdvancedDto(enrichSpec),
                toStateDto(pipeline, freshness),
                notes == null || notes.isEmpty() ? null : List.copyOf(notes));
    }

    /**
     * The stored trigger, or null when the declaration carries none of it — a draft that has not authored a
     * trigger yet. Served absent rather than as {@code {}} for the reason {@link #absentIfEmpty} gives: the
     * next patch would post an empty object back as a declared trigger. An armed pipeline always has a
     * trigger kind, so the projection an enabled pipeline serves is unaffected.
     */
    private TriggerRequest toTriggerDto(PipelineEntity pipeline) {
        if (pipeline.getTriggerKind() == null && pipeline.getTriggerCron() == null
                && pipeline.getTriggerGroupBy() == null && pipeline.getReadyWhen() == null
                && pipeline.getMemberSelect() == null) {
            return null;
        }
        return new TriggerRequest(
                pipeline.getTriggerKind() == null ? null : codeOf(pipeline.getTriggerKind()),
                pipeline.getTriggerCron(),
                pipeline.getTriggerGroupBy(),
                toReadyWhenDto(pipeline.getReadyWhen()),
                toMemberSelectDto(pipeline.getMemberSelect()));
    }

    /**
     * An authored member the declaration does not carry is served <b>absent</b>, not as {@code []} — the
     * rule {@link #inputsOf} already applies to {@code inputs}. The authoring loop is read, edit, send whole,
     * and a patch replaces rather than merges, so a member a read invents as {@code []} is a member the next
     * patch stores as "declares none". Empty and undeclared are the same thing here, because a valid
     * declaration never carries zero group keys or zero measures: an empty one can only be unauthored.
     *
     * <p>Applied to {@code group_by} and {@code measures} only. {@code output_bindings} is derived rather
     * than authored and served on the compiled projection alone, so it round-trips nowhere.
     */
    private static <T> List<T> absentIfEmpty(List<T> members) {
        return members == null || members.isEmpty() ? null : members;
    }

    public PipelineListDto toListDto(List<PipelineEntity> pipelines,
            Map<String, PipelineFreshnessDiagnostics.Report> freshness, PipelineView view) {
        return new PipelineListDto(pipelines.stream()
                .map(pipeline -> toDto(pipeline, null, freshness.getOrDefault(
                        pipeline.getName(), PipelineFreshnessDiagnostics.Report.NONE), view))
                .toList());
    }

    /** The resolved list form — the runner's sync feed, where every ENRICH member is populated. */
    public PipelineListDto toResolvedListDto(List<ResolvedEnrichPipeline> resolved,
            Map<String, PipelineFreshnessDiagnostics.Report> freshness, PipelineView view) {
        return new PipelineListDto(resolved.stream()
                .map(entry -> toDto(entry.pipeline(), entry, freshness.getOrDefault(
                        entry.pipeline().getName(), PipelineFreshnessDiagnostics.Report.NONE), view))
                .toList());
    }

    /**
     * The authored transform as its response shape, served by <b>both</b> projections.
     *
     * <p>Serving it on {@code view=source} as well as {@code view=compiled} is what makes a
     * read-modify-write round trip faithful: a client reads the declaration, changes one member and sends
     * the whole transform back, and nothing it did not touch is lost. Before the fold that round trip
     * could not lose a prompt, because the prompt lived on an immutable evaluator version; now it can, so
     * the fidelity is a requirement rather than a convenience.
     *
     * <p><b>Three things are deliberately echoed exactly as stored rather than resolved.</b>
     *
     * <ul>
     *   <li>{@code response_schema} is the schema this declaration <em>stores</em>, absent when it stores
     *       none, and served on {@code view=source} <b>only</b>. The resolved one — stored, else composed —
     *       is the top-level {@code response_schema} on the compiled projection. Serving a composed schema
     *       inside this block would let a {@code view=compiled} client post it back as a stored one,
     *       pinning a prompt to a schema nobody authored; serving the <em>stored</em> one there would ship
     *       the same multi-kilobyte object twice in every feed entry, which is the duplication deleting the
     *       inlined {@code evaluator} object removed. The runner reads only the top-level member — its
     *       {@code TransformDto} does not model this one at all — so the omission costs it nothing, and the
     *       round trip that needs the authored copy happens on {@code source}.
     *   <li>An output's {@code prose} is echoed blank or absent where that is what was authored. The
     *       column-description default is applied at composition only: echoed here it would be sent back
     *       by the next {@code PATCH}, become authored, and silently stop tracking the column.
     *   <li>{@code output_vars} is echoed where a folded legacy declaration carries it, because otherwise
     *       a round trip would drop it and leave a declaration with no outputs at all.
     * </ul>
     *
     * <p>{@code inputs} is echoed in the object form always, even where the declaration used the
     * bare-string shorthand — the two are two spellings of one fact, and the request accepts the object
     * form. {@code outputs} is echoed <em>as authored</em>, because there the form is the type dispatch: a
     * {@code sql} output must be a bare expression string on the way in, so echoing it as an object would
     * produce a body the next {@code PATCH} refuses.
     *
     * @param compiled whether this is the {@code view=compiled} projection, which is the one member-level
     *     difference between the two: the authored {@code response_schema} is omitted there, because the
     *     resolved one is already served at the top level and the pair would otherwise duplicate a
     *     multi-kilobyte object in every entry of a feed the runner polls
     */
    private static PipelineDto.PipelineTransformDto toTransformDto(EnrichSpec spec, boolean compiled) {
        final EnrichSpec.TransformSpec transform = spec.getTransform();
        if (transform == null) {
            return null;
        }
        return new PipelineDto.PipelineTransformDto(
                transform.getType(),
                transform.getPreset(),
                transform.getModel(),
                transform.getParams() == null || transform.getParams().isEmpty()
                        ? null : transform.getParams(),
                transform.getRequestTemplate(),
                toVarsDto(spec.getInputBindings()),
                toAuthoredOutputsDto(transform),
                toVarDtos(transform.getOutputVars()),
                compiled ? null : transform.getResponseSchema());
    }

    /**
     * The authored {@code outputs} map, in stored order, each value in the form it was authored in.
     *
     * <p><b>Absent rather than empty when the declaration stores none</b>, which is a folded legacy
     * declaration: {@code outputs: {}} would say "it produces nothing", where the truth is "this
     * declaration keeps its outputs under the other member".
     */
    private static Map<String, OutputSpec> toAuthoredOutputsDto(EnrichSpec.TransformSpec transform) {
        final List<TransformOutput> outputs = transform.getOutputs();
        if (outputs == null || outputs.isEmpty()) {
            return null;
        }
        final Map<String, OutputSpec> dto = new LinkedHashMap<>();
        for (TransformOutput output : outputs) {
            dto.put(output.getName(), toOutputSpec(output, transform.getType()));
        }
        return dto;
    }

    /**
     * One authored output in the form it was authored in: the bare string for a {@code sql} expression or
     * for prose with nothing beside it, and the object where the declaration carries a domain or a
     * transform. A {@code null} value round-trips as {@code null} — the request reads that as "default the
     * prose from the column", which is what it meant on the way in.
     */
    private static OutputSpec toOutputSpec(TransformOutput output, TransformType type) {
        if (type == TransformType.SQL) {
            return new OutputSpec(output.getSql(), null);
        }
        final boolean hasValues = output.getValues() != null && !output.getValues().isEmpty();
        if (!hasValues && StringUtils.isBlank(output.getJsonata())) {
            return output.getProse() == null ? null : new OutputSpec(output.getProse(), null);
        }
        return new OutputSpec(null,
                new OutputSpecBody(output.getProse(), output.getValues(), output.getJsonata()));
    }

    /** The legacy output shape, or absent when the declaration does not carry it. */
    private static List<TransformVarDto> toVarDtos(List<TransformVar> vars) {
        if (vars == null || vars.isEmpty()) {
            return null;
        }
        return vars.stream()
                .map(var -> new TransformVarDto(var.getName(), var.getType(), var.getJsonata(), var.getSql()))
                .toList();
    }

    /**
     * The stored bindings as the authored {@code transform.inputs} map, in stored order.
     *
     * <p>Always the object form, even for a binding the caller wrote as the bare-string shorthand: a
     * response that could return either spelling would make a reader guess which, and the request accepts
     * the object form too, so a GET → PATCH round trip stays faithful.
     *
     * <p><b>Absent rather than empty when the pipeline declares none</b>, which is every {@code sql}
     * pipeline: a Class-1 transform asks no model anything, so it has no variables, and {@code inputs: {}}
     * says "it takes an empty set of inputs" where the truth is "this kind has no such member". That is the
     * same distinction the rest of this DTO is built on — a member belonging to the other shape is absent,
     * never reported empty — and {@code NON_NULL} inclusion cannot make it for us, because an empty map is
     * not null.
     */
    private static Map<String, VarSpecBody> toVarsDto(List<InputBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return null;
        }
        final Map<String, VarSpecBody> vars = new LinkedHashMap<>();
        for (InputBinding binding : bindings) {
            vars.put(binding.getVar(), new VarSpecBody(binding.getColumn(), binding.getJsonata()));
        }
        return vars;
    }

    /**
     * The stored output bindings folded together with each output's transform, in binding order.
     *
     * <p><b>This is the join that used to be the consumer's problem.</b> A consumer received the bindings
     * ({@code column} → {@code var}) and the transform's output vars ({@code name} → {@code jsonata}) and
     * had to match them by name to know what to do with a model response — which the Enrichment Runner
     * did per row, building a throwaway map, for a list whose transform is the identity on 24 of 25 live
     * outputs. There is one correct way to perform that join and one place that holds both halves, so it
     * happens here.
     *
     * <p>The transform is read through {@code jsonataByOutputName()}, so it resolves for both stored
     * shapes: a folded declaration carrying {@code output_vars} keeps serving its own, and one carrying
     * {@code outputs} projects them. A blank transform is left <b>null</b> rather than echoed as an
     * identity expression, because absent is what "read the response property named by {@code name}"
     * means and an identity {@code jsonata} is refused at declaration.
     *
     * <p>An output var the transform no longer declares contributes no transform rather than dropping the
     * binding: the binding list is the write allow-list, and a silently shorter one writes NULL over a
     * paid-for label. A mapping that cannot be resolved at all is refused upstream, not thinned here.
     */
    private static List<PipelineOutputDto> toOutputDtos(EnrichSpec spec) {
        final List<OutputBinding> bindings = spec.getOutputBindings();
        if (bindings == null) {
            return null;
        }
        final Map<String, String> transforms = spec.getTransform() == null
                ? Map.of()
                : spec.getTransform().jsonataByOutputName();
        return bindings.stream()
                .map(binding -> new PipelineOutputDto(
                        binding.getVar(), binding.getColumn(), transforms.get(binding.getVar())))
                .toList();
    }

    /**
     * The stored knobs under their authoring names, or <b>null</b> when the declaration set none — an
     * absent block says "the runner's own defaults apply", where an all-null block would read as a
     * declaration that had considered each knob and chosen nothing.
     */
    private static AdvancedRequest toAdvancedDto(EnrichSpec spec) {
        if (spec.getSampling() == null && spec.getBatchScanLimit() == null
                && spec.getBatchChunk() == null && spec.getRateRpm() == null) {
            return null;
        }
        return new AdvancedRequest(spec.getBatchScanLimit(), spec.getBatchChunk(), spec.getRateRpm(),
                spec.getSampling());
    }

    /**
     * A dry-run preview of either kind as its response shape. Hand-written because the note's severity is a
     * property of its code rather than a member of the service model, and is surfaced here so a client need not
     * carry the table.
     */
    public PreviewDto toPreviewDto(PipelinePreview preview) {
        return new PreviewDto(
                preview.statement(),
                preview.query(),
                preview.columns().stream()
                        .map(column -> new PreviewColumnDto(column.name(), column.role(), column.type(),
                                column.targetType(), column.maxBytes()))
                        .toList(),
                preview.rows(),
                preview.totalRows(),
                preview.groupKey(),
                preview.notes().stream()
                        .map(note -> new PreviewNoteDto(note.code(), note.severity(), note.subject(), note.message()))
                        .toList());
    }

    /** Generated: {@link GroupKey} ↔ {@link GroupKeyRequest} (including the nested {@code trunc}) by name. */
    public abstract List<GroupKeyRequest> toGroupKeyDtos(List<GroupKey> keys);

    protected abstract List<GroupKey> toGroupKeys(List<GroupKeyRequest> requests);

    /**
     * Measures on the way out. Two things are hand-written rather than copied by name.
     *
     * <p>{@code column} is echoed <b>expanded</b> — it is read from the stored spec, which
     * {@link #toMeasures} normalised on the way in, so a declaration that omitted it reads back with the
     * input column it actually resolved to. That matters because the default cannot detect an input
     * carrying two plausibly-named columns: {@code dial_usage_log} has both {@code total_price} and
     * {@code deployment_price}, and a rollup may deliberately declare
     * {@code {"name": "total_price", "column": "deployment_price"}} while another sums the other one. Write
     * {@code {"name": "total_price", "fn": "sum"}} and it resolves to {@code total_price}, validates
     * cleanly, and is wrong — so the resolution is made visible rather than left to be discovered in the
     * numbers.
     *
     * <p>{@code distinct} is echoed <b>only when true</b>. It was {@code false} on 80 of the 87 live
     * measures, which made a default the single most repeated member of the whole registry read.
     */
    public List<MeasureRequest> toMeasureDtos(List<Measure> measures) {
        if (measures == null) {
            return List.of();
        }
        return measures.stream()
                .map(measure -> new MeasureRequest(measure.getName(), measure.getFn(), measure.getColumn(),
                        measure.getWhere(), measure.isDistinct() ? Boolean.TRUE : null))
                .toList();
    }

    /**
     * Measures on the way in, with {@code column} resolved <b>by function</b> and normalised into the
     * stored declaration.
     *
     * <p>An omitted {@code column} means the measure's own {@code name} for every function <em>except</em>
     * {@code count}, where it keeps meaning {@code count(*)}. The rule has to be function-aware, and a
     * flat "defaults to {@code name}" would be wrong twice over: 13 live measures are column-less counts
     * whose names ({@code hop_count}, {@code rate_event_count}) name no column at all, and
     * {@code count(<column>)} skips NULLs where {@code count(*)} does not — so the flat default would break
     * some declarations outright and silently change the numbers on the rest.
     *
     * <p>Normalising here rather than deriving at read time keeps one stored spelling of the grain:
     * everything downstream — the compiler, the target-binding check, the response — reads the expanded
     * value, and there is no second derivation path to keep in step behind each of them.
     */
    protected List<Measure> toMeasures(List<MeasureRequest> requests) {
        if (requests == null) {
            return new ArrayList<>();
        }
        final List<Measure> measures = new ArrayList<>(requests.size());
        for (MeasureRequest request : requests) {
            final Measure measure = new Measure();
            measure.setName(request.name());
            measure.setFn(request.fn());
            measure.setColumn(resolvedColumnOf(request));
            measure.setWhere(request.where());
            measure.setDistinct(Boolean.TRUE.equals(request.distinct()));
            measures.add(measure);
        }
        return measures;
    }

    /** The measure's input column: as declared, else its own name — except for a column-less {@code count}. */
    private static String resolvedColumnOf(MeasureRequest request) {
        if (StringUtils.isNotBlank(request.column())) {
            return request.column();
        }
        return COUNT_FN.equalsIgnoreCase(StringUtils.trimToEmpty(request.fn())) ? null : request.name();
    }

    // No binding mappers, in either direction. Nothing named `input_bindings` or `output_bindings`
    // crosses the wire any more: inputs are declared and served as `vars` (a map, mapped by hand, since
    // its keys are the variable names), and the stored output bindings — derived by EnrichSpecValidator
    // from the transform's outputs matched to the target's columns — are served folded together with each
    // output's transform as `outputs`, by toOutputDtos below.

    protected abstract ReadyWhen toReadyWhen(ReadyWhenDto dto);

    protected abstract ReadyWhenDto toReadyWhenDto(ReadyWhen readyWhen);

    protected abstract MemberSelect toMemberSelect(MemberSelectDto dto);

    protected abstract MemberSelectDto toMemberSelectDto(MemberSelect select);

    /**
     * The runtime state, combining what the last run recorded with what is true of the pipelines the
     * declaration points at now. The clamp comes from the row because only the run that was clamped can
     * know it; the other two are derived, so a pipeline that has never run still reports them.
     *
     * <p>The ADAS-owned cursor and schedule members are reported as <b>absent</b> for a kind whose cursor
     * the runner holds, rather than as the nulls and zeroes the row happens to carry — an omitted member
     * says "no such position here", where a zero would read as a real one.
     */
    private static PipelineDto.StateDto toStateDto(
            PipelineEntity pipeline, PipelineFreshnessDiagnostics.Report freshness) {
        final boolean adasDriven = pipeline.getKind() != PipelineKind.ENRICH;
        return new PipelineDto.StateDto(
                adasDriven ? pipeline.getCursorVersion() : null,
                adasDriven ? pipeline.getCursorIdentity() : null,
                adasDriven ? pipeline.lagSeconds() : null,
                adasDriven ? pipeline.getLastRunAt() : null,
                adasDriven ? pipeline.getNextRunAt() : null,
                adasDriven ? pipeline.getLastError() : null,
                adasDriven ? pipeline.isHasMore() : null,
                toClampDto(pipeline),
                freshness.unclampedReads().stream()
                        .map(read -> new PipelineDto.PipelineUnclampedReadDto(read.enrichment(), read.reason()))
                        .toList(),
                freshness.rebuild() == null ? null : new PipelineDto.PipelineRebuildDto(
                        freshness.rebuild().enrichment(), freshness.rebuild().rederivedAt()),
                pipeline.getMaterializedThroughVersion(),
                pipeline.getMaterializedThroughIdentity(),
                pipeline.getDrainedAt());
    }

    /** The clamp the last run recorded, or null when it was not clamped. */
    private static PipelineDto.PipelineClampDto toClampDto(PipelineEntity pipeline) {
        if (pipeline.getClampedBy() == null) {
            return null;
        }
        return new PipelineDto.PipelineClampDto(pipeline.getClampedBy(),
                pipeline.getClampedToVersion(), pipeline.getClampedToIdentity());
    }

    /**
     * Composes the stored declaration for the request's kind, from the wire members that carry it.
     *
     * <p><b>The two binding lists keep their stored names; the three remaining knobs are renamed with an alias.</b>
     * {@link EnrichSpec} still stores {@code input_bindings} (inside the transform now) and
     * {@code output_bindings} — a list is not the map and the folded list the wire calls
     * {@code transform.inputs} and {@code outputs}, so borrowing those names would be a worse lie than the
     * two it replaced. The execution knobs <em>do</em> move
     * ({@code rows_per_scan}, {@code rows_per_call}, {@code sample_fraction}), each
     * carrying a {@link com.fasterxml.jackson.annotation.JsonAlias} on its former spelling, so a row stored
     * before this change keeps parsing and no data migration exists — see {@link EnrichSpec} for why that
     * direction is the safe one and the reverse is out of scope by decision.
     *
     * <p>{@code freshness} is <b>not touched at all</b>, and that is a trap worth naming rather than a
     * simplification. {@code AggregateSpec.freshness} is field-initialised to a periodic intent, so a stored
     * spec carrying no member already parses as periodic — but Jackson honours an <em>explicit</em>
     * {@code "freshness": null} by calling the setter, and {@code validateFreshness} throws when the value
     * is null. So passing a null through where the request's value used to go would make every aggregate
     * registration and every re-arm a 422. Leaving the member alone keeps the initialiser's default and
     * keeps {@code validateFreshness} reachable only for a hand-edited row, which is the case it now guards.
     *
     * <p>Every enrich knob is stored as sent, including null, because a null there means "not declared" and
     * the runner applies its own configured fallback — a default written at this layer would be
     * indistinguishable from an operator's deliberate choice.
     */
    private PipelineSpec toSpec(PipelineKind kind, List<GroupKeyRequest> groupBy,
            List<MeasureRequest> measures, String sql, TransformFields transform, AdvancedRequest advanced) {
        if (kind == PipelineKind.ENRICH) {
            final EnrichSpec spec = new EnrichSpec();
            spec.setTransform(toTransformSpec(transform));
            // output_bindings are DERIVED, by EnrichSpecValidator, from the transform's outputs matched to
            // the target's columns - not sent by the caller and not set here. They are still stored,
            // because three write paths read the list and all three fail closed on an empty one.
            spec.setSampling(sampleFractionOf(advanced));
            spec.setBatchScanLimit(advanced == null ? null : advanced.rowsPerScan());
            spec.setBatchChunk(advanced == null ? null : advanced.rowsPerCall());
            spec.setRateRpm(advanced == null ? null : advanced.rateRpm());
            return spec;
        }
        final AggregateSpec spec = new AggregateSpec();
        spec.setGroupBy(toGroupKeys(groupBy));
        spec.setMeasures(toMeasures(measures));
        spec.setSql(sql);
        return spec;
    }

    /**
     * The two verbs' transform records read as one shape.
     *
     * <p>{@code POST} and {@code PATCH} take <b>different record types</b> deliberately — the create-side
     * one omits {@code output_vars}, which is what makes {@code transform.output_vars} on a {@code POST} an
     * unknown member and therefore a 400 naming the field, exactly as {@code cadence} is refused, rather
     * than a hand-written check that has to be remembered. Everything downstream of the binding wants the
     * union, so the two collapse here and nothing else in this mapper dispatches on the verb.
     */
    private record TransformFields(
            TransformType type,
            TransformPreset preset,
            String model,
            Map<String, Object> params,
            String requestTemplate,
            Map<String, VarSpec> inputs,
            Map<String, OutputSpec> outputs,
            List<TransformVarDto> outputVars,
            Map<String, Object> responseSchema) {}

    private static TransformFields transformFieldsOf(CreateTransformRequest request) {
        if (request == null) {
            return null;
        }
        return new TransformFields(request.type(), request.preset(), request.model(), request.params(),
                request.requestTemplate(), request.inputs(), request.outputs(), null,
                request.responseSchema());
    }

    private static TransformFields transformFieldsOf(PatchTransformRequest request) {
        if (request == null) {
            return null;
        }
        return new TransformFields(request.type(), request.preset(), request.model(), request.params(),
                request.requestTemplate(), request.inputs(), request.outputs(), request.outputVars(),
                request.responseSchema());
    }

    /**
     * The authored transform as the stored block, carrying every member as sent.
     *
     * <p><b>Nothing is dispatched away or normalised here except the two things that have to be.</b> The
     * {@code llm}/{@code sql} member rules are the declaration gate's, and reporting them there is what
     * lets a refusal name the field with a 422; a mapper that dropped an {@code llm}-only member from a
     * {@code sql} transform would turn "a sql transform must not carry model" into a silent accept.
     *
     * <p>The two exceptions. {@code preset} defaults to {@code chat_completion} for {@code llm}, because it
     * is the only value the enum has — and the default is {@code llm}-only, so a {@code sql} body's own
     * {@code preset} stays null and is refused by the gate rather than being filled in and then accepted.
     * And an output's bare string is dispatched on {@code type}: the expression for {@code sql}, prose for
     * {@code llm}. That dispatch cannot move later — both grammars are "a string" on the wire, so this is
     * the last point at which the sibling {@code type} is in hand together with the value.
     *
     * <p>Order is carried by the maps' iteration order, which is the request body's key order. This is the
     * point at which that order stops being incidental and becomes the stored array's order, hence the
     * composed schema's property order, hence the model's generation order.
     *
     * <p><b>An absent block maps to a null transform, not to an empty one</b>, and that distinction is what
     * makes the declaration gate's own diagnosis reachable. An empty {@link EnrichSpec.TransformSpec} is a
     * transform that was declared and left blank, so the gate answers "transform.type is required" —
     * telling an author who sent no {@code transform} at all to fill in a member of a block they never
     * wrote. Storing null instead lets the gate say "transform is required for an 'enrich' pipeline", which
     * is the fact the caller is missing.
     */
    private static EnrichSpec.TransformSpec toTransformSpec(TransformFields request) {
        if (request == null) {
            return null;
        }
        final EnrichSpec.TransformSpec transform = new EnrichSpec.TransformSpec();
        final boolean sql = request.type() == TransformType.SQL;
        transform.setType(request.type());
        transform.setPreset(request.preset() == null && !sql
                ? TransformPreset.CHAT_COMPLETION : request.preset());
        transform.setModel(request.model());
        transform.setParams(request.params());
        transform.setRequestTemplate(request.requestTemplate());
        transform.setInputBindings(toInputBindings(request.inputs()));
        transform.setOutputs(toOutputs(request.outputs(), sql));
        transform.setOutputVars(toOutputVars(request.outputVars()));
        transform.setResponseSchema(request.responseSchema());
        return transform;
    }

    /**
     * The authored {@code outputs} map as the stored ordered list, or <b>null</b> when the declaration
     * sends none — which is what keeps a folded legacy declaration readable as legacy: the output-shape
     * accessor treats a null or empty {@code outputs} as "read {@code output_vars} instead", so writing an
     * empty array here would flip such a declaration to the other shape and lose every output it has.
     */
    private static List<TransformOutput> toOutputs(Map<String, OutputSpec> outputs, boolean sql) {
        if (outputs == null) {
            return null;
        }
        final List<TransformOutput> stored = new ArrayList<>(outputs.size());
        for (Map.Entry<String, OutputSpec> entry : outputs.entrySet()) {
            stored.add(toOutput(entry.getKey(), entry.getValue(), sql));
        }
        return stored;
    }

    /**
     * One {@code outputs} entry. A null value is carried through as an output with only a name rather than
     * silently dropped: under the optional-prose rule that is exactly "default the description from the
     * column", and dropping it would make the key vanish from the composed schema instead.
     */
    private static TransformOutput toOutput(String name, OutputSpec spec, boolean sql) {
        final TransformOutput output = new TransformOutput();
        output.setName(name);
        if (spec == null) {
            return output;
        }
        // For a sql transform the bare string is the expression; for an llm one it is prose. The object
        // form has no meaning on a sql transform, so it falls through to the same members an llm output
        // uses and is carried through UNCHANGED rather than dropped: that is what lets the gate tell "an
        // object was sent to a sql transform" from "the expression was blank" and refuse each by name.
        if (sql && spec.isBareString()) {
            output.setSql(spec.text());
            return output;
        }
        output.setProse(spec.proseText());
        output.setValues(spec.values());
        output.setJsonata(spec.jsonata());
        return output;
    }

    /**
     * The legacy output shape as stored, patched back verbatim. Empty rather than null when the caller
     * sends none, matching what the fold migration writes for a declaration that has no legacy outputs.
     */
    private static List<TransformVar> toOutputVars(List<TransformVarDto> outputVars) {
        final List<TransformVar> stored = new ArrayList<>();
        if (outputVars == null) {
            return stored;
        }
        for (TransformVarDto dto : outputVars) {
            final TransformVar var = new TransformVar();
            var.setName(dto.name());
            var.setType(dto.type());
            var.setJsonata(dto.jsonata());
            var.setSql(dto.sql());
            stored.add(var);
        }
        return stored;
    }

    /**
     * {@code transform.inputs} as the stored binding list, in declaration order.
     *
     * <p>Hand-written rather than generated because the wire shape is a map whose keys are the variable
     * names — which is the point of the shape: a name can no longer be declared in one place and bound in
     * another with nothing comparing the two.
     */
    private static List<InputBinding> toInputBindings(Map<String, VarSpec> vars) {
        final List<InputBinding> bindings = new ArrayList<>();
        if (vars == null) {
            return bindings;
        }
        for (Map.Entry<String, VarSpec> entry : vars.entrySet()) {
            final InputBinding binding = new InputBinding();
            binding.setVar(entry.getKey());
            final VarSpec spec = entry.getValue();
            if (spec != null) {
                binding.setColumn(spec.column());
                binding.setJsonata(spec.jsonata());
            }
            bindings.add(binding);
        }
        return bindings;
    }

    /**
     * {@code sample_fraction}, held to a strict fraction in {@code (0, 1]}.
     *
     * <p>An ADAS-side change only: the runner's own sampler reads {@code <= 1} as a fraction and {@code > 1}
     * as a percentage, with an intentional discontinuity at 1. That leniency simply becomes unreachable on
     * its {@code > 1} branch, and {@code sample_fraction: 1} keeps meaning 100% in both — so the runner
     * needs no edit for this.
     *
     * <p>{@code 0} is refused rather than stored: the sampler reads it as keep-nothing, which is a pipeline
     * that is armed and evaluates no rows. That is a state {@code enabled: false} already expresses
     * honestly, so the refusal names it.
     */
    private static Double sampleFractionOf(AdvancedRequest advanced) {
        if (advanced == null || advanced.sampleFraction() == null) {
            return null;
        }
        final double fraction = advanced.sampleFraction();
        if (fraction > 1.0d) {
            throw new PipelineValidationException("advanced.sample_fraction must be a fraction in (0, 1],"
                    + " not " + fraction + "; it is not read as a percentage");
        }
        if (fraction <= 0.0d) {
            throw new PipelineValidationException("advanced.sample_fraction must be a fraction in (0, 1],"
                    + " not " + fraction + "; declare 'enabled': false to register a pipeline that"
                    + " evaluates nothing");
        }
        return fraction;
    }

    /**
     * v1 reads exactly one source. A multi-source declaration is rejected rather than silently truncated.
     * An absent one is accepted for either kind here: an {@code enrich} pipeline may follow its target's
     * parent, and an {@code aggregate} pipeline may not have named its input yet while it is being
     * authored — it is refused when it is armed, by the declaration gate, naming the absent input.
     */
    private static String singleInput(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }
        if (inputs.size() != 1) {
            throw new PipelineValidationException(
                    "inputs must name exactly one source table (multi-source input is not yet supported)");
        }
        return inputs.getFirst();
    }

    /**
     * The inputs to echo back, which is a different fact per projection.
     *
     * <p>The <b>compiled</b> projection reports the <em>resolved</em> table — the input declared, else the
     * target enrichment's parent — because that is the table the runner is to scan, and it must not need a
     * second lookup to find out.
     *
     * <p>The <b>authored</b> projection reports the <em>stored</em> value, and <b>omits it entirely</b>
     * when the pipeline declared none. That distinction is load-bearing rather than cosmetic: source and
     * target parent are deliberately separable, and three of the four live enrich pipelines read a table
     * that is not their target's parent. Echoing a resolved value on the authored projection would make a
     * GET → PATCH round trip silently <em>declare</em> what the pipeline had been inheriting, after which
     * three stricter invariants apply (the source must be an ACTIVE source table, must carry the target's
     * grain key at a compatible type, and must be scannable) — so a caller could lose the "follow my
     * target's parent" reading without ever having written a source.
     */
    private static List<String> inputsOf(
            PipelineEntity pipeline, ResolvedEnrichPipeline resolved, boolean compiled) {
        if (!compiled) {
            return pipeline.getInputTable() == null ? null : List.of(pipeline.getInputTable());
        }
        final String input = resolved != null && resolved.source() != null
                ? resolved.source()
                : pipeline.getInputTable();
        return input == null ? List.of() : List.of(input);
    }

    /**
     * Rejects a wire member that belongs to the other kind, naming it. Not a style check: see the class
     * javadoc for why silently ignoring one is the failure mode this prevents.
     */
    private static void requireKindFields(
            PipelineKind kind, List<String> aggregateFields, List<String> enrichFields) {
        if (kind == null) {
            return;
        }
        final List<String> offending = kind == PipelineKind.ENRICH ? aggregateFields : enrichFields;
        if (!offending.isEmpty()) {
            final PipelineKind owner = kind == PipelineKind.ENRICH
                    ? PipelineKind.AGGREGATE : PipelineKind.ENRICH;
            throw new PipelineValidationException(String.join(", ", offending)
                    + (offending.size() == 1 ? " belongs" : " belong") + " to the '" + owner.code()
                    + "' kind and cannot be declared on a '" + kind.code() + "' pipeline");
        }
    }

    /**
     * Refuses {@code enabled: true} on a <b>registration</b> of a kind whose disabled state is its dry-run
     * gate, instead of silently coercing it to {@code false}.
     *
     * <p>A field the caller set and the server discarded is the class of surprise this whole change exists
     * to remove, and it was the last one left in the registration path. {@code enabled: false} and an
     * absent {@code enabled} both stay legal — an honest statement is not a mistake.
     *
     * <p><b>Registration only.</b> The patch path is the only way such a pipeline is ever armed, and it
     * re-validates before it flips, so extending this to {@code PATCH} would make the kind unarmable.
     */
    private static void requireEnabledNotArmedAtRegistration(CreatePipelineRequest request) {
        // Scoped to AGGREGATE by name rather than to "not ENRICH": a kind the registry cannot store at all
        // must be refused as unimplemented, by the service's own kind gate, not told about a preview
        // endpoint that does not serve it.
        if (request.kind() == PipelineKind.AGGREGATE && Boolean.TRUE.equals(request.enabled())) {
            throw new PipelineValidationException("an '" + PipelineKind.AGGREGATE.code() + "' pipeline is"
                    + " registered disabled, because its dry run is the gate that state exists for: check"
                    + " the declaration with POST /v1/pipelines/{name}/preview, then arm it with PATCH"
                    + " /v1/pipelines/{name}. Send 'enabled': false, or omit it.");
        }
    }

    private static List<String> aggregateFieldsOf(CreatePipelineRequest request) {
        return present(AGGREGATE_ONLY, request.groupBy(), request.measures(), request.sql());
    }

    private static List<String> aggregateFieldsOf(PatchPipelineRequest request) {
        return present(AGGREGATE_ONLY, request.groupBy(), request.measures(), request.sql());
    }

    /**
     * Refuses an {@code aggregate} declaration carrying a blank {@code sql}, which would be stored as an SQL-mode
     * declaration with nothing in it, or {@code sql} beside a form-mode member, naming the member. On every write,
     * armed or not: two declarations of the same keys, measures and {@code WHERE} are a
     * contradiction no further authoring resolves, and any rule picking one would silently discard the other.
     */
    private static void requireOneAggregateMode(PipelineKind kind, String sql, List<GroupKeyRequest> groupBy,
            List<MeasureRequest> measures, String filter) {
        if (kind != PipelineKind.AGGREGATE || sql == null) {
            return;
        }
        // Asked first, so `"sql": ""` beside group_by is told what is wrong with it rather than that it is a mode.
        if (sql.isBlank()) {
            throw new PipelineValidationException("sql must not be blank");
        }
        final List<String> form = present(FORM_MODE, groupBy, measures, filter);
        if (!form.isEmpty()) {
            throw new PipelineValidationException(String.join(", ", form) + (form.size() == 1 ? " is" : " are")
                    + " a form-mode member and cannot be declared beside sql, which carries the group keys, the"
                    + " measures and the WHERE itself; send either sql or group_by/measures/filter");
        }
    }

    /**
     * The {@code sql} an aggregate patch declares: the one it sends; none when it sends a form-mode member,
     * which switches the pipeline to form mode; otherwise the stored statement, kept unchanged — a patch
     * carrying only shared members ({@code trigger}, {@code inputs}, {@code target}) keeps the stored mode,
     * because in SQL mode the whole keys-measures-filter body is one member and clearing it by omission would
     * make every runtime patch erase a statement it never mentioned. An unreadable stored spec carries nothing.
     */
    private String patchedSql(PipelineEntity stored, PatchPipelineRequest request) {
        if (request.sql() != null) {
            return request.sql();
        }
        if (!present(FORM_MODE, request.groupBy(), request.measures(), request.filter()).isEmpty()
                || stored.getSpec() == null) {
            return null;
        }
        try {
            return specOf(stored, AggregateSpec.class).getSql();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static List<String> enrichFieldsOf(CreatePipelineRequest request) {
        return enrichFields(transformFieldsOf(request.transform()), request.advanced());
    }

    private static List<String> enrichFieldsOf(PatchPipelineRequest request) {
        return enrichFields(transformFieldsOf(request.transform()), request.advanced());
    }

    /**
     * The enrich-only members actually sent, with the {@code transform} and {@code advanced} blocks
     * reported one member at a time so the cross-kind refusal names the field the caller misplaced rather
     * than the block holding it.
     */
    private static List<String> enrichFields(TransformFields transform, AdvancedRequest advanced) {
        return present(ENRICH_ONLY,
                transform == null ? null : transform.type(),
                transform == null ? null : transform.preset(),
                transform == null ? null : transform.model(),
                transform == null ? null : transform.params(),
                transform == null ? null : transform.requestTemplate(),
                transform == null ? null : transform.inputs(),
                transform == null ? null : transform.outputs(),
                transform == null ? null : transform.outputVars(),
                transform == null ? null : transform.responseSchema(),
                advanced == null ? null : advanced.rowsPerScan(),
                advanced == null ? null : advanced.rowsPerCall(),
                advanced == null ? null : advanced.rateRpm(),
                advanced == null ? null : advanced.sampleFraction());
    }

    /** The names, in {@code names} order, of the values that were actually sent. */
    private static List<String> present(List<String> names, Object... values) {
        final List<String> sent = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (values[i] != null) {
                sent.add(names.get(i));
            }
        }
        return sent;
    }

    /**
     * The trigger kind, or null for "not stated" — which the service resolves to {@code schedule} for an
     * aggregate pipeline, keeping every registration body that predates the merged trigger valid.
     */
    private static TriggerKind triggerKindOf(TriggerRequest trigger) {
        if (trigger == null || StringUtils.isBlank(trigger.kind())) {
            return null;
        }
        if (RETIRED_TRIGGER_KIND.equalsIgnoreCase(trigger.kind().trim())) {
            throw new PipelineValidationException("trigger.kind '" + RETIRED_TRIGGER_KIND + "' is retired; declare"
                    + " 'schedule', the row-grain trigger, with a trigger.cron (omit it to run every minute)");
        }
        return parseEnum(TriggerKind.class, trigger.kind(), "trigger.kind", "schedule, group");
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, String raw, String field, String allowed) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new PipelineValidationException(
                    field + " '" + raw + "' is not supported (allowed: " + allowed + ")");
        }
    }

    private static String codeOf(TriggerKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private <T extends PipelineSpec> T specOf(PipelineEntity pipeline, Class<T> type) {
        return type.cast(specCodec.read(pipeline));
    }

    /**
     * A patch may restate the pipeline's identity but not change it: {@code name} and {@code kind} are
     * accepted when they match what the pipeline already has and refused when they differ.
     *
     * <p>Checked before the {@code hasDeclaration} short-circuit above, deliberately. Neither member counts
     * as a declaration, so a body of {@code {"name": "someone-else", "enabled": true}} would otherwise take
     * the enable-only path and apply happily to the pipeline in the URL — the one case where getting this
     * wrong acts on a pipeline the caller did not mean to touch.
     *
     * <p>422 rather than 404: the pipeline the path names exists, and the caller's error is in the body.
     */
    private static void requireIdentityEcho(String name, PipelineKind kind, PatchPipelineRequest request) {
        if (request.name() != null && !request.name().equals(name)) {
            throw new PipelineValidationException("body name '" + request.name() + "' does not match the "
                    + "pipeline being patched ('" + name + "'); a pipeline's name is its identity and "
                    + "cannot be changed");
        }
        if (request.kind() != null && request.kind() != kind) {
            throw new PipelineValidationException("pipeline '" + name + "' is of kind '" + kind.code()
                    + "' and its kind cannot be changed; a different transform over the same target is a "
                    + "different pipeline");
        }
    }

    /**
     * A declaration patch <b>that leaves the pipeline armed</b> must supply the whole declaration for its
     * kind. A partial merge would leave the stored declaration in a state no caller described — and for the
     * aggregate kind, whose group keys must equal the target's ordering key, it would also let one half of a
     * re-grain land without the other.
     *
     * <p>Not asked of a patch that leaves the pipeline disabled: that is a draft save, which stores the
     * members it was given and clears the rest, and the arming gate asks for what is missing.
     * {@code target} is not among the required members, armed or not: a declaration patch that omits it
     * keeps the stored one, so it is never absent from the declaration being armed.
     */
    private static void requireComplete(PipelineKind kind, PatchPipelineRequest request, String sql) {
        if (kind == PipelineKind.ENRICH) {
            if (request.transform() == null || request.trigger() == null) {
                throw new PipelineValidationException("a declaration patch that leaves the pipeline enabled "
                        + "must supply transform and trigger together; a partial merge would leave a "
                        + "declaration no caller described (a patch that leaves it disabled may supply any "
                        + "subset while its declaration is incomplete)");
            }
            return;
        }
        // SQL mode: the statement carries the keys, the measures and the input, so it and the trigger are the
        // whole declaration. `sql` is the patched one — sent, or the stored statement a patch carrying no
        // form-mode member keeps.
        if (sql != null) {
            if (request.trigger() == null) {
                throw new PipelineValidationException("a declaration patch that leaves the pipeline enabled "
                        + "must supply sql and trigger together; a partial merge would leave a declaration no "
                        + "caller described (a patch to a disabled pipeline that leaves it disabled may supply any "
                        + "subset while its declaration is incomplete)");
            }
            return;
        }
        // group_by is NOT required, and must not become so: it is optional at registration (derived from
        // the target's ordering_key), so demanding it here would refuse a patch of a rollup that was
        // ALLOWED to omit it, asking for a field the registration said was optional.
        if (request.inputs() == null || request.measures() == null || request.trigger() == null) {
            throw new PipelineValidationException("a declaration patch that leaves the pipeline enabled "
                    + "must supply inputs, measures, and trigger together; a partial merge would leave a "
                    + "declaration no caller described (a patch to a disabled pipeline that leaves it "
                    + "disabled may supply any subset while its declaration is incomplete; an enabled aggregate pipeline must be disabled by a "
                    + "separate patch before its declaration is edited)");
        }
    }

    /**
     * The read source an enrich declaration patch stores. A declaration patch of a pipeline that <b>has</b> a
     * declared read source must restate it — unless that source is the very table an omission would follow.
     *
     * <p>Omitting it would repoint the pipeline from the table it declared to its target's parent — a change
     * to what the pipeline reads that the caller never wrote: a pipeline reading a ratings table could
     * silently start reading its target's parent instead. Such a repoint often fails loudly a moment later
     * (the transform's expressions name columns the parent does not carry), but the risk is a pipeline whose
     * two candidate sources share enough columns to validate either way, which is the argument for closing it
     * now rather than relying on that.
     *
     * <p>When the declared source <b>is</b> the parent of the target the patch leaves in place, the omission
     * repoints nothing, and the stored source is kept rather than cleared — the same stickiness an omitted
     * {@code target} has. Kept, not cleared, because clearing would still change the pipeline: the authored
     * projection and a catalog export would stop naming the source, the stricter invariants a declared
     * source carries would lapse, and the service would see a different read source and drop the derived
     * members it carries over only while the tables are unchanged. The case is real rather than contrived:
     * a client reading the compiled projection, where {@code inputs} is always resolved, cannot tell a
     * declared source from a followed one, and so cannot know to restate it.
     *
     * <p>A pipeline that never declared a source stays patchable without {@code inputs}: for it, omitting
     * the member means the same thing it meant at registration.
     *
     * <p>Asked of every declaration patch, armed or not: it guards against an implicit repoint, not an
     * incomplete declaration. A draft that means to drop its declared source says so with
     * {@code inputs: []}.
     */
    private static String patchedInput(PatchPipelineRequest request, String storedInputTable,
            Supplier<String> targetParentOf) {
        if (storedInputTable == null || request.inputs() != null) {
            return singleInput(request.inputs());
        }
        final String targetParent = targetParentOf.get();
        if (storedInputTable.equals(targetParent)) {
            return storedInputTable;
        }
        throw new PipelineValidationException("a declaration patch of a pipeline that declares its read"
                + " source must restate 'inputs' (this one declares '" + storedInputTable + "');"
                + " omitting it would repoint the pipeline at its target's parent source"
                + (targetParent == null ? "" : " '" + targetParent + "'")
                + ", which is a change to what it reads that the patch does not say");
    }
}
