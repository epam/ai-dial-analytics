package com.epam.aidial.analytics.web.dto.enrichment;

import com.epam.aidial.analytics.service.query.model.SortDir;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One ordering key of a {@link MemberSelectDto}: a declared column of the pipeline's read source and a
 * direction. A column and a direction rather than an expression — ranking a group's members needs an
 * order, not a computation, and {@code prefer_sql} already covers what needs a predicate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberOrderDto(
        @Schema(description = "A column declared on the pipeline's read source",
                example = "number_request_messages")
        String column,

        @Schema(description = "Ordering direction; defaults to desc, since every ranking this expresses "
                + "puts the interesting member highest", example = "desc")
        SortDir direction) {
}
