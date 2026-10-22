package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * A catalog bundle: user tables and pipelines in request shape, plus the names of the system tables they
 * depend on. {@code format_version} is raised whenever the shape of any member changes, a new enum value
 * included, so an older ADAS refuses a newer bundle by version rather than by an unknown field.
 */
public record CatalogBundle(
        @Schema(description = "Always `adas-catalog`", example = FORMAT)
        @NotBlank String format,

        @Schema(description = "Bundle shape version", example = "1")
        @JsonProperty("format_version") @NotNull Integer formatVersion,

        @Schema(description = "When the bundle was exported")
        @JsonProperty("exported_at") Instant exportedAt,

        @Schema(description = "Objects an export-everything left out")
        List<@NotNull @Valid SkippedObject> skipped,

        @Schema(description = "System tables the bundle's objects depend on, by name: import requires each to "
                + "exist on the target and never creates or changes one", example = "[\"dial_usage_log\"]")
        @JsonProperty("required_system_tables") List<@NotBlank String> requiredSystemTables,

        @Schema(description = "User tables, in dependency order")
        List<@NotNull @Valid BundleTable> tables,

        @Schema(description = "Pipelines")
        List<@NotNull @Valid BundlePipeline> pipelines) {

    /** The {@code format} every bundle carries. */
    public static final String FORMAT = "adas-catalog";

    /**
     * The highest {@code format_version} this build reads and the one it writes. Every export writes it, whatever
     * the bundle holds, so a bundle never claims a version by its content; every lower version still reads.
     *
     * <ul>
     *   <li>{@code 1} — the first shape.</li>
     *   <li>{@code 2} — adds an {@code aggregate} declaration's {@code sql}. A version-1 ADAS refuses a version-2
     *       bundle with the readable newer-format 422 rather than the strict binder's unknown-field 400.</li>
     * </ul>
     */
    public static final int FORMAT_VERSION = 2;
}
