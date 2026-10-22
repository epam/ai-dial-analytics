package com.epam.aidial.analytics.web.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /v1/pipelines/{name}/group-rows}. The caller supplies only the group key — the
 * source, membership filter, projection, and ordering all come from the stored pipeline, so the caller never
 * names a table, a filter, or a column set.
 */
public record GroupFetchRequest(
        @Schema(description = "The group key to assemble, as its string form", example = "chat-42")
        @JsonProperty("group_key") @NotBlank String groupKey,

        @Schema(description = "Maximum members to return; a configured default applies when omitted")
        Integer limit) {
}
