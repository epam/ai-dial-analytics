package com.epam.aidial.analytics.web.dto.datasync;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Selects the outstanding subjects to requeue; every member is optional, present members are ANDed, at least one is required. */
@Schema(description = "Filters selecting the outstanding failures to requeue. At least one member is required.")
public record IngestFailureRetryRequest(
        @JsonProperty("kind") @Schema(description = "`blob` or `day`.") String kind,
        @JsonProperty("state") @Schema(description = "`pending` or `parked`.") String state,
        @JsonProperty("reason") @Schema(description = "Reason code of the latest failure.", example = "mapping_error") String reason,
        @JsonProperty("from") @Schema(description = "Inclusive lower bound on the subject's day (ISO date).") String from,
        @JsonProperty("to") @Schema(description = "Inclusive upper bound on the subject's day (ISO date).") String to,
        @JsonProperty("subjects") @Schema(description = "Blob keys or ISO dates; at most 1000.") List<String> subjects) {
}
