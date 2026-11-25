package com.epam.aidial.analytics.web.dto.enrichment;

import com.epam.aidial.analytics.web.dto.pipeline.PipelineDto;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineResponses.PreviewDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /v1/pipelines/{name}/trial-input}: one snapshot of everything the Enrichment Runner's dry run
 * needs — the preview it shows, the declaration it evaluates, and the rows it evaluates them on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrialInputResponse(
        @Schema(description = "The response `POST /v1/pipelines/{name}/preview` gives for the same body, values capped")
        PreviewDto preview,

        @Schema(description = "The compiled projection of the declaration the sample was read through; for a disabled "
                + "pipeline, as the gate derived it rather than as stored")
        PipelineDto pipeline,

        @Schema(description = "The rows the runner would receive, with no value cap: the newest `limit` sampled rows "
                + "keyed as a scan row, or the whole group as `/group-rows` returns it")
        @JsonProperty("input_rows") List<Map<String, Object>> inputRows,

        @Schema(description = "Whether the group fetch's row or byte cap cut the group (`group` pipelines only)")
        Boolean truncated) {
}
