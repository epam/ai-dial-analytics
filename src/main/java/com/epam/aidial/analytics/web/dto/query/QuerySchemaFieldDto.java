package com.epam.aidial.analytics.web.dto.query;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One flat, queryable field of an entity schema. {@code source} names the entity field the flat
 * field is backed by: a plain column's source is the field itself, while a flattened JSONB-derived
 * field points at the JSONB field it lives in (e.g. {@code metric:Accuracy:score} →
 * {@code metricValues}).
 */
public record QuerySchemaFieldDto(
        @Schema(description = "Flat field name usable in structured queries", example = "metric:Accuracy:score")
        String name,

        @Schema(description = "Declared field type", example = "decimal")
        QueryFieldType type,

        @Schema(description = "Scalar element type of an array field (null for non-array fields)",
                example = "string")
        @JsonProperty("element_type") QueryFieldType elementType,

        @Schema(description = "Declared value domain of an enum field, in declared order (null for "
                + "non-enum fields). Publishing it is what lets a consumer build a value picker or "
                + "validate a filter from discovery alone, instead of sampling the data",
                example = "[\"pending\", \"running\", \"failed\"]")
        @JsonProperty("enum_values") List<String> enumValues,

        @Schema(description = "Entity field backing this flat field", example = "metricValues")
        String source,

        @Schema(description = "Optional human-readable description of the column (null when unset)",
                example = "Number of prompt (input) tokens consumed")
        String description,

        @Schema(description = "Optional user-friendly display name for the column (null when unset)",
                example = "Total money spend")
        @JsonProperty("display_name") String displayName,

        @Schema(description = "Optional label identifying the column (null when unset)", example = "user_email")
        String tag,

        @Schema(description = "Whether the column is sensitive (values returned only to callers holding "
                + "TABLE_SENSITIVE on the table that owns it)", example = "false")
        boolean sensitive,

        @Schema(description = "Whether the column is heavy: omitted from wildcard projections (a SQL "
                + "SELECT *, or a row-mode query with no select) because it is expensive to transfer. "
                + "Still returned when named explicitly, and visible to every caller — a cost hint, not "
                + "access control", example = "false")
        boolean heavy,

        @Schema(description = "Whether the field is computed by the service after the query runs rather than stored: "
                + "it may only be selected as a plain row-mode projection, never filtered, sorted, grouped or "
                + "passed to a function", example = "false")
        @JsonProperty("projection_only") boolean projectionOnly) {

    /** A field backed by a catalog column — every field but a computed one. */
    public QuerySchemaFieldDto(String name, QueryFieldType type, QueryFieldType elementType, List<String> enumValues,
            String source, String description, String displayName, String tag, boolean sensitive, boolean heavy) {
        this(name, type, elementType, enumValues, source, description, displayName, tag, sensitive, heavy, false);
    }
}
