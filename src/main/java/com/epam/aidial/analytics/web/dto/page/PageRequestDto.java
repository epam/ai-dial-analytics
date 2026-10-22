package com.epam.aidial.analytics.web.dto.page;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * A paged, sorted, filtered read request — the shape the sibling DIAL control planes use, adopted so
 * their admin frontend needs no second client.
 *
 * @param pageNumber zero-based page index; absent means the first page
 * @param pageSize   rows per page; absent means 20, and the bound is enforced rather than silently clamped
 * @param sorts      ordered sort terms; when absent or empty the reading service applies its own default,
 *                   because offset paging over an unordered result set silently duplicates and skips rows
 * @param filters    flat AND-list of filter terms
 */
@Schema(description = "Paged, sorted and filtered read request.")
public record PageRequestDto(
        @Schema(description = "Zero-based page index.", defaultValue = "0")
        @PositiveOrZero(message = "pageNumber must not be negative")
        Integer pageNumber,

        @Schema(description = "Rows per page.", defaultValue = "20")
        @Min(value = 1, message = "pageSize must be at least 1")
        @Max(value = MAX_PAGE_SIZE, message = "pageSize must not exceed " + MAX_PAGE_SIZE)
        Integer pageSize,

        // @NotNull on the element, not just @Valid on the list: @Valid cascades into elements but says
        // nothing about a null one, so a JSON `[null]` would arrive as a null entry and NPE inside the
        // query builder — a 500 for a malformed request this layer exists to answer with a 400.
        @Schema(description = "Sort terms, most significant first.")
        @Valid List<@NotNull(message = "a sort term must not be null") SortDto> sorts,

        @Schema(description = "Filter terms, combined with AND.")
        @Valid List<@NotNull(message = "a filter term must not be null") FilterDto> filters) {

    /** Upper bound on {@link #pageSize}, so one request cannot ask for the whole table. */
    public static final int MAX_PAGE_SIZE = 1000;

    private static final int DEFAULT_PAGE_NUMBER = 0;
    private static final int DEFAULT_PAGE_SIZE = 20;

    public int pageNumberOrDefault() {
        return pageNumber == null ? DEFAULT_PAGE_NUMBER : pageNumber;
    }

    public int pageSizeOrDefault() {
        return pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }

    public List<SortDto> sortsOrEmpty() {
        return sorts == null ? List.of() : sorts;
    }

    public List<FilterDto> filtersOrEmpty() {
        return filters == null ? List.of() : filters;
    }
}
