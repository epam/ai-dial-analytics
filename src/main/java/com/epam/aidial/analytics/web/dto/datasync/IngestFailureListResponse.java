package com.epam.aidial.analytics.web.dto.datasync;

import com.epam.aidial.analytics.service.IngestFailureService;
import com.epam.aidial.analytics.service.IngestFailureState;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** The outstanding failure-ledger subjects a list request selected. */
@Schema(description = "Outstanding failed blobs and days, newest failure first.")
public record IngestFailureListResponse(
        @JsonProperty("failures") @Schema(description = "The outstanding subjects returned.") List<IngestFailureView> failures,
        @JsonProperty("truncated") @Schema(description = "Whether more subjects matched than `limit` allowed.") boolean truncated,
        @JsonProperty("total") @Schema(description = "How many subjects matched the filters, however many `limit` returned.") int total,
        @JsonProperty("by_state") @Schema(description = "How many subjects matched every filter but `state`, per state.") ByState byState,
        @JsonProperty("retry_sweep") @Schema(description = "Whether the retry sweep attempts pending subjects.") RetrySweepView retrySweep) {

    public static IngestFailureListResponse of(IngestFailureService.Page page, IngestFailureService.RetrySweepStatus sweep) {
        return new IngestFailureListResponse(page.entries().stream().map(IngestFailureView::of).toList(), page.truncated(), page.total(),
                new ByState(page.byState().getOrDefault(IngestFailureState.PENDING, 0), page.byState().getOrDefault(IngestFailureState.PARKED, 0)),
                RetrySweepView.of(sweep));
    }

    /** Matching subjects per state. */
    @Schema(description = "Matching subjects per state.")
    public record ByState(
            @JsonProperty("pending") @Schema(description = "Subjects retried automatically.") int pending,
            @JsonProperty("parked") @Schema(description = "Subjects waiting for a requeue.") int parked) {
    }
}
