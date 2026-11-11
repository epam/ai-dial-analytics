package com.epam.aidial.analytics.web.dto.table.rowpolicy;

import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FilterNodeDeserializer;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicy;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * One role's row policy on one table, as written by {@code PUT /v1/tables/{name}/row-policies/{role}} and
 * read back. A request gives the policy as {@code filter} or as {@code where}; a response always carries the
 * stored {@code filter}.
 */
@Schema(description = "A row policy: a `filter` in the structured query's filter grammar, over this table's own "
        + "columns. A policy narrows what the role reads of the table; it never grants a read. A caller one of whose "
        + "other roles may read the table and has no policy on it is not narrowed.")
public record RowPolicyDto(
        @Schema(description = "The rows the role may read, in the structured query's filter grammar (CQL2-JSON). "
                + "No aggregate or window functions, subqueries or enrichment fields, and no `param` other than the "
                + "caller values `caller.user_hash` and `caller.project`.")
        @JsonDeserialize(using = FilterNodeDeserializer.class) FilterNode filter,

        @Schema(description = "Request only, instead of `filter`: the same policy written as the SQL condition that "
                + "would follow WHERE, for example `user_hash = caller.user_hash`. It is translated to a `filter`, "
                + "which is what is stored and returned.")
        @JsonInclude(JsonInclude.Include.NON_NULL) String where) {

    public static RowPolicyDto of(RowPolicy policy) {
        return new RowPolicyDto(policy.filter(), null);
    }
}
