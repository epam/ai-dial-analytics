package com.epam.aidial.analytics.web.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * One page of a pipeline-scoped scan. Echo {@code next_cursor} as the next request's {@code cursor}.
 * {@code has_more} is true when the page filled to the limit; {@code lag_seconds} is the observed
 * ingest lag among returned rows (null on an empty page).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScanResponse(
        @Schema(description = "Scanned source rows (projected columns only)")
        List<Map<String, Object>> rows,

        @JsonProperty("next_cursor")
        @Schema(description = "Opaque cursor to echo on the next request")
        String nextCursor,

        @JsonProperty("has_more")
        @Schema(description = "Whether more rows may remain (page filled to the limit)")
        boolean hasMore,

        @JsonProperty("lag_seconds")
        @Schema(description = "server_now − max(version) among returned rows; null on an empty page")
        Long lagSeconds) {
}
