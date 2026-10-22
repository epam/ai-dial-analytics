package com.epam.aidial.analytics.web.dto.page;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One page of results together with the size of the whole match.
 *
 * <p>{@code total} and {@code totalPages} are part of the adopted contract and cost a second
 * {@code COUNT(*)} per request with the same predicate; there is no wire opt-out.
 *
 * @param total      number of rows matching the filters, across all pages
 * @param totalPages number of pages at the requested page size
 * @param data       the requested page
 */
@Schema(description = "One page of results plus the total match size.")
public record PageDto<T>(
        @Schema(description = "Rows matching the filters across all pages.", example = "137") long total,
        @Schema(description = "Number of pages at the requested page size.", example = "7") int totalPages,
        @Schema(description = "The requested page.") List<T> data) {}
