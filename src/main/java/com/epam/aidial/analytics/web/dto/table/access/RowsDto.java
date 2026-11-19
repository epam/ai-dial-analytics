package com.epam.aidial.analytics.web.dto.table.access;

import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FilterNodeDeserializer;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicy;
import com.epam.aidial.analytics.web.dto.StrictStringDeserializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * An entry's row policy. A request gives it as {@code filter} or as {@code where}; a response always carries the
 * stored {@code filter}.
 */
@Schema(description = "A row policy: the rows the role may read, as a `filter` in the structured query's filter grammar "
        + "over this table's own columns, or (request only) as `where`, the SQL condition that would follow WHERE. Stored "
        + "and returned as `filter`. A policy narrows what the role reads; it never grants a read.")
public record RowsDto(
        @Schema(description = "The rows the role may read, in the structured query's filter grammar (CQL2-JSON). No "
                + "aggregate or window functions, subqueries or enrichment fields, and no `param` other than the caller "
                + "values `caller.user_hash` and `caller.project`.")
        @JsonDeserialize(using = FilterNodeDeserializer.class) FilterNode filter,

        @Schema(description = "Request only, instead of `filter`: the same policy as the SQL condition that would follow "
                + "WHERE, for example `user_hash = caller.user_hash()` (the spelling without parentheses is accepted too). "
                + "Translated to a `filter`, which is what is stored.")
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonDeserialize(using = StrictStringDeserializer.class) String where) {

    /** The stored policy as a response member, or {@code null} for none. */
    public static RowsDto of(RowPolicy policy) {
        return policy == null ? null : new RowsDto(policy.filter(), null);
    }
}
