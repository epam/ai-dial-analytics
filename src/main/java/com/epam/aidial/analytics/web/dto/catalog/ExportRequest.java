package com.epam.aidial.analytics.web.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The export selection: the objects to export by kind and name. Each pulls in its dependency closure; an
 * empty or absent list exports every exportable object. {@code include_access} adds the covered tables' access
 * entries, system tables included.
 */
public record ExportRequest(
        @Schema(description = "Objects to export; empty or absent exports everything exportable. With include_access, a "
                + "system table may be named to export its access")
        List<@NotNull @Valid ExportComponent> components,

        @Schema(description = "Also carry every covered table's access entries (privileges, row and column policies), "
                + "system tables included; requires TABLE_GRANT. Default false")
        @JsonProperty("include_access") Boolean includeAccess) {

    /** Whether the export carries access. */
    public boolean accessIncluded() {
        return Boolean.TRUE.equals(includeAccess);
    }
}
