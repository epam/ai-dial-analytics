package com.epam.aidial.analytics.web.dto.datasync;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Selects the outstanding subjects to requeue; every member is optional, present members are ANDed, at least one is required. */
@Schema(description = "Filters selecting the outstanding failures to requeue. At least one member is required.")
public record IngestFailureRetryRequest(
        @JsonProperty("kind") @Schema(description = "`blob`, `day` or `records`.") String kind,
        @JsonProperty("state") @Schema(description = "`pending` or `parked`.") String state,
        @JsonProperty("reason") @Schema(description = "Reason code of the latest failure.", example = "mapping_error") String reason,
        @JsonProperty("from") @Schema(description = "Inclusive lower bound on the subject's day (ISO date).") String from,
        @JsonProperty("to") @Schema(description = "Inclusive upper bound on the subject's day (ISO date).") String to,
        @JsonProperty("subjects") @Schema(description = "Blob keys or ISO dates, of any kind; at most 1000.") List<String> subjects,
        @JsonProperty("backfill_run_id") @Schema(description = "A backfill run that failed the subject since it was last resolved.")
        String backfillRunId,
        @JsonProperty("targets") @Schema(description = "Exact subjects, each with its kind; at most 1000.") List<Target> targets) {

    /** One subject named with its kind, since a blob's `blob` and `records` subjects share its key. */
    @Schema(description = "One subject named with its kind.")
    public record Target(
            @JsonProperty("kind") @Schema(description = "`blob`, `day` or `records`.") String kind,
            @JsonProperty("subject") @Schema(description = "The blob key or ISO date.") String subject) {
    }
}
