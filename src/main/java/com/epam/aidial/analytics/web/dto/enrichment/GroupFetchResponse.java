package com.epam.aidial.analytics.web.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * One group's assembled members. An unknown or empty group returns HTTP 200 with an empty {@code rows}
 * array rather than an error: groups are discovered from a row scan and expire with the source, so an
 * empty group is a normal race, not a caller mistake.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupFetchResponse(
        @Schema(description = "The group's member rows (projected columns only), in the source's total order")
        List<Map<String, Object>> rows,

        @Schema(description = "Whether the row or response-byte cap trimmed the group")
        boolean truncated) {
}
