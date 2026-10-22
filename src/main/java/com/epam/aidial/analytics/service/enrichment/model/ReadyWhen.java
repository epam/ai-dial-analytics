package com.epam.aidial.analytics.service.enrichment.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A group pipeline's readiness declaration, stored as the {@code ready_when} JSONB: when is a group complete
 * enough to evaluate? ADAS validates it at registration (the signal parses under the same bounded grammar
 * as a filter, the durations parse, the ceiling is positive, and at least one condition is present) and
 * serves it on the sync feed; the caller evaluates it against the columns the scan projected for it.
 *
 * <p>Splitting it that way is what makes group enrichment affordable: "this group changed" rides the row
 * scan and costs proportional to <b>new rows</b>, while "this group is ready" is a predicate the caller
 * evaluates over its own per-group state — so ADAS never has to know which groups exist.
 *
 * <p>Unknown properties are tolerated so the caller may carry extra fields.
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReadyWhen {

    /**
     * Boolean predicate over the source's declared columns marking a row that terminates its group (an
     * explicit end-of-conversation event, say). Validated under the bounded Calcite grammar exactly as a
     * pipeline filter is, and its referenced columns run the sensitive-column entitlement check.
     */
    private String signal;

    /** How long a group must be quiet before it counts as ready ({@code 10m} or {@code PT10M}). */
    private String idle;

    /** Maximum age of a computed result before the group is re-evaluated regardless of activity. */
    @JsonProperty("max_staleness")
    private String maxStaleness;

    /** Per-group evaluation budget the caller enforces; ADAS only validates that it is positive. */
    @JsonProperty("cost_ceiling")
    private Integer costCeiling;
}
