package com.epam.aidial.analytics.web.dto.backfill;

import com.epam.aidial.analytics.service.BackfillRun;
import com.epam.aidial.analytics.service.DataBackfillService;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The retained backfill runs, each in exactly the representation {@code GET /{runId}} returns. An envelope
 * rather than a bare array, and {@code runs} is always present: no matching run is an empty list.
 */
@Schema(description = "Retained backfill runs.")
public record BackfillRunListResponse(
        @ArraySchema(schema = @Schema(implementation = BackfillStatusResponse.class),
                arraySchema = @Schema(description = "Runs newest started first, bounded by retention (at most the "
                        + DataBackfillService.MAX_RETAINED_RUNS + " most recently started). A runtime cache, not a history: cleared on restart and redeploy, and per "
                        + "instance, so during a rolling-update overlap it can omit a run the other instance holds."))
        List<BackfillStatusResponse> runs) {

    public static BackfillRunListResponse of(List<BackfillRun.Snapshot> snapshots) {
        return new BackfillRunListResponse(snapshots.stream().map(BackfillStatusResponse::of).toList());
    }
}
