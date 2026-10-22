package com.epam.aidial.analytics.web.dto.page;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One filter term. A request's filters form a flat AND-list — there is no grouping and no OR; a range
 * is expressed as two terms on the same column.
 *
 * @param column   an allow-listed filterable attribute
 * @param operator the comparison to apply
 * @param value    always carried as a string, whatever the column's type, and coerced per column
 */
@Schema(description = "One filter term; a request's terms are combined with AND.")
public record FilterDto(
        @Schema(description = "Attribute to filter on.", example = "resourceType") String column,
        @Schema(description = "Comparison to apply.", example = "eq") FilterOperatorDto operator,
        @Schema(description = "Value to compare against, always as a string.", example = "Table") String value) {}
