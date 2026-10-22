package com.epam.aidial.analytics.web.dto.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** An object an export-everything left out, and why. */
public record SkippedObject(
        @Schema(description = "Object kind", example = "table")
        @NotNull ExportComponent.Type type,

        @Schema(description = "Object name", example = "draft_table")
        @NotBlank String name,

        @Schema(description = "Why it was left out: `pending`, `failed`, `otlp`, `unresolvable` (a pipeline whose "
                + "declaration no longer resolves), or `dependency_skipped` for an object that needs one of those", example = "pending")
        @NotBlank String reason) {
}
