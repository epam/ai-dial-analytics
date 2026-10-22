package com.epam.aidial.analytics.web.dto.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The export selection: the objects to export by kind and name. Each pulls in its dependency closure; an
 * empty or absent list exports every exportable object.
 */
public record ExportRequest(
        @Schema(description = "Objects to export; empty or absent exports everything exportable")
        List<@NotNull @Valid ExportComponent> components) {
}
