package com.epam.aidial.analytics.web.dto.table;

import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Request body for updating a table definition's catalog metadata via {@code PUT /v1/tables/{name}}.
 * Only the {@code description} and the per-table {@code tag_order} are mutable; the name, type, source,
 * and all physical-schema fields are immutable (physical schema changes go through the schema resource).
 * {@code description} is set when non-null (a null leaves it unchanged); {@code tag_order} follows the
 * merge-patch convention: absent/null leaves it unchanged, an empty array clears it, and a non-empty
 * array replaces it.
 *
 * <p>{@code write} is declared here only so that a caller who tries to change the write discipline gets
 * a clear 422 pointing at create rather than a silently-ignored field — the facet selects the ClickHouse
 * engine and is frozen at materialization.
 */
public record UpdateTableRequest(
        @Schema(description = "New description; null leaves it unchanged", example = "Region per user (v2)")
        String description,

        @Schema(description = "Per-table tag ordering (merge-patch): omit/null to leave unchanged, "
                + "empty array to clear, non-empty array to replace")
        @JsonProperty("tag_order") List<String> tagOrder,

        @Schema(description = "Not accepted here (422): the write discipline selects the ClickHouse "
                + "engine, which is frozen at materialization; it is declared at POST /v1/tables")
        TableWriteMode write) {}
