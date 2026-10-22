package com.epam.aidial.analytics.web.dto.query.saved;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Envelope for the saved-query list. An object rather than a bare array, per the project's API
 * conventions, so counts or pagination cursors can be added without breaking clients.
 *
 * <p>Entries are the <strong>full</strong> {@link SavedQueryDto}, identical to what the single read
 * returns. There is deliberately no summary projection: a client cannot act on one. Which editor a
 * saved query opens in is derived from its body ({@code sql} set means SQL, otherwise whether the
 * structured body is builder-representable decides Builder vs JSON), and the period and chart type it
 * displays come from {@code time} and {@code chart} — so a projected list costs a second round trip per
 * row selected, plus a loading state and a cache to invalidate on every save and delete. The projection
 * also withheld nothing: the same caller could read each row individually. It was a bandwidth
 * optimization, not access control, and the wrong trade at this size.
 */
public record SavedQueryListDto(
        @Schema(description = "Saved queries visible to the caller at the requested scope, "
                + "most recently updated first; each is the same full object the single read returns")
        @JsonProperty("saved_queries") List<SavedQueryDto> savedQueries) {}
