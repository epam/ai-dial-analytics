package com.epam.aidial.analytics.web.dto.backfill;

import com.epam.aidial.analytics.service.BackfillRun;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response to a successful backfill trigger ({@code 202 Accepted}): the generated run id and the
 * resolved range. Poll {@code GET /v1/data-sync/backfill/{runId}} for progress.
 */
@Schema(description = "Backfill trigger acknowledgement.")
public record BackfillTriggerResponse(
        @Schema(description = "Generated run identifier.", example = "f1d2c3b4-...")
        String runId,
        @Schema(description = "Resolved inclusive start date (UTC).", example = "2026-05-26")
        String from,
        @Schema(description = "Resolved inclusive end date (UTC).", example = "2026-06-24")
        String to,
        @Schema(description = "Number of days in the inclusive range.", example = "30")
        int days) {

    public static BackfillTriggerResponse of(BackfillRun.Snapshot snapshot) {
        return new BackfillTriggerResponse(
                snapshot.runId(),
                snapshot.from().toString(),
                snapshot.to().toString(),
                snapshot.daysTotal());
    }
}
