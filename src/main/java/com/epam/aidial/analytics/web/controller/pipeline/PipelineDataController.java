package com.epam.aidial.analytics.web.controller.pipeline;

import com.epam.aidial.analytics.exception.PipelineNotFoundException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.EnrichPreviewService;
import com.epam.aidial.analytics.service.enrichment.EnrichWriteService;
import com.epam.aidial.analytics.service.enrichment.GroupFetchService;
import com.epam.aidial.analytics.service.enrichment.MaterializeService;
import com.epam.aidial.analytics.service.enrichment.ScanService;
import com.epam.aidial.analytics.service.enrichment.model.GroupPage;
import com.epam.aidial.analytics.service.enrichment.model.MaterializePage;
import com.epam.aidial.analytics.service.enrichment.model.ScanPage;
import com.epam.aidial.analytics.service.enrichment.support.RunnerContactMetrics;
import com.epam.aidial.analytics.service.enrichment.support.RunnerContract;
import com.epam.aidial.analytics.service.pipeline.AggregateRefreshService;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.model.RefreshOutcome;
import com.epam.aidial.analytics.web.contract.RunnerContractExempt;
import com.epam.aidial.analytics.web.dto.enrichment.GroupFetchRequest;
import com.epam.aidial.analytics.web.dto.enrichment.GroupFetchResponse;
import com.epam.aidial.analytics.web.dto.enrichment.MaterializeRequest;
import com.epam.aidial.analytics.web.dto.enrichment.MaterializeResponse;
import com.epam.aidial.analytics.web.dto.enrichment.ScanRequest;
import com.epam.aidial.analytics.web.dto.enrichment.ScanResponse;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.PreviewRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineResponses.PreviewDto;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineResponses.RefreshDto;
import com.epam.aidial.analytics.web.dto.table.WriteRowsRequest;
import com.epam.aidial.analytics.web.dto.table.WriteRowsResponse;
import com.epam.aidial.analytics.web.mapper.pipeline.PipelineMapper;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * The pipeline data plane: execution, as against the registry management {@link PipelineController}
 * owns. Two concerns with different failure modes, and — since the fold — two different <b>callers</b>,
 * which is the more consequential split:
 *
 * <ul>
 *   <li><b>Operator surfaces</b>, {@code REGISTRY_ADMIN} like the registry's <em>mutations</em> — its
 *       reads
 *       need only {@code REGISTRY_READ}, and these do not follow them because each executes the
 *       declaration over real rows rather than reporting registered state: {@code preview} (the dry-run
 *       gate, for either kind) and {@code refresh} (run one window now, {@code aggregate} only).
 *   <li><b>Runner surfaces</b>, reachable by any authenticated identity holding the target enrichment's
 *       WRITE grant: {@code scan}, {@code group-rows}, {@code materialize} and {@code rows}. The security
 *       chain pins these four paths as {@code authenticated()} <em>before</em> the admin rule, so the
 *       Enrichment Runner does not need global admin; the per-call authorization is the grant check in the
 *       service layer, which also holds in {@code none} mode where method security is inert.
 * </ul>
 *
 * <p><b>The four runner endpoints additionally require the {@code Adas-Contract-Version} header</b>, and
 * are refused 409 without a matching one — see {@code RunnerContractInterceptor}, which is deny-by-default
 * over every {@code POST} on this class except the two marked {@link RunnerContractExempt}. So the split
 * above is enforced by three independent mechanisms that must agree: {@code @RequiresPrivilege(REGISTRY_ADMIN)} here, the
 * four {@code authenticated()} matchers in {@code OidcSecurityConfiguration}, and that interceptor.
 *
 * <p>The four runner endpoints resolve everything from the stored declaration (its already-validated
 * input, filter and grain, and its target enrichment), so the runner never names a table, a filter, or a
 * column set. A declaration that reads a {@code sensitive} column additionally requires the calling
 * identity to be entitled to see sensitive columns, re-checked at each read or derive. The two read
 * surfaces ({@code scan}, {@code group-rows}) require the pipeline to exist but not to be enabled, though a
 * disabled one must declare what they compile from; the two write surfaces ({@code materialize},
 * {@code rows}) refuse a disabled pipeline with 409, since a disabled pipeline writes to its target by no
 * route.
 *
 * <p><b>Each of the four records the runner's contact</b> ({@link RunnerContactMetrics}) once its handler
 * has succeeded. Here rather than in the four services: the metric's statement is that the runner
 * <em>successfully</em> called, so it has to be recorded after the outcome is known, and this is the one
 * point that is both after the outcome and common to all four.
 */
