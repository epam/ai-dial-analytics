package com.epam.aidial.analytics.web.dto.query;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Flat schema of a queryable entity ({@code GET /v1/queries/entities/schema/{name}}): the union of
 * the source table's exposed columns and every materialized enrichment's exposed columns. Foreign-key
 * (join) columns are hidden and do not appear. {@code tag_order} is the source table's per-table tag
 * ordering ({@code null} when not set); when it is set the {@code fields} list is returned sorted by
 * it (tag groups in list order, unlisted tags alphabetically, untagged last, alphabetical by name
 * within a group).
 */
public record QueryEntitySchemaDto(
        @Schema(description = "Entity wire name", example = "dial_usage_log")
        String entity,

        @Schema(description = "Per-table tag ordering, or null when not set. When set, fields are "
                + "returned grouped/sorted by it.")
        @JsonProperty("tag_order") List<String> tagOrder,

        @Schema(description = "Flat, queryable fields") List<QuerySchemaFieldDto> fields) {

    /** Convenience constructor for a schema with no tag ordering (equivalent to a null tag_order). */
    public QueryEntitySchemaDto(String entity, List<QuerySchemaFieldDto> fields) {
        this(entity, null, fields);
    }
}
