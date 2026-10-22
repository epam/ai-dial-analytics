package com.epam.aidial.analytics.web.controller.table;

import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicy;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyService;
import com.epam.aidial.analytics.web.dto.table.rowpolicy.RowPoliciesDto;
import com.epam.aidial.analytics.web.dto.table.rowpolicy.RowPolicyDto;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-role row policies on a table: which of the table's rows each raw provider role may read. A policy
 * narrows a read the role already has and never grants one (see {@code table-access-control}).
 *
 * <p>Every method requires {@link Privilege#TABLE_GRANT}, as the grant lists do: a policy discloses which
 * provider roles exist and what each may see. Per-role resources rather than one document, so creating,
 * replacing and removing one role's policy need no merge semantics.
 */
@RestController
@RequestMapping("/v1/tables/{name}/row-policies")
@RequiredArgsConstructor
@Tag(name = "Table row policies", description = "Manage per-role row policies on a table.")
public class TableRowPolicyController {

    private final RowPolicyService service;

    @GetMapping
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @Operation(summary = "List a table's row policies",
            description = "Returns every role's row policy on the table, keyed by raw provider role. Requires "
                    + "TABLE_GRANT, because the policies disclose which roles exist and what each may see.")
    public RowPoliciesDto list(@Parameter(description = "Table name") @PathVariable String name) {
        final Map<String, RowPolicyDto> policies = new LinkedHashMap<>();
        service.list(name).forEach((role, policy) -> policies.put(role, RowPolicyDto.of(policy)));
        return new RowPoliciesDto(policies);
    }

    @PutMapping("/{role}")
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @Operation(summary = "Create or replace a role's row policy",
            description = "Stores the role's policy on the table, replacing any it had. The `filter` confines the "
                    + "role's reads of the table to the rows it matches, whatever path the role reads the table "
                    + "by — including a global read privilege. A caller is not confined when another of its roles "
                    + "may read the table and has no policy on it. Validated against the table's own columns "
                    + "(422 otherwise).")
    public RowPolicyDto put(
            @Parameter(description = "Table name") @PathVariable String name,
            @Parameter(description = "Raw provider role name") @PathVariable String role,
            @RequestBody RowPolicyDto request) {
        return RowPolicyDto.of(service.put(name, role, new RowPolicy(request.filter())));
    }

    @DeleteMapping("/{role}")
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a role's row policy",
            description = "Removes the role's policy on the table; 404 when it has none. A caller carrying the "
                    + "role is then no longer confined by it, and — if the role may read the table — no longer "
                    + "confined there at all.")
    public void delete(
            @Parameter(description = "Table name") @PathVariable String name,
            @Parameter(description = "Raw provider role name") @PathVariable String role) {
        service.delete(name, role);
    }
}
