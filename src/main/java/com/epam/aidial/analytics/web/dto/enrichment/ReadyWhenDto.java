package com.epam.aidial.analytics.web.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A group pipeline's readiness declaration: when is a group complete enough to evaluate? At least one of
 * {@code signal}, {@code idle}, or {@code max_staleness} must be present — a declaration with none marks
 * a group dirty forever and never ready, so the pipeline would silently do nothing.
 *
 * <p>ADAS validates this at registration and serves it on the sync feed; the caller evaluates it against
 * the columns the scan projected for it, which is what keeps dirty-marking proportional to new rows
 * rather than to the number of groups.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReadyWhenDto(
        @Schema(description = "Boolean predicate over the source's declared columns marking a row that "
                + "terminates its group; parsed under the same bounded grammar as filter_sql",
                example = "event_kind = 'conversation_end'")
        String signal,

        @Schema(description = "How long a group must be quiet before it counts as ready", example = "10m")
        String idle,

        @JsonProperty("max_staleness")
        @Schema(description = "Maximum age of a computed result before the group is re-evaluated "
                + "regardless of activity", example = "24h")
        String maxStaleness,

        @JsonProperty("cost_ceiling")
        @Schema(description = "Per-group evaluation budget the caller enforces; must be positive",
                example = "5")
        Integer costCeiling) {
}
