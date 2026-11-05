package com.epam.aidial.analytics.web.dto.datasync;

import com.epam.aidial.analytics.service.IngestFailureService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** Whether the retry sweep attempts pending subjects, and when it next runs. */
@Schema(description = "Whether the retry sweep attempts pending subjects. When it does not, a requeued subject stays pending.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RetrySweepView(
        @JsonProperty("enabled") @Schema(description = "Whether the sweep attempts due pending subjects.") boolean enabled,
        @JsonProperty("next_sweep_at") @Schema(description = "When the next sweep starts (UTC, ISO-8601); absent when not enabled.")
        String nextSweepAt,
        @JsonProperty("max_per_sweep") @Schema(description = "The most subjects one sweep attempts.") int maxPerSweep) {

    public static RetrySweepView of(IngestFailureService.RetrySweepStatus status) {
        return new RetrySweepView(status.enabled(), status.nextSweepAt() == null ? null : status.nextSweepAt().toString(), status.maxPerSweep());
    }
}
