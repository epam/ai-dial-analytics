package com.epam.aidial.analytics.web.controller.datasync;

import com.epam.aidial.analytics.exception.InvalidRequestParameterException;
import com.epam.aidial.analytics.service.IngestFailureFilter;
import com.epam.aidial.analytics.service.IngestFailureKind;
import com.epam.aidial.analytics.service.IngestFailureReason;
import com.epam.aidial.analytics.service.IngestFailureService;
import com.epam.aidial.analytics.service.IngestFailureState;
import com.epam.aidial.analytics.web.dto.datasync.IngestFailureListResponse;
import com.epam.aidial.analytics.web.dto.datasync.IngestFailureRetryRequest;
import com.epam.aidial.analytics.web.dto.datasync.IngestFailureRetryResponse;
import com.epam.aidial.analytics.web.dto.datasync.RetrySweepView;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The operator surface of the failure ledger: lists the blobs and days that failed to ingest and are still
 * outstanding, requeues them for the retry sweep, and reports whether that sweep runs (see the
 * {@code data-sync-failures} spec).
 */
@RestController
@RequestMapping("/v1/data-sync/failures")
@RequiredArgsConstructor
@Tag(name = "Data-sync failures", description = "Failed ingests still outstanding, and requeueing them for retry.")
public class DataSyncFailuresController {

    /** The largest page and the most subjects one requeue may name. */
    static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_LIMIT = 100;
    private static final List<String> KINDS = Arrays.stream(IngestFailureKind.values()).map(IngestFailureKind::wire).toList();

    private final IngestFailureService service;