@Slf4j
@RestController
@RequestMapping("/v1/pipelines/{name}")
@RequiredArgsConstructor
@Tag(name = "Pipelines", description = "Declare and operate pipelines: in-DB rollups and enrichments.")
public class PipelineDataController {

    private static final PreviewRequest NO_PREVIEW_BODY = new PreviewRequest(null, null, null);

    /**
     * The {@code Adas-Contract-Version} request header, described once for the four runner-only surfaces
     * that carry it. A constant rather than four copies of the same paragraph: they document one rule, and
     * four spellings of it would drift the way the published OpenAPI document is least likely to be
     * re-read. See {@code RunnerContractInterceptor}, which enforces it.
     */
    private static final String CONTRACT_HEADER_DOC =
            "The ADAS <-> Enrichment Runner contract version the caller was built against, compared for "
                    + "equality. Absent -> 409 `runner_contract_required`; different -> 409 "
                    + "`runner_contract_mismatch`. Every `/v1/pipelines` response echoes the version this "
                    + "build serves in the same header, so a caller that sees no echo is talking to an "
                    + "ADAS older than the contract and must not act on what it read.";

    /** The 409 those two codes render as, described once for the same four surfaces. */
    private static final String CONTRACT_REFUSAL_DOC =
            "Contract version missing (`runner_contract_required`) or not the one this build serves "
                    + "(`runner_contract_mismatch`); nothing was read, computed or written";

    /** The 409 of the two writing surfaces, which also refuse a disabled pipeline. */
    private static final String WRITE_REFUSAL_DOC = CONTRACT_REFUSAL_DOC
            + "; or the pipeline is disabled (`pipeline_conflict`), and nothing was written";

    private final PipelineService pipelines;
    private final PipelineMapper mapper;
    private final AggregateRefreshService refreshService;
    private final EnrichPreviewService enrichPreviewService;
    private final ScanService scanService;
    private final GroupFetchService groupFetchService;
    private final MaterializeService materializeService;
    private final EnrichWriteService writeService;
    private final RunnerContactMetrics contactMetrics;

    /** The writer hold of an automatic relayout, on the endpoints that write a target's rows. */
    private static final String RELAYOUT_HOLD_DOC = "`table_relayout_in_progress`: the target is being relaid out by "
            + "ADAS and holds its writers for a minute or two; retry the same request shortly";

