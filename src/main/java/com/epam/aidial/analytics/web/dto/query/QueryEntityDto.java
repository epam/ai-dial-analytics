package com.epam.aidial.analytics.web.dto.query;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A queryable entity as listed by {@code GET /v1/queries/entities}. {@code name} is the wire
 * identifier used as the {@code entity} value of a structured query. Queryable entities are the
 * materialized {@code source} tables in the catalog; a source's enrichments are flattened into its
 * schema rather than listed separately. {@code supports_final} says whether anything the entity reads
 * collapses versions — its source is {@code upsert_by_key}, or it has an enrichment the caller may read — and
 * so whether a query may read it {@code FINAL}: {@code "final": true} or a SQL {@code FINAL} on an entity
 * reporting {@code false} is rejected with {@code 400 illegal_final}.
 */
public record QueryEntityDto(
        @Schema(description = "Entity wire name", example = "dial_usage_log")
        String name,

        @Schema(description = "Whether the entity may be read FINAL: its source is upsert_by_key or it has an"
                + " enrichment the caller may read. An absent structured-query `final` reads the entity final"
                + " exactly when this is true.",
                example = "true")
        @JsonProperty("supports_final")
        boolean supportsFinal) {}
