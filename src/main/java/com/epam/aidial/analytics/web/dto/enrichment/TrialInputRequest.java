package com.epam.aidial.analytics.web.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Optional body of {@code POST /v1/pipelines/{name}/trial-input}: the preview's narrowing, plus how many row-grain
 * rows the Enrichment Runner's dry run will try. Each member is refused where the preview refuses it, and an
 * unknown one is a 400 from the strict request mapper.
 */
public record TrialInputRequest(
        @Schema(description = "The group to try, for a `group`-triggered pipeline; without it the newest group is "
                + "chosen, as the preview chooses it", example = "chat-42")
        @JsonProperty("group_key") String groupKey,

        @Schema(description = "Exclusive lower bound on the input's version column (row-grain pipelines only)",
                example = "2026-07-01T00:00:00Z")
        Instant from,

        @Schema(description = "Inclusive upper bound on the input's version column (row-grain pipelines only)",
                example = "2026-08-01T00:00:00Z")
        Instant to,

        @Schema(description = "How many of the newest sampled rows to return, 1 to 5; 1 when omitted. Refused on a "
                + "`group` pipeline, whose trial input is the whole group", example = "1")
        Integer limit) {
}
