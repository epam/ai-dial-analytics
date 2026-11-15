package com.epam.aidial.analytics.web.dto.table.access;

import com.epam.aidial.analytics.web.dto.StrictStringListDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * A {@code PUT /v1/tables/{name}/access/{role}} body: the role's whole new entry and the generation the client read.
 * An absent or {@code null} {@code privileges} means none; an absent {@code rows} or {@code columns} means {@code null}.
 * {@code generation} binds any JSON value and is judged by the service ({@code 422} unless an integer, {@code 409
 * access_conflict} when stale); every item of {@code privileges} and {@code columns} must be a JSON string.
 */
public record PutAccessEntryRequest(
        @Schema(description = "The access generation last read from GET .../access or .../access/{role}; required, an "
                + "integer. A stale one is refused 409 access_conflict.", type = "integer", format = "int64")
        JsonNode generation,
        @Schema(description = "The privileges to hold: a subset of get, read, sensitive, write and modify. Absent or null "
                + "means none. `*` takes only get, read and sensitive; a system table takes no write or modify; an "
                + "enrichment takes only write and modify.")
        @JsonDeserialize(using = StrictStringListDeserializer.class) List<String> privileges,
        @Schema(description = "The role's row policy as `filter` or `where`; absent or null for none") RowsDto rows,
        @Schema(description = "The role's column allowlist, non-empty: exposed column names of this table and "
                + "`<enrichment>.<column>` for its enrichments' columns. Absent or null for every column.")
        @JsonDeserialize(using = StrictStringListDeserializer.class) List<String> columns) {}
