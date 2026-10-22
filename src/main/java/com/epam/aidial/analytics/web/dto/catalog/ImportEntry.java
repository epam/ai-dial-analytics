package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** One bundle object's import outcome, planned or applied. */
public record ImportEntry(
        @Schema(description = "Object name", example = "usage_sentiment")
        String name,

        @Schema(description = "What import does with the object")
        @JsonProperty("import_action") ImportAction importAction,

        @Schema(description = "The target's existing object in bundle shape, as the caller may see it; absent when it is new "
                + "or the caller may not see it")
        Object prev,

        @Schema(description = "The bundle's object")
        Object next,

        @Schema(description = "Whether prev and next differ, ignoring enabled, state and timestamps")
        Boolean differs,

        @Schema(description = "Whether only descriptions, display names or tags differ")
        @JsonProperty("metadata_only") Boolean metadataOnly,

        @Schema(description = "Why the object cannot be imported")
        List<String> problems,

        @Schema(description = "Pipelines only: whether PATCH enabled=true would pass its arm-time checks "
                + "after this import")
        Boolean armable,

        @Schema(description = "Pipelines only: what the arm-time checks refused")
        @JsonProperty("arm_problems") List<String> armProblems,

        @Schema(description = "Pipelines only: the target deleted a pipeline of this name that had been enabled")
        @JsonProperty("reused_name") Boolean reusedName,

        @Schema(description = "Import result only: `created`, `skipped`, `rolled_back`, `rollback_failed` or "
                + "`failed`")
        String status) {

    /** This entry with {@code status} set. */
    public ImportEntry withStatus(String newStatus) {
        return new ImportEntry(name, importAction, prev, next, differs, metadataOnly, problems, armable, armProblems,
                reusedName, newStatus);
    }
}
