package com.epam.aidial.analytics.web.dto.table.access;

import com.epam.aidial.analytics.web.dto.table.TableDto.PermissionsDto;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The answer to a grant replacement: the stored lists, the <b>effective</b> permission flags they produce
 * for the calling identity, and any grant in the request that changes nothing.
 *
 * <p>The five lists sit at the top level, exactly where {@link AccessDto} puts them, so {@code GET} and
 * {@code PUT} on this resource keep one shape and a client reading {@code response.write} keeps working.
 * Nesting them under an {@code access} member would have been an undeclared break on the response side of
 * the very clients the merge semantics exist to protect. The two new members are additive, which is what
 * the "responses are objects, never bare arrays" convention exists to allow.
 *
 * <p>The effective flags are carried because the stored lists alone do not say what a grant achieved: two
 * different rules live in one document — two monotone read rungs, a clearance and two independent keys — so reading
 * back {@code {"read": ["analysts"]}} does not show that {@code analysts} can now also see the table and
 * read its schema.
 *
 * <p>{@code ineffective_grants} reports rather than refuses. A grant can legitimately change nothing in
 * two ways, neither of which is an error: the role already holds the privilege globally, so effective
 * access was already a union that included it; or the grant is a {@code read} on an enrichment whose
 * source the role cannot read, and an enrichment is not a queryable entity, so no statement can reach its
 * rows. Refusing either would block a deployment mid-migration; saying nothing would leave the operator to
 * discover it.
 */
public record AccessReplacedDto(
        @Schema(description = "Roles that may see this table and read its definition and columns") List<String> get,
        @Schema(description = "Roles that may read this table's non-sensitive values") List<String> read,
        @Schema(description = "Roles cleared for this table's sensitive columns, for the get/read/modify they also hold")
        List<String> sensitive,
        @Schema(description = "Roles that may insert rows into this table") List<String> write,
        @Schema(description = "Roles that may change this table's schema or description") List<String> modify,

        @Schema(description = "The calling identity's effective permissions after the merge")
        PermissionsDto permissions,

        @Schema(description = "Grants in this request that change nothing, with the reason")
        @JsonProperty("ineffective_grants") List<IneffectiveGrantDto> ineffectiveGrants) {

    /** The five stored lists plus the two additive members. */
    public static AccessReplacedDto of(
            AccessDto access, PermissionsDto permissions, List<IneffectiveGrantDto> ineffectiveGrants) {
        return new AccessReplacedDto(
                access.get(), access.read(), access.sensitive(),
                access.write(), access.modify(), permissions, ineffectiveGrants);
    }

    /**
     * One grant that changes nothing.
     *
     * @param role      the granted provider role name
     * @param key       the grant key it was granted under ({@code get}, {@code read}, …)
     * @param reason    a stable machine code: {@code already_global}, {@code enrichment_read_inert} or
     *                  {@code sensitive_without_verb}
     * @param message   the human-readable explanation
     */
    public record IneffectiveGrantDto(
            String role,
            String key,
            String reason,
            String message) {}
}
