package com.epam.aidial.analytics.web.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The outcome of one Class-1 materialize window: counts and cursor bookkeeping only — no row payload, in
 * either direction, by design. Echo {@code next_cursor} as the next request's {@code cursor}, and only
 * after a successful response: a failed call leaves the window unwritten and re-running the same cursor
 * re-executes an identical window.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MaterializeResponse(
        @JsonProperty("rows_written")
        @Schema(description = "Rows ClickHouse reported written for this window")
        long rowsWritten,

        @JsonProperty("next_cursor")
        @Schema(description = "Opaque cursor to echo on the next request; unchanged for an empty window")
        String nextCursor,

        @JsonProperty("has_more")
        @Schema(description = "Whether more of the backlog remains (the window filled to the limit)")
        boolean hasMore,

        @JsonProperty("lag_seconds")
        @Schema(description = "server_now − the window's end version; null for an empty window")
        Long lagSeconds) {
}