    @GetMapping
    @RequiresPrivilege(Privilege.REGISTRY_READ)
    @Operation(summary = "List outstanding failures",
            description = "Returns the failed blobs and days that are still pending or parked, newest failure first, with how many "
                    + "matched in all and per state, and whether the retry sweep runs. Resolved subjects are not listed.")
    @ApiResponse(responseCode = "200", description = "Outstanding failures (an empty `failures` array when none match)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IngestFailureListResponse.class)))
    @ApiResponse(responseCode = "400", description = "An unknown, empty or unparseable filter, an inverted range, or `limit` outside 1..1000")
    @ApiResponse(responseCode = "503", description = "ClickHouse is unavailable")
    public IngestFailureListResponse list(
            @Parameter(description = "`pending` or `parked`") @RequestParam(name = "state", required = false) String state,
            @Parameter(description = "`blob`, `day` or `records`") @RequestParam(name = "kind", required = false) String kind,
            @Parameter(description = "Reason code of the latest failure") @RequestParam(name = "reason", required = false) String reason,
            @Parameter(description = "Inclusive lower bound on the day (ISO date)") @RequestParam(name = "from", required = false) String from,
            @Parameter(description = "Inclusive upper bound on the day (ISO date)") @RequestParam(name = "to", required = false) String to,
            @Parameter(description = "A backfill run that failed the subject since it was last resolved")
            @RequestParam(name = "backfill_run_id", required = false) String backfillRunId,
            @Parameter(description = "Most entries returned, 1..1000 (default 100)") @RequestParam(name = "limit", required = false) String limit) {
        IngestFailureFilter filter = filter(kind, state, reason, from, to, null, backfillRunId, null);
        return IngestFailureListResponse.of(service.list(filter, limit(limit)), service.retrySweepStatus());
    }

    @PostMapping("/retry")
    @RequiresPrivilege(Privilege.DATA_SYNC_OPERATE)
    @Operation(summary = "Requeue failures",
            description = "Requeues every outstanding failure the body selects, resetting its attempts, so the next retry sweep "
                    + "attempts it, and reports whether that sweep runs. Members are ANDed; at least one is required.")
    @ApiResponse(responseCode = "200", description = "How many failures were requeued",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IngestFailureRetryResponse.class)))
    @ApiResponse(responseCode = "400", description = "An empty body, an unknown member, an invalid filter, more than 1000 subjects or targets, "
            + "or a null or blank subject or target")
    @ApiResponse(responseCode = "503", description = "ClickHouse is unavailable")
    public IngestFailureRetryResponse retry(@RequestBody IngestFailureRetryRequest request) {
        IngestFailureFilter filter = retryFilter(request);
        return new IngestFailureRetryResponse(service.requeue(filter), RetrySweepView.of(service.retrySweepStatus()));
    }

    private static IngestFailureFilter retryFilter(IngestFailureRetryRequest request) {
        if (request.kind() == null && request.state() == null && request.reason() == null && request.from() == null
                && request.to() == null && request.subjects() == null && request.backfillRunId() == null && request.targets() == null) {
            throw new InvalidRequestParameterException(
                    "the body must name at least one of kind, state, reason, from, to, subjects, backfill_run_id, targets");
        }
        if (request.subjects() != null && request.subjects().size() > MAX_LIMIT) {
            throw new InvalidRequestParameterException("subjects must hold at most " + MAX_LIMIT + " entries");
        }
        if (request.subjects() != null && request.subjects().stream().anyMatch(subject -> subject == null || subject.isBlank())) {
            throw new InvalidRequestParameterException("subjects must not hold a null or blank entry");
        }
        Set<IngestFailureFilter.Target> targets = targets(request.targets());
        return filter(request.kind(), request.state(), request.reason(), request.from(), request.to(), request.subjects(), request.backfillRunId(),
                targets);
    }

    private static Set<IngestFailureFilter.Target> targets(List<IngestFailureRetryRequest.Target> targets) {
        if (targets == null) {
            return null;
        }
        if (targets.size() > MAX_LIMIT) {
            throw new InvalidRequestParameterException("targets must hold at most " + MAX_LIMIT + " entries");
        }
        Set<IngestFailureFilter.Target> parsed = new LinkedHashSet<>();
        for (IngestFailureRetryRequest.Target target : targets) {
            if (target == null || target.kind() == null || target.subject() == null || target.subject().isBlank()) {
                throw new InvalidRequestParameterException("each of targets must name a kind and a non-blank subject");
            }
            parsed.add(new IngestFailureFilter.Target(coded("targets.kind", target.kind(), IngestFailureKind::fromWire, KINDS), target.subject()));
        }
        return parsed;
    }

    private static IngestFailureFilter filter(String kind, String state, String reason, String from, String to, List<String> subjects,
            String backfillRunId, Set<IngestFailureFilter.Target> targets) {
        LocalDate fromDate = date("from", from);
        LocalDate toDate = date("to", to);
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new InvalidRequestParameterException("from must not be after to");
        }
        if (backfillRunId != null && backfillRunId.isBlank()) {
            throw new InvalidRequestParameterException("backfill_run_id must not be blank");
        }
        return new IngestFailureFilter(
                coded("kind", kind, IngestFailureKind::fromWire, KINDS),
                coded("state", state, IngestFailureState::fromWire, Arrays.stream(IngestFailureState.values()).map(IngestFailureState::wire).toList()),
                coded("reason", reason, IngestFailureReason::fromWire, Arrays.stream(IngestFailureReason.values()).map(IngestFailureReason::wire).toList()),
                fromDate,
                toDate,
                subjects == null ? null : new LinkedHashSet<>(subjects),
                backfillRunId == null ? null : backfillRunId.trim(),
                targets);
    }

    private static <T> T coded(String name, String value, Function<String, T> parse, List<String> accepted) {
        if (value == null) {
            return null;
        }
        T parsed = value.isBlank() ? null : parse.apply(value.trim());
        if (parsed == null) {
            throw new InvalidRequestParameterException(name + " must be one of " + accepted);
        }
        return parsed;
    }

    private static LocalDate date(String name, String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new InvalidRequestParameterException(name + " must be an ISO date (yyyy-MM-dd)");
        }
    }

    private static int limit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value.trim());
            if (limit >= 1 && limit <= MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the refusal below
        }
        throw new InvalidRequestParameterException("limit must be an integer from 1 to " + MAX_LIMIT);
    }
}
