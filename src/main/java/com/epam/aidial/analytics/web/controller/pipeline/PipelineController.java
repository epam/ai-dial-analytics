package com.epam.aidial.analytics.web.controller.pipeline;

import com.epam.aidial.analytics.exception.InvalidRequestParameterException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.audit.AuditPipelineSnapshotService;
import com.epam.aidial.analytics.service.audit.AuditSnapshotGuards;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineFreshnessDiagnostics;
import com.epam.aidial.analytics.service.pipeline.PipelineJsonReadNotes;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.model.PipelineEnabledFilter;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineDto;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.CreatePipelineRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.PatchPipelineRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineResponses.PipelineListDto;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineView;
import com.epam.aidial.analytics.web.mapper.pipeline.PipelineMapper;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.beans.PropertyEditorSupport;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * Pipeline registry API — the control plane for declared row-producing processes, of both kinds: the
 * {@code aggregate} in-DB rollup ADAS refreshes on a cron, and the {@code enrich} declaration the
 * Enrichment Runner drives (except its Class-1 case, which ADAS materializes itself).
 *
 * <p>The aggregate lifecycle is <b>register → preview → enable</b>: registration always lands disabled,
 * so the dry-run preview ({@code POST …/{name}/preview}, see {@link PipelineDataController}) always sits
 * between declaring a rollup and letting it write. An {@code enrich} pipeline has a preview too, but its
 * declaration is gated by the static governance gate rather than by a disabled state, so it may be
 * registered enabled; {@code enabled: false}, preview, then enable is its checkpoint.
 *
 * <p>The {@code GET} collection is both the operator's listing of the registry and the runner's sync
 * feed: with no filter it returns the registry as it actually stands, disabled pipelines included, and a
 * consumer that runs pipelines asks for {@code ?kind=enrich&enabled=true&view=compiled}. The projection
 * is what separates the two readings — an operator gets the authored declaration by default, and only
 * the explicit {@code view=compiled} pays for resolution.
 *
 * <p><b>Authorization.</b> Registering, patching and deleting a pipeline requires
 * {@code REGISTRY_ADMIN}:
 * it declares what a table people read as fact will contain. <b>Reading</b> one needs only a mapped
 * {@code REGISTRY_READ}, which the {@code READ_ONLY_ADMIN} bundle holds, so it may list and read the
 * registry — a declaration is the
 * answer to "what populates this table, and is it running", which is the question that role exists to
 * answer. A read exposes the declaration in full, including the names of {@code sensitive} columns it
 * references and an {@code enrich} entry's own {@code transform}, its prompt template included; it
 * exposes no {@code sensitive} <em>value</em>: every field of the response is declaration, a count or a
 * timestamp bar two kinds of runtime field. The identity-valued state fields ({@code cursor_identity},
 * {@code materialized_through_identity}, and the {@code clamp}'s own {@code cursor_identity}) copy the
 * input's scan identity column, which can never be marked {@code sensitive} — {@code ScanMetadataRules}
 * refuses that at declaration and again on patch, precisely so the scan may project and page by it.
 * {@code last_error} is the upstream failure verbatim rather than a projection of the input; a driver
 * message that quotes the row it failed on is this read's one stated residual, kept because a stall the
 * observing role cannot diagnose is the worse trade. See the {@code pipeline-registry} spec, "A pipeline
 * read carries no row values" — the invariant holds by the shape of the response, never by filtering it
 * per caller.
 *
 * <p>The operator actions on the same prefix require {@code REGISTRY_ADMIN} too, and live on
 * {@link PipelineDataController}: {@code POST …/{name}/preview} executes the declared query over real
 * rows — the point at which sensitive values would actually flow — and {@code POST …/{name}/refresh}
 * writes them. Neither reports registered state, so neither follows the read.
 */
@Slf4j
@RestController
@RequestMapping("/v1/pipelines")
@RequiredArgsConstructor
@Tag(name = "Pipelines", description = "Declare and operate pipelines: in-DB rollups and enrichments.")
public class PipelineController {

    private final PipelineService service;
    private final PipelineFreshnessDiagnostics freshness;
    private final PipelineJsonReadNotes jsonReadNotes;
    private final PipelineMapper mapper;
    private final AuditPipelineSnapshotService snapshotService;

    @PostMapping
    @RequiresPrivilege(Privilege.REGISTRY_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a pipeline",
            description = "Stores a pipeline. Only `name`, `kind` and `target` are required: every other "
                    + "declaration member may be authored later by PATCH while the pipeline is disabled. The "
                    + "gate its `kind` selects runs when the pipeline is armed — by this call for an `enrich` "
                    + "pipeline registered with `enabled: true`, else by the PATCH that enables it — and on "
                    + "preview of a disabled pipeline. An `aggregate` pipeline always lands disabled: preview "
                    + "it, then enable it via PATCH. A field belonging to the other kind is rejected with 422 "
                    + "rather than ignored.")
    public PipelineDto create(@Valid @RequestBody CreatePipelineRequest request) {
        final PipelineEntity created = service.create(mapper.toRegistration(request));
        return withNotes(created);
    }

    @GetMapping
    @RequiresPrivilege(Privilege.REGISTRY_READ)
    @Operation(summary = "List pipelines",
            description = "Returns registered pipelines, optionally narrowed by target, kind, enabled, and "
                    + "a lower bound on updated_at — every filter optional, all applied together. With no "
                    + "enabled filter this is the whole registry, disabled pipelines included: a listing "
                    + "reports what is registered, not only what is running — including drafts, disabled "
                    + "pipelines whose declaration is still incomplete and which run nothing until armed. "
                    + "Completeness is computed, not stored, so there is no filter for it; `enabled=false` "
                    + "is the closest proxy. A `view=compiled` listing omits an entry whose declaration "
                    + "cannot be compiled rather than serving it with empty members. "
                    + "`kind=enrich&enabled=true&view=compiled` is what the Enrichment Runner's sync "
                    + "passes, and an `enrich` entry then carries five derived members beside the authored "
                    + "declaration: the resolved `response_schema` the model is held to, the derived grain "
                    + "key, version column and output mapping, and the resolved read source; without "
                    + "`view=compiled` the same listing is the authored declaration and costs no "
                    + "resolution. Ordered `updated_at` then `name`, so the order is stable across calls "
                    + "over an unchanged registry.")
    // Named for the generated OpenAPI rather than for the class: springdoc derives operationId from the
    // method name, and a bare list() is one of six in this package, leaving them to be told apart by a
    // scan-order-dependent list_N suffix.
    public PipelineListDto listPipelines(
            @Parameter(description = "Only pipelines writing this table")
            @RequestParam(required = false) String target,
            @Parameter(description = "Only pipelines of this kind", example = "enrich")
            @RequestParam(required = false) PipelineKind kind,
            @Parameter(description = "Narrow to enabled (true) or disabled (false); omit for all",
                    example = "true")
            @RequestParam(name = "enabled", required = false) Boolean enabled,
            @Parameter(description = "ISO-8601 lower bound on updated_at", example = "2026-07-01T00:00:00Z")
            @RequestParam(name = "updated_since", required = false) String updatedSince,
            @Parameter(description = "Which projection to return: `source` (default) - the authored "
                    + "declaration - or `compiled`, the resolved executable form the Enrichment Runner "
                    + "reads. `compiled` requires `kind=enrich` and is refused with 400 without it, "
                    + "rather than answering with unresolved entries under the compiled name.",
                    example = "source")
            @RequestParam(name = "view", required = false, defaultValue = "source") PipelineView view) {
        final PipelineEnabledFilter filter = filterOf(enabled);
        final Instant since = parseInstant(updatedSince);
        final PipelineView projection = viewOf(view);
        // The enrich feed is resolved (response schema, grain key, version column, output mapping, read
        // source) in a constant number of queries; a cross-kind or aggregate listing is not, because
        // resolving an aggregate pipeline would cost lookups for fields it has none of. Every other
        // filter — target included — rides along resolved, so narrowing the listing cannot quietly
        // narrow each entry too.
        //
        // Gated on the projection as well as the kind: `source` serves nothing that resolution produces,
        // so resolving for it would buy a discarded result at the price of the extra round trips this
        // branch exists to keep constant. That makes `?kind=enrich` alone a cheap authored listing, and
        // `?kind=enrich&view=compiled` the runner's sync feed — which is the one the runner asks for.
        //
        // The other half of that gate is a REFUSAL, not a fall-through: `view=compiled` without
        // `kind=enrich` used to answer 200 with the entries unresolved under the compiled name, which is
        // the same defect class as an ignored `view` value and a worse instance of it — the shape is
        // plausible rather than obviously wrong, carrying no derived `response_schema`, no `grain_key`, no
        // `version_column` and no `outputs`, indistinguishable by a consumer from an `enrich` pipeline
        // that genuinely has none. That is precisely the shape a consumer turns into a silent data loss.
        requireCompilableListing(kind, projection);
        if (kind == PipelineKind.ENRICH && projection == PipelineView.COMPILED) {
            final List<ResolvedEnrichPipeline> resolved =
                    service.listEnrich(StringUtils.trimToNull(target), filter, since);
            return mapper.toResolvedListDto(resolved,
                    freshness.of(resolved.stream().map(ResolvedEnrichPipeline::pipeline).toList()),
                    projection);
        }
        final List<PipelineEntity> pipelines = service.list(target, kind, filter, since);
        return mapper.toListDto(pipelines, freshness.of(pipelines), projection);
    }

    @GetMapping("/{name}")
    @RequiresPrivilege(Privilege.REGISTRY_READ)
    @Operation(summary = "Get a pipeline",
            description = "Returns the declaration plus its read-only runtime state. Read with "
                    + "`view=compiled`, an `enrich` pipeline additionally carries the resolved "
                    + "`response_schema` the model is held to, its derived grain key, version column and "
                    + "output mapping, and its resolved read source; the ADAS-driven cursor and schedule "
                    + "members are absent for it on either projection, because its scan position is held "
                    + "by the Enrichment Runner. A `view=compiled` read of a declaration that cannot be "
                    + "compiled — one still missing a member its kind requires, or a complete one whose "
                    + "outputs compose to no response schema — is refused with 422 "
                    + "`pipeline_validation_failed` naming the problem.")
    public PipelineDto get(@Parameter(description = "Pipeline name") @PathVariable String name,
            @Parameter(description = "Which projection to return: `source` (default) or `compiled`. "
                    + "`compiled` resolves only for the `enrich` kind and is refused with 422 for any "
                    + "other, rather than answering with the declaration under the compiled name.",
                    example = "source")
            @RequestParam(name = "view", required = false, defaultValue = "source") PipelineView view) {
        final PipelineEntity pipeline = service.get(name);
        final PipelineView projection = viewOf(view);
        if (projection == PipelineView.COMPILED && pipeline.getKind() != PipelineKind.ENRICH) {
            // Refused rather than downgraded, for the reason listPipelines' own gate now states: a
            // requested projection this read cannot honour is answered with an error, never with the
            // unresolved object under the compiled name. 422 and `pipeline_validation_failed` — the same
            // shape as every other endpoint that serves one kind.
            throw new PipelineValidationException("pipeline '" + name + "' is of kind '"
                    + pipeline.getKind().code() + "', which resolves no compiled projection; view=compiled "
                    + "serves the '" + PipelineKind.ENRICH.code() + "' kind, and the declaration of any "
                    + "kind is served by view=source");
        }
        return withFreshness(pipeline, projection);
    }

    @GetMapping("/{name}/revision/{revision}")
    @RequiresPrivilege(Privilege.REGISTRY_READ)
    @Operation(summary = "Get a pipeline as it stood at a revision",
            description = "Returns the pipeline's own stored fields reconstructed from the audit trail at "
                    + "the end of the given revision. 404 if the pipeline did not exist at that revision or "
                    + "the revision is unknown. Revision ids come from the activity feed. Gated the same as "
                    + "the live read: any mapped application role.")
    public PipelineDto snapshot(
            @Parameter(description = "Pipeline name") @PathVariable String name,
            @Parameter(description = "Revision id, as carried by an activity") @PathVariable String revision) {
        return mapper.toDto(snapshotService.snapshot(name, AuditSnapshotGuards.parseRevision(revision)));
    }

    @PatchMapping("/{name}")
    @RequiresPrivilege(Privilege.REGISTRY_ADMIN)
    @Operation(summary = "Patch a pipeline",
            description = "Enables or disables it, and/or re-declares it. A declaration change replaces the "
                    + "stored declaration (an omitted member is cleared; an omitted `target` is kept). One that "
                    + "leaves the pipeline enabled must supply the whole declaration and is validated in full; "
                    + "one that leaves it disabled is a draft save and is stored as given. Enabling runs the "
                    + "declaration gate over the stored declaration. Whether a declaration change is allowed "
                    + "while the pipeline is enabled depends "
                    + "on the kind: refused with 409 for `aggregate`, whose target would then hold rows "
                    + "computed two different ways; permitted for `enrich`, whose target is a grain-keyed "
                    + "replace-by-grain table where that is already how it behaves.")
    public PipelineDto patch(
            @Parameter(description = "Pipeline name") @PathVariable String name,
            @Valid @RequestBody PatchPipelineRequest request) {
        final PipelineEntity stored = service.get(name);
        final PipelineEntity patched = service.patch(name,
                mapper.toRegistration(stored, request, () -> service.patchedTargetParentOf(stored, request.target())),
                request.enabled());
        return withNotes(patched);
    }

    @DeleteMapping("/{name}")
    @RequiresPrivilege(Privilege.REGISTRY_ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a pipeline",
            description = "Removes the process. The target table and its rows are left intact — a target "
                    + "that stops being fed is stale, not invalid.")
    public void delete(@Parameter(description = "Pipeline name") @PathVariable String name) {
        service.delete(name);
    }

    /**
     * Binds {@code enabled} strictly, in place of Spring's lenient boolean conversion. That converter
     * accepts {@code 1}/{@code yes}/{@code on} and their negatives, and — the case that matters — maps an
     * <em>empty</em> value to {@code null}: {@code ?enabled=}, which is what a caller interpolating an
     * unset variable sends, would then fall through to {@link PipelineEnabledFilter#ANY} and report the
     * whole registry under what looks like a filter. That is exactly the silent mis-answer the filter
     * exists to prevent, so anything but {@code true} or {@code false} is refused here; the
     * {@link IllegalArgumentException} surfaces as 400 {@code bad_request}. Scoped to this one parameter
     * by name, so nothing else this controller binds is affected.
     */
    @InitBinder("enabled")
    void bindEnabledStrictly(WebDataBinder binder) {
        binder.registerCustomEditor(Boolean.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                if (!"true".equals(text) && !"false".equals(text)) {
                    throw new InvalidRequestParameterException("enabled must be exactly 'true' or 'false'");
                }
                setValue(Boolean.valueOf(text));
            }
        });
    }

    /**
     * Binds {@code kind} by its <b>wire</b> spelling. Spring's enum converter is
     * {@code Enum.valueOf(name)} and so case-sensitive on the constant name, while a pipeline kind is
     * published lower-case ({@code aggregate}, {@code enrich}) — so {@code ?kind=enrich}, the only form a
     * client has ever seen, would otherwise fail to convert and answer 400 for a value the API documents.
     *
     * <p>An unrecognised kind stays a 400 rather than the 422 an unimplemented kind gets in a request
     * <em>body</em>, and the difference is deliberate: in a body the kind is part of a declaration this
     * capability governs, while here it is a filter that will not convert to its declared type — which is
     * a bad request service-wide.
     */
    @InitBinder("kind")
    void bindKindByWireCode(WebDataBinder binder) {
        binder.registerCustomEditor(PipelineKind.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                setValue(Arrays.stream(PipelineKind.values())
                        .filter(kind -> kind.code().equalsIgnoreCase(text))
                        .findFirst()
                        .orElseThrow(() -> new InvalidRequestParameterException("kind must be one of "
                                + Arrays.stream(PipelineKind.values()).map(PipelineKind::code).toList())));
            }
        });
    }

    /**
     * Binds {@code view} by its <b>wire</b> spelling, and refuses anything else.
     *
     * <p>The same rule the other two filters follow, for the same reason: Spring's enum converter is
     * case-sensitive on the constant name, and a value that will not convert must be a 400 naming the
     * accepted set rather than falling through to a default. A silently-ignored projection selector would
     * answer with a different <em>shape</em> than the caller asked for — the worst version of the
     * silent-mis-answer failure, because the members it wanted would simply be absent.
     */
    @InitBinder("view")
    void bindViewByWireCode(WebDataBinder binder) {
        binder.registerCustomEditor(PipelineView.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                setValue(Arrays.stream(PipelineView.values())
                        .filter(view -> view.code().equalsIgnoreCase(text))
                        .findFirst()
                        .orElseThrow(() -> new InvalidRequestParameterException("view must be one of "
                                + Arrays.stream(PipelineView.values()).map(PipelineView::code).toList())));
            }
        });
    }

    /**
     * The {@code enabled} filter, {@link PipelineEnabledFilter#ANY} when the parameter is absent — and only
     * when it is absent, since {@link #bindEnabledStrictly} refuses every value that is not {@code true} or
     * {@code false} rather than letting one fall through to a filter the caller did not ask for.
     */
    private static PipelineEnabledFilter filterOf(Boolean enabled) {
        if (enabled == null) {
            return PipelineEnabledFilter.ANY;
        }
        return enabled ? PipelineEnabledFilter.ENABLED : PipelineEnabledFilter.DISABLED;
    }

    /**
     * Refuses {@code ?view=compiled} on a listing that is not narrowed to the {@code enrich} kind.
     *
     * <p>400 rather than 422, and {@code bad_request} rather than a pipeline code, because what is wrong is
     * the <em>combination of query parameters</em> — nothing about any stored pipeline. Resolving a
     * cross-kind listing would cost a per-pipeline lookup for entries that have nothing to resolve, which
     * is why {@code kind=enrich} is required rather than inferred; a caller who wants everything asks for
     * {@code view=source}, which serves every kind.
     */
    private static void requireCompilableListing(PipelineKind kind, PipelineView view) {
        if (view == PipelineView.COMPILED && kind != PipelineKind.ENRICH) {
            throw new InvalidRequestParameterException("view=compiled requires kind="
                    + PipelineKind.ENRICH.code() + ": only that kind resolves a compiled projection, and a "
                    + "listing across kinds has nothing to compile for the others. Ask for "
                    + "kind=" + PipelineKind.ENRICH.code() + "&view=compiled, or for view=source");
        }
    }

    private static Instant parseInstant(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new QueryValidationException("updated_since must be an ISO-8601 instant");
        }
    }

    /**
     * The write responses: one pipeline with its freshness diagnostics attached, plus the advisory notes a
     * read does not carry — see {@link PipelineJsonReadNotes}. A note describes a declaration that has
     * <b>already been accepted</b>, so it is computed after the gate and reported beside what was stored; it
     * cannot refuse anything, and a failure to compute one degrades to no notes rather than to an error
     * about a write that succeeded.
     *
     * <p>A write answers with {@code SOURCE}: what was just declared, rather than a resolution of it.
     */
    private PipelineDto withNotes(PipelineEntity pipeline) {
        final List<String> notes = jsonReadNotes.of(pipeline);
        if (!notes.isEmpty()) {
            log.info("Pipeline '{}' accepted with {} advisory note(s): {}",
                    pipeline.getName(), notes.size(), notes);
        }
        return mapper.toDto(pipeline, null, freshness.of(pipeline), PipelineView.SOURCE, notes);
    }

    /**
     * Resolution is skipped for {@code SOURCE}, which keeps the authored read cheap: it costs the
     * Postgres round trips that produce values the projection then discards.
     *
     * <p>It is <b>not</b> what keeps the projection honest, and an earlier version of this note claimed it
     * was. The mapper gates every derived member on the projection as well as on having a resolution to
     * serve, so purity holds even for a caller that has one in hand for another reason. Skipping the
     * lookup here and gating there are two independent reasons the same thing cannot leak, and only the
     * second of them is a property of the response rather than of one call site.
     */
    private PipelineDto withFreshness(PipelineEntity pipeline, PipelineView view) {
        final ResolvedEnrichPipeline resolved =
                pipeline.getKind() == PipelineKind.ENRICH && view == PipelineView.COMPILED
                        ? service.getEnrichCompiled(pipeline.getName())
                        : null;
        return mapper.toDto(pipeline, resolved, freshness.of(pipeline), view);
    }

    /**
     * The projection to serve, {@link PipelineView#SOURCE} when {@code view} is absent.
     *
     * <p>The authored declaration is the default because every reader that is not the Enrichment Runner
     * wants it — a human with a {@code curl}, MCP, the UI — and because a default that answers with
     * derived values invites a GET → PATCH round trip to re-declare them, laundering a resolved input
     * into a declared one. The runner names {@code view=compiled} explicitly; it is a single known client
     * shipping in the same release, not a population to be defaulted for.
     *
     * <p><b>The {@code null} branch is no longer reached from HTTP</b>, and is kept deliberately. Both
     * {@code view} parameters now declare {@code defaultValue = "source"}, so Spring binds the default
     * before the handler runs — which is the point, because that is what publishes the default in the
     * OpenAPI document instead of leaving it to a code branch no consumer can read. This method stays as
     * the guard for any caller that does not arrive through the binder, and because deleting a defensive
     * branch on the grounds that one caller stopped reaching it is how the branch gets rediscovered.
     */
    private static PipelineView viewOf(PipelineView view) {
        return view == null ? PipelineView.SOURCE : view;
    }
}
