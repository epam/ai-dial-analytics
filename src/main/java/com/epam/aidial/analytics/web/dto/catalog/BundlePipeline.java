package com.epam.aidial.analytics.web.dto.catalog;

import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.AdvancedRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.GroupKeyRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.MeasureRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.PatchTransformRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.TriggerRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** One pipeline, as its authored declaration plus whether it was armed on the exporting environment. */
public record BundlePipeline(
        @Schema(description = "The `view=source` projection, without generation, state, enabled and timestamps")
        @NotNull @Valid Declaration pipeline,

        @Schema(description = "Whether the pipeline was enabled on the exporting environment. Informational: "
                + "import always creates it disabled")
        @JsonProperty("exported_enabled") boolean exportedEnabled) {

    /**
     * The authored declaration. The transform is the PATCH shape, because that is the one that accepts a
     * folded legacy declaration's {@code output_vars}.
     */
    public record Declaration(
            @Schema(description = "Pipeline name", example = "usage_daily_rollup")
            @NotBlank String name,

            @Schema(description = "Pipeline kind", example = "aggregate")
            @NotNull PipelineKind kind,

            @Schema(description = "The table this pipeline writes", example = "usage_daily")
            @NotBlank String target,

            @Schema(description = "The table this pipeline reads; absent when it follows its target's source")
            List<@NotNull String> inputs,

            @Schema(description = "Membership predicate over the input")
            String filter,

            @Schema(description = "Trigger")
            @Valid TriggerRequest trigger,

            @Schema(description = "Group keys (`aggregate` only)")
            @JsonProperty("group_by") List<@NotNull @Valid GroupKeyRequest> groupBy,

            @Schema(description = "Measures (`aggregate` only)")
            List<@NotNull @Valid MeasureRequest> measures,

            @Schema(description = "The SQL declaration (`aggregate` only), exactly as authored; exclusive with "
                    + "`group_by`, `measures` and `filter`. Added in format version 2")
            String sql,

            @Schema(description = "Transform (`enrich` only), exactly as authored")
            @Valid PatchTransformRequest transform,

            @Schema(description = "Execution knobs (`enrich` only)")
            @Valid AdvancedRequest advanced) {
    }
}
