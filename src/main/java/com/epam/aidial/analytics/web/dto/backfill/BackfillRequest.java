package com.epam.aidial.analytics.web.dto.backfill;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for triggering a backfill: an inclusive UTC date range. Both dates are ISO
 * {@code yyyy-MM-dd}. Validation (missing, unparseable, inverted, future, over-cap) is performed by the
 * service and surfaced as {@code 400 bad_request}. {@code includeIngested} is optional: absent or
 * {@code null} means {@code false}.
 */
@Schema(description = "Backfill trigger request: an inclusive UTC date range. Blobs already ingested are skipped "
        + "unless `includeIngested` is `true`.")
public record BackfillRequest(
        @Schema(description = "Inclusive start date (UTC), ISO yyyy-MM-dd.", example = "2026-05-26")
        String from,
        @Schema(description = "Inclusive end date (UTC), ISO yyyy-MM-dd.", example = "2026-06-24")
        String to,
        @Schema(description = "Re-ingest blobs that were already ingested. Default `false`: only blobs not yet ingested "
                + "are written, so re-triggering a range a stopped run left part-done writes only the rest. Set `true` "
                + "to repair rows lost from a range or to apply a changed mapping to it; the re-ingested rows "
                + "re-fire on-ingest enrichments and aggregate refreshes.",
                defaultValue = "false", example = "false")
        Boolean includeIngested) {
}