    @PostMapping("/preview")
    @RequiresPrivilege(Privilege.REGISTRY_ADMIN)
    @RunnerContractExempt
    @ApiResponse(responseCode = "200", description = "The sample, its typed columns and coded notes")
    @ApiResponse(responseCode = "400", description = "A malformed body, or one carrying an unknown member such "
            + "as `cursor` (`bad_request`)")
    @ApiResponse(responseCode = "403", description = "The caller may not read a `sensitive` column the "
            + "declaration reads (`sensitive_column_not_entitled`), is row-confined (`row_policy_confined`), lacks the "
            + "target's grant (`pipeline_access_denied`), or lacks `REGISTRY_ADMIN` (`forbidden`)")
    @ApiResponse(responseCode = "404", description = "No such pipeline (`unknown_pipeline`)")
    @ApiResponse(responseCode = "409", description = "A conflict arming would also report (`pipeline_conflict`, `table_is_system`)")
    @ApiResponse(responseCode = "422", description = "The declaration would not arm, or the body does not fit "
            + "the pipeline: any body on an `aggregate`, `group_key` on a row-grain or Class-1 one, `from`/`to` on a "
            + "`group` one (`pipeline_validation_failed`, one problem at a time), or a read exceeded the preview's budget (`pipeline_preview_budget_exceeded`)")
    @Operation(summary = "Preview a pipeline (dry run)",
            description = "Writes nothing, calls no model, and is allowed on a disabled pipeline — this is the "
                    + "checkpoint between declaring a pipeline and letting it write. A disabled pipeline is "
                    + "refused exactly as arming it would be refused; an enabled `aggregate` is re-gated too, an "
                    + "enabled `enrich` is not.\n\n"
                    + "**Both kinds answer in one shape**, carrying only what a preview alone can know: `rows` "
                    + "(newest first; a group's members in assembly order), typed `columns`, the declaration as a "
                    + "portable `statement` (ADAS SQL) and `query` (structured-query DSL) a caller can run through "
                    + "the query API where there is one, "
                    + "`total_rows` (aggregate only: the number of rows the target holds once caught up), "
                    + "`group_key` (group pipelines) and coded `notes`. Nothing the pipeline read serves is "
                    + "echoed — read `GET /v1/pipelines/{name}` (and `?view=compiled` for an enrich pipeline) "
                    + "for the declaration beside it.\n\n"
                    + "For an `aggregate` pipeline the sample covers the ENTIRE input, deliberately unwindowed, "
                    + "and takes no body. For an `enrich` pipeline the sample is read through the governed scan, "
                    + "group-assembly or materialize path, so it shows only what the pipeline itself may read; "
                    + "no prompt is rendered, because the renderer lives in the Enrichment Runner. The optional "
                    + "body narrows the sample — `from`/`to` for a row-grain or Class-1 pipeline, `group_key` "
                    + "for a group one (without it, the newest group is sampled). Every value is capped at "
                    + "2048 bytes in `rows`.")
    public PreviewDto preview(
            @Parameter(description = "Pipeline name") @PathVariable String name,
            @RequestBody(required = false) PreviewRequest request) {
        final PreviewRequest body = request == null ? NO_PREVIEW_BODY : request;
        // Dispatched on the stored kind rather than guessed from the body, and enumerated rather than
        // defaulted: a fourth kind cannot be added without deciding here what its dry run is, where a
        // `default` would let one ship with the endpoint quietly answering for the wrong machinery. The
        // EXTERNAL arm is the "no dry run for this kind" refusal the contract promises, and it is
        // unreachable through the registry — that kind is declared but never persisted — so it is
        // written as the honest 422 rather than as an assertion.
        final PipelineEntity pipeline = pipelines.get(name);        // 404 unknown_pipeline
        return switch (pipeline.getKind()) {
            case AGGREGATE -> aggregatePreview(name, body);
            case ENRICH -> mapper.toPreviewDto(
                    enrichPreviewService.preview(name, body.groupKey(), body.from(), body.to()));
            case EXTERNAL -> throw new PipelineValidationException("pipeline '" + name + "' is of kind '"
                    + pipeline.getKind().code() + "', which ADAS does not run and has no dry run for; "
                    + "preview serves the '" + PipelineKind.AGGREGATE.code() + "' and '"
                    + PipelineKind.ENRICH.code() + "' kinds");
        };
    }

    /**
     * The aggregate arm. A body is <b>refused</b> rather than ignored: its members narrow an {@code enrich}
     * sample, an aggregate sample is unwindowed by design, and a caller who sent one has a different pipeline
     * in mind than the one they named.
     */
    private PreviewDto aggregatePreview(String name, PreviewRequest body) {
        if (!body.isEmpty()) {
            throw new PipelineValidationException("pipeline '" + name + "' is of kind 'aggregate', whose "
                    + "preview takes no body — `group_key`, `from` and `to` narrow the sample of an enrich "
                    + "pipeline's input, while an aggregate sample covers the whole input deliberately; repeat "
                    + "the request with no body");
        }
        return mapper.toPreviewDto(refreshService.preview(name));
    }

