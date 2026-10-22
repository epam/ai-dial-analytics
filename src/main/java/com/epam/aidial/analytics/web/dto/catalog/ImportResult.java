package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** What an import did. Each entry carries a {@code status}. */
public record ImportResult(
        @Schema(description = "Correlates this import with its catalog_import log event")
        @JsonProperty("import_id") String importId,

        @Schema(description = "`completed`; `rolled_back` when an object failed and what this import had created was "
                + "deleted again; `rollback_failed` when some of it could not be, and each entry's status says which",
                example = "completed")
        String outcome,

        @Schema(description = "System tables the bundle requires: SKIP when the target has it, FAIL otherwise")
        @JsonProperty("required_system_tables") List<ImportEntry> requiredSystemTables,

        @Schema(description = "Tables, in import order")
        List<ImportEntry> tables,

        @Schema(description = "Pipelines, in import order")
        List<ImportEntry> pipelines,

        @Schema(description = "Values likely to mean something different on this environment")
        @JsonProperty("env_specific") List<EnvSpecificValue> envSpecific) {
}
