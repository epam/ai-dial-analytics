package com.epam.aidial.analytics.web.dto.datasync;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** How many outstanding subjects a retry request requeued. */
@Schema(description = "Outcome of a requeue.")
public record IngestFailureRetryResponse(
        @JsonProperty("requeued") @Schema(description = "Outstanding subjects requeued; the next sweep attempts them.") int requeued) {
}
