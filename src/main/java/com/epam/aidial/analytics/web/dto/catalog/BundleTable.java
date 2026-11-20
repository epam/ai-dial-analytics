package com.epam.aidial.analytics.web.dto.catalog;

import com.epam.aidial.analytics.web.dto.table.CreateTableRequest;
import com.epam.aidial.analytics.web.dto.table.DefineSchemaRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * One table, as the two requests that create it. Its access is not part of it: it travels only in the bundle's
 * {@code access} member, when an export asks for it.
 */
public record BundleTable(
        @Schema(description = "The POST /v1/tables body")
        @NotNull @Valid CreateTableRequest table,

        @Schema(description = "The POST /v1/tables/{name}/schema body, columns exactly as stored")
        @NotNull @Valid DefineSchemaRequest schema) {
}
