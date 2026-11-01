package com.epam.aidial.analytics.web.dto.datasync;

import com.epam.aidial.analytics.service.IngestFailureService;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** The outstanding failure-ledger subjects a list request selected. */
@Schema(description = "Outstanding failed blobs and days, newest failure first.")
public record IngestFailureListResponse(
        @JsonProperty("failures") @Schema(description = "The outstanding subjects returned.") List<IngestFailureView> failures,
        @JsonProperty("truncated") @Schema(description = "Whether more subjects matched than `limit` allowed.") boolean truncated) {

    public static IngestFailureListResponse of(IngestFailureService.Page page) {
        return new IngestFailureListResponse(page.entries().stream().map(IngestFailureView::of).toList(), page.truncated());
    }
}
