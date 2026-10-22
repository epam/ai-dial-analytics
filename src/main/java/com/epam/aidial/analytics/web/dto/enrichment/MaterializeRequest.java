package com.epam.aidial.analytics.web.dto.enrichment;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Request body for {@code POST /v1/pipelines/{name}/materialize}. {@code cursor} is the opaque token echoed
 * from a prior window (omit to start from the source's beginning) — ADAS holds no per-caller position,
 * exactly as on the scan path, so the caller persists it and advances only on success. {@code limit}
 * bounds the window's row count (a configured default applies when omitted), and {@code from}/{@code to}
 * optionally bound the source's version column as the half-open range {@code (from, to]}, the same window
 * vocabulary the scan uses.
 */
public record MaterializeRequest(
        @Schema(description = "Opaque cursor echoed from a prior window; omit to start from the beginning")
        String cursor,

        @Schema(description = "Maximum source rows in this window; a configured default applies when omitted")
        Integer limit,

        @Schema(description = "Exclusive lower bound on the source's version column; omit for unbounded",
                example = "2026-07-01T00:00:00Z")
        Instant from,

        @Schema(description = "Inclusive upper bound on the source's version column; omit for unbounded. "
                + "Always clamped by the safety-lag horizon.",
                example = "2026-08-01T00:00:00Z")
        Instant to) {
}
