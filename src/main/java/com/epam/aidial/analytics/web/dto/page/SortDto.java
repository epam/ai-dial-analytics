package com.epam.aidial.analytics.web.dto.page;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One sort term. The first entry of a request's list is primary and the rest are tie-breakers, in
 * order.
 *
 * @param column    an allow-listed sortable attribute
 * @param direction {@code ASC} or {@code DESC}; defaults to {@code ASC} when absent
 */
@Schema(description = "One sort term; the first in a list is primary and the rest are tie-breakers.")
public record SortDto(
        @Schema(description = "Attribute to sort by.", example = "epochTimestampMs") String column,
        @Schema(description = "Sort direction.", example = "DESC") SortDirectionDto direction) {

    /** The direction to apply, treating an absent one as ascending. */
    public SortDirectionDto directionOrDefault() {
        return direction == null ? SortDirectionDto.ASC : direction;
    }
}