    @PostMapping("/refresh")
    @RequiresPrivilege(Privilege.REGISTRY_ADMIN)
    @RunnerContractExempt
    @Operation(summary = "Refresh a pipeline now",
            description = "Runs one bounded window immediately: probes the window end, then recomputes in "
                    + "full every group key that window touched. Idempotent — repeating it writes the same "
                    + "values, which the target collapses. `has_more` reports whether input remains, so a "
                    + "backlog is drained by refreshing until it is false. Requires the pipeline to be "
                    + "enabled (409 otherwise): a disabled pipeline never writes, which is what makes the "
                    + "preview gate meaningful.")
    public RefreshDto refresh(@Parameter(description = "Pipeline name") @PathVariable String name) {
        final RefreshOutcome outcome = refreshService.refresh(name);
        return new RefreshDto(outcome.rowsWritten(), outcome.hasMore(), outcome.lagSeconds());
    }

    @PostMapping("/scan")
    @RequiresPrivilege(value = Privilege.TABLE_READ, deferred = true)
    @Parameter(in = ParameterIn.HEADER, name = RunnerContract.HEADER, required = true,
            description = CONTRACT_HEADER_DOC, example = RunnerContract.VERSION_VALUE)
    @ApiResponse(responseCode = "409", description = CONTRACT_REFUSAL_DOC)
    @Operation(summary = "Scan a page of source rows for an enrich pipeline",
            description = "Runs the pipeline's governed source-only keyset scan and returns a page plus an "
                    + "opaque next_cursor, has_more, and lag_seconds. Omit cursor to scan from the start; "
                    + "supply from/to to bound the scan to the version window (from, to]. For a row-trigger pipeline "
                    + "whose target is aligned with its source, every row also carries `__partition_v`, the source "
                    + "row's partition value, which the write-back takes back unchanged.")
    public ScanResponse scan(
            @Parameter(description = "Pipeline name") @PathVariable String name,
            @RequestBody(required = false) ScanRequest request) {
        final ScanRequest body = request == null ? new ScanRequest(null, null, null, null) : request;
        final ScanPage page = served(name, () -> scanService.scan(name, body.cursor(), body.limit(), body.from(), body.to()));
        return new ScanResponse(page.rows(), page.nextCursor(), page.hasMore(), page.lagSeconds());
    }

    @PostMapping("/group-rows")
    @RequiresPrivilege(value = Privilege.TABLE_READ, deferred = true)
    @Parameter(in = ParameterIn.HEADER, name = RunnerContract.HEADER, required = true,
            description = CONTRACT_HEADER_DOC, example = RunnerContract.VERSION_VALUE)
    @ApiResponse(responseCode = "409", description = CONTRACT_REFUSAL_DOC)
    @Operation(summary = "Assemble a group's member rows for a group-triggered pipeline",
            description = "Returns the source rows sharing the given group key that satisfy the pipeline's "
                    + "membership filter, in the source's total order, bounded by both a row cap and a "
                    + "response-byte cap (truncated reports either). The caller supplies only the group key. "
                    + "An unknown or empty group returns 200 with an empty rows array.")
    public GroupFetchResponse fetchGroup(
            @Parameter(description = "Pipeline name") @PathVariable String name,
            @Valid @RequestBody GroupFetchRequest request) {
        final GroupPage page = served(name, () -> groupFetchService.fetch(name, request.groupKey(), request.limit()));
        return new GroupFetchResponse(page.rows(), page.truncated());
    }

