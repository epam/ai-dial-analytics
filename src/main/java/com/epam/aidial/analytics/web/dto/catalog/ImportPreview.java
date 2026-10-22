package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** What an import of the bundle would do. Returned whenever the bundle parses, problems included. */
public record ImportPreview(
        @Schema(description = "System tables the bundle requires: SKIP when the target has it, FAIL otherwise")
        @JsonProperty("required_system_tables") List<ImportEntry> requiredSystemTables,

        @Schema(description = "Tables, in import order")
        List<ImportEntry> tables,

        @Schema(description = "Pipelines, in import order")
        List<ImportEntry> pipelines,

        @Schema(description = "Values likely to mean something different on this environment")
        @JsonProperty("env_specific") List<EnvSpecificValue> envSpecific,

        @Schema(description = "Problems with the bundle as a whole: format, version, closure, limits")
        @JsonProperty("validation_errors") List<String> validationErrors) {
}
