package com.epam.aidial.analytics.web.dto.catalog;

import io.swagger.v3.oas.annotations.media.Schema;

/** One object an export would write, and why the selection reached it. */
public record ExportEntry(
        @Schema(description = "Object kind", example = "table")
        ExportComponent.Type type,

        @Schema(description = "Object name", example = "usage_base")
        String name,

        @Schema(description = "The object's description, when it has one")
        String description,

        @Schema(description = "`selected`; the dependency that pulled the object in, such as `column policy of <table>` "
                + "for an enrichment an access entry names; or, for a required system table, `access` when only "
                + "exporting everything with access covers it",
                example = "source_table of usage_sentiment")
        String reason) {
}
