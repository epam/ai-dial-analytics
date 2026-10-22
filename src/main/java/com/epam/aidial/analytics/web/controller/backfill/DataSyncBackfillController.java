package com.epam.aidial.analytics.web.controller.backfill;

import com.epam.aidial.analytics.exception.InvalidRequestParameterException;
import com.epam.aidial.analytics.service.BackfillRun;
import com.epam.aidial.analytics.service.DataBackfillService;
import com.epam.aidial.analytics.web.dto.backfill.BackfillRequest;
import com.epam.aidial.analytics.web.dto.backfill.BackfillRunListResponse;
import com.epam.aidial.analytics.web.dto.backfill.BackfillStatusResponse;
import com.epam.aidial.analytics.web.dto.backfill.BackfillTriggerResponse;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.beans.PropertyEditorSupport;
import java.util.Arrays;
import java.util.Locale;

/**
 * Operator-triggered backfill of DIAL usage logs over a bounded UTC date range. {@code POST} starts an
 * asynchronous run and returns {@code 202} with a run id; {@code GET /{runId}} reports its status, and the
 * bare {@code GET} lists the runs this instance retains, so a caller without the run id can find the active one.
 * This is a deliberately HTTP-triggered path, distinct from the in-process scheduled and poller ingestion.
 */
@RestController
@RequestMapping("/v1/data-sync/backfill")
@RequiredArgsConstructor
@Tag(name = "Data-sync backfill", description = "Operator-triggered backfill of a bounded UTC date range.")
public class DataSyncBackfillController {

    private final DataBackfillService service;

    @PostMapping
    @RequiresPrivilege(Privilege.DATA_SYNC_OPERATE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Trigger a backfill", description = "Starts an asynchronous backfill for an inclusive UTC date range. "
            + "Blobs already ingested are skipped unless `includeIngested` is `true`.")
    @ApiResponse(
            responseCode = "202",
            description = "Accepted; backfill started",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = BackfillTriggerResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid range (missing, unparseable, inverted, future, or over the max span), "
            + "or an unreadable body, such as an `includeIngested` that is not a boolean")
    @ApiResponse(responseCode = "409", description = "A backfill run is already in progress")
    @ApiResponse(responseCode = "503", description = "Backfill is disabled")
    public BackfillTriggerResponse trigger(@RequestBody BackfillRequest request) {
        BackfillRun run = service.start(request.from(), request.to(), Boolean.TRUE.equals(request.includeIngested()));
        return BackfillTriggerResponse.of(run.snapshot());
    }

    @GetMapping
    @RequiresPrivilege(Privilege.REGISTRY_READ)
    @Operation(summary = "List backfill runs",
            description = "Returns the retained backfill runs, newest started first, each as `GET /{runId}` reports it. "
                    + "A runtime cache, not a history: at most the " + DataBackfillService.MAX_RETAINED_RUNS
                    + " most recently started runs, cleared on restart and "
                    + "redeploy, and per instance, so during a rolling-update overlap it can omit a run the other "
                    + "instance holds. `state=running` yields at most one entry. Not paginated.")
    @ApiResponse(
            responseCode = "200",
            description = "Retained backfill runs (an empty `runs` array when none match)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = BackfillRunListResponse.class)))
    @ApiResponse(responseCode = "400", description = "Unknown or empty `state`")
    public BackfillRunListResponse listBackfillRuns(
            @Parameter(description = "Only runs in this state; omit for every retained run",
                    schema = @Schema(type = "string", allowableValues = {"running", "completed", "failed"}))
            @RequestParam(name = "state", required = false) BackfillRun.State state) {
        return BackfillRunListResponse.of(service.list(state));
    }

    @GetMapping("/{runId}")
    @RequiresPrivilege(Privilege.REGISTRY_READ)
    @Operation(summary = "Get backfill status", description = "Returns the current status of a backfill run.")
    @ApiResponse(
            responseCode = "200",
            description = "Backfill status",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = BackfillStatusResponse.class)))
    @ApiResponse(responseCode = "404", description = "No backfill run with that id")
    public BackfillStatusResponse status(
            @Parameter(description = "Backfill run id") @PathVariable String runId) {
        return BackfillStatusResponse.of(service.status(runId));
    }

    /**
     * Binds {@code state} by its <b>wire</b> spelling, the lower-case form the status reports. Spring's enum
     * converter is {@code Enum.valueOf(name)} and so case-sensitive on the constant name, so {@code ?state=running}
     * would otherwise fail to convert. A value outside the set, including an empty one, is a 400 naming the accepted set.
     */
    @InitBinder("state")
    void bindStateByWireCode(WebDataBinder binder) {
        binder.registerCustomEditor(BackfillRun.State.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                setValue(Arrays.stream(BackfillRun.State.values())
                        .filter(state -> wireCode(state).equalsIgnoreCase(text))
                        .findFirst()
                        .orElseThrow(() -> new InvalidRequestParameterException("state must be one of "
                                + Arrays.stream(BackfillRun.State.values()).map(DataSyncBackfillController::wireCode).toList())));
            }
        });
    }

    private static String wireCode(BackfillRun.State state) {
        return state.name().toLowerCase(Locale.ROOT);
    }
}
