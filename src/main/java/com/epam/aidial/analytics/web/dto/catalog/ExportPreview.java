package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** What an export with the same body would write, without the bundle itself. */
public record ExportPreview(
        @Schema(description = "Tables and pipelines the bundle would carry, in dependency order")
        List<ExportEntry> objects,

        @Schema(description = "System tables the bundle would require on the target by name, never carrying their "
                + "definitions: those its objects depend on and, with include_access, those whose access it carries")
        @JsonProperty("required_system_tables") List<ExportEntry> requiredSystemTables,

        @Schema(description = "Objects an export-everything leaves out")
        List<SkippedObject> skipped,

        @Schema(description = "With include_access only: each table that carries access or leaves some behind, sorted by "
                + "table. The only report of what stays behind")
        List<AccessItem> access) {

    /**
     * One table's access in the export.
     *
     * @param roles      the roles the bundle carries for the table, sorted
     * @param leftBehind each part of a stored entry the bundle does not carry, by role and then in reduction order
     */
    public record AccessItem(
            String table,
            List<String> roles,
            @JsonProperty("left_behind") List<LeftBehindItem> leftBehind) {}

    /**
     * A part of a stored entry left behind.
     *
     * @param entry the part, in entry shape
     * @param error the code a PUT of that part would answer, or dependency_skipped
     */
    public record LeftBehindItem(String role, BundleAccessEntry entry, String error) {}
}
