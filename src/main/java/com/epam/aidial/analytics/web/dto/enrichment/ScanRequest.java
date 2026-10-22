package com.epam.aidial.analytics.web.dto.enrichment;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Request body for {@code POST /v1/pipelines/{name}/scan}. {@code cursor} is the opaque token echoed from a
 * prior page (omit for the first page / full-replay); {@code limit} is the requested page size (a
 * configured default applies when omitted); {@code from}/{@code to} optionally bound the source's
 * version column as the half-open range {@code (from, to]} so a backfill can walk one window instead of
 * replaying from the source start.
 */
public record ScanRequest(
        @Schema(description = "Opaque cursor echoed from a prior page; omit to scan from the start")
        String cursor,

        @Schema(description = "Requested page size; a configured default applies when omitted")
        Integer limit,

        @Schema(description = "Exclusive lower bound on the source's version column; omit for unbounded",
                example = "2026-07-01T00:00:00Z")
        Instant from,

        @Schema(description = "Inclusive upper bound on the source's version column; omit for unbounded. "
                + "Always clamped by the safety-lag horizon.",
                example = "2026-08-01T00:00:00Z")
        Instant to) {
}
