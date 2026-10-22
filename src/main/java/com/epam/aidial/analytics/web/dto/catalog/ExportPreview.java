package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** What an export with the same body would write, without the bundle itself. */
public record ExportPreview(
        @Schema(description = "Tables and pipelines the bundle would carry, in dependency order")
        List<ExportEntry> objects,

        @Schema(description = "System tables the bundle would require on the target rather than carry")
        @JsonProperty("required_system_tables") List<ExportEntry> requiredSystemTables,

        @Schema(description = "Objects an export-everything leaves out")
        List<SkippedObject> skipped) {
}
