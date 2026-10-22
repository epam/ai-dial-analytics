package com.epam.aidial.analytics.web.dto.enrichment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A group pipeline's member-selection policy: which of a group's members bear on its label. Valid only for a
 * {@code group} trigger, and optional for it — omitted, the assembly keeps its default policy of the newest
 * members by the source's total order, bounded only by the configured caps.
 *
 * <p>Declaring it does two things: it selects members from inexpensive columns before any bound body column
 * is read, so the assembly's cost follows the members it returns rather than the group's size; and it
 * replaces "the newest N", which is the wrong ranking whenever the last row of a group is not the
 * interesting one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberSelectDto(
        @Schema(description = "Boolean predicate over the read source's declared columns marking the "
                + "preferred members, parsed under the same bounded grammar as filter_sql. When NO member "
                + "of a group satisfies it, every member becomes a candidate instead — it is a preference, "
                + "not a second membership filter",
                example = "parent_deployment IS NULL")
        @JsonProperty("prefer_sql")
        String preferSql,

        @Schema(description = "How to rank the candidates, most-interesting first. Always followed by the "
                + "source's total order descending as a deterministic tiebreak, so re-assembling an "
                + "unchanged group selects the same members")
        @JsonProperty("order_by")
        List<MemberOrderDto> orderBy,

        @Schema(description = "How many candidates to retain. Lowers the assembly's effective row cap "
                + "rather than sitting beside it, so a caller requesting more members than the pipeline allows "
                + "cannot defeat the policy. At least 1, at most the configured group-fetch maximum",
                example = "2")
        Integer limit) {
}