    @PostMapping("/materialize")
    @RequiresPrivilege(value = Privilege.TABLE_READ, deferred = true)
    @Parameter(in = ParameterIn.HEADER, name = RunnerContract.HEADER, required = true,
            description = CONTRACT_HEADER_DOC, example = RunnerContract.VERSION_VALUE)
    @ApiResponse(responseCode = "409", description = WRITE_REFUSAL_DOC)
    @ApiResponse(responseCode = "503", description = RELAYOUT_HOLD_DOC)
    @Operation(summary = "Materialize a window of a Class-1 (sql) pipeline",
            description = "Probes a bounded window of the pipeline's source, then writes its derived values "
                    + "for exactly that window with a single server-side INSERT … SELECT. Returns counts and "
                    + "cursor bookkeeping only — no row payload crosses the wire in either direction. "
                    + "Re-running the same cursor re-executes an identical window, which the target's "
                    + "ReplacingMergeTree collapses, so the effect is exactly-once. A disabled pipeline is "
                    + "refused with 409 `pipeline_conflict`: a disabled pipeline writes to its target by no "
                    + "route.")
    public MaterializeResponse materialize(
            @Parameter(description = "Pipeline name") @PathVariable String name,
            @RequestBody(required = false) MaterializeRequest request) {
        final MaterializeRequest body =
                request == null ? new MaterializeRequest(null, null, null, null) : request;
        final MaterializePage page = served(name,
                () -> materializeService.materialize(name, body.cursor(), body.from(), body.to(), body.limit()));
        return new MaterializeResponse(
                page.rowsWritten(), page.nextCursor(), page.hasMore(), page.lagSeconds());
    }

    @PostMapping("/rows")
    @RequiresPrivilege(value = Privilege.TABLE_WRITE, deferred = true)
    @Parameter(in = ParameterIn.HEADER, name = RunnerContract.HEADER, required = true,
            description = CONTRACT_HEADER_DOC, example = RunnerContract.VERSION_VALUE)
    @ApiResponse(responseCode = "409", description = WRITE_REFUSAL_DOC)
    @ApiResponse(responseCode = "503", description = RELAYOUT_HOLD_DOC)
    @Operation(summary = "Write enrichment rows for an enrich pipeline",
            description = "Validates the row column-set against the declaration's outputs (plus grain and "
                    + "provenance) and writes to the target enrichment; ClickHouse stamps _updated_at. A "
                    + "disabled pipeline is refused with 409 `pipeline_conflict`: a disabled pipeline writes to "
                    + "its target by no route. For a target aligned with its source, every row carries back the "
                    + "`__partition_v` its scan row returned; a row without it is refused with 422 `validation_error`.")
    public WriteRowsResponse writeRows(
            @Parameter(description = "Pipeline name") @PathVariable String name,
            @Valid @RequestBody WriteRowsRequest request) {
        final int inserted = served(name, () -> writeService.write(name, request.rows()));
        return new WriteRowsResponse(inserted);
    }

    /**
     * Runs one data-plane call and records it on the runner series: an attempt whatever the outcome, and a
     * contact only when it succeeded.
     *
     * <p><b>No lookup on the success path.</b> A call that returned resolved {@code name} as an existing enrich
     * pipeline on the way, so it is recorded as it stands. Only a refused call asks the registry whether the name
     * is an existing, <em>enabled</em> enrich pipeline, so that a caller-chosen path segment never creates a
     * series and a refusal of an unknown name ({@code 404}) costs nothing. A disabled pipeline's refusal is not
     * an attempt: disabling drops the pipeline's series, and one re-created by a straggling call would climb for
     * a pipeline nothing is meant to call. A contract refusal never gets here, because
     * {@code RunnerContractInterceptor} decides it in {@code preHandle}.
     */
    private <T> T served(String name, Supplier<T> call) {
        final T result;
        try {
            result = call.get();
        } catch (RuntimeException ex) {
            if (!(ex instanceof PipelineNotFoundException) && isEnabledEnrich(name)) {
                contactMetrics.recordAttempt(name);
            }
            throw ex;
        }
        contactMetrics.recordAttempt(name);
        contactMetrics.recordContact(name);
        return result;
    }

    /** The lookup a refused call's attempt waits on; a failure of the lookup itself records nothing. */
    private boolean isEnabledEnrich(String name) {
        try {
            return pipelines.isEnabledEnrich(name);
        } catch (RuntimeException lookup) {
            log.debug("Could not resolve pipeline '{}' for the runner attempt series: {}", name,
                    lookup.getClass().getSimpleName());
            return false;
        }
    }
}
