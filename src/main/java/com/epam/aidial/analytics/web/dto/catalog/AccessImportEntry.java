package com.epam.aidial.analytics.web.dto.catalog;

import com.epam.aidial.analytics.web.dto.table.access.AccessEntryReplacedDto.IneffectiveGrantDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** One bundle access entry's import outcome, planned or applied. */
public record AccessImportEntry(
        @Schema(description = "The table the entry is on", example = "dial_usage_log")
        String table,

        @Schema(description = "The role, trimmed", example = "analytics-viewers")
        String role,

        @Schema(description = "CREATE when the target has no entry for the role, REPLACE when it has a different one, SKIP "
                + "when it has an equal one, FAIL when the entry is refused")
        @JsonProperty("import_action") ImportAction importAction,

        @Schema(description = "The target's entry for the role, or null when it has none")
        @JsonInclude(JsonInclude.Include.ALWAYS) BundleAccessEntry prev,

        @Schema(description = "The bundle's entry as a PUT of it would store it; as bound from the bundle when FAIL")
        BundleAccessEntry next,

        @Schema(description = "Why the entry cannot be imported")
        List<String> problems,

        @Schema(description = "What a PUT of the entry would report, judged with every bundle entry applied; empty when "
                + "none is applied")
        @JsonProperty("ineffective_grants") List<IneffectiveGrantDto> ineffectiveGrants,

        @Schema(description = "Import result only: `created`, `replaced`, `skipped` or `rolled_back`")
        String status) {
}
