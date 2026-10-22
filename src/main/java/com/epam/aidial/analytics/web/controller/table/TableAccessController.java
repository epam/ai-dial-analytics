package com.epam.aidial.analytics.web.controller.table;

import com.epam.aidial.analytics.service.table.TableAccessService;
import com.epam.aidial.analytics.service.table.TableAccessService.AccessReplacement;
import com.epam.aidial.analytics.service.table.grant.EffectivePermissions;
import com.epam.aidial.analytics.service.table.grant.PrivilegeScope;
import com.epam.aidial.analytics.web.dto.table.TableDto.PermissionsDto;
import com.epam.aidial.analytics.web.dto.table.access.AccessDto;
import com.epam.aidial.analytics.web.dto.table.access.AccessReplacedDto;
import com.epam.aidial.analytics.web.dto.table.access.AccessReplacedDto.IneffectiveGrantDto;
import com.epam.aidial.analytics.web.dto.table.access.ReplaceAccessRequest;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-table grant management: the five lists of raw provider role names that grant each grantable
 * table-scoped privilege. A caller holding a listed role is then authorized for that table's operations in its own
 * right, with no global privilege.
 *
 * <p><b>Both</b> the read and the write require {@link Privilege#TABLE_GRANT}. The read used to be open to
 * any application role like the rest of the catalog read surface, which disclosed which provider roles
 * exist and what each may do — organisational structure — to every catalog reader.
 *
 * <p>The {@code PUT} is a merge: absent keeps, {@code []} revokes. Its response carries the stored lists,
 * the caller's effective permissions, and any grant that changes nothing.
 */
@RestController
@RequestMapping("/v1/tables/{name}/access")
@RequiredArgsConstructor
@Tag(name = "Table access", description = "Manage per-table grant lists.")
public class TableAccessController {

    private final TableAccessService service;

    @GetMapping
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @Operation(summary = "Get a table's grants",
            description = "Returns all five grant lists for the table, as stored. Requires TABLE_GRANT, "
                    + "because the lists disclose which provider roles exist and what they may do.")
    public AccessDto get(@Parameter(description = "Table name") @PathVariable String name) {
        return AccessDto.of(service.getAccess(name));
    }

    @PutMapping
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @Operation(summary = "Merge a table's grants",
            description = "Merges the grant lists: a key present in the body replaces that list, an absent "
                    + "key keeps the stored one, and an explicit empty array revokes it. Returns the same five "
                    + "lists GET returns, plus the caller's effective permissions and any grant that changes "
                    + "nothing.")
    public AccessReplacedDto replace(
            @Parameter(description = "Table name") @PathVariable String name,
            @Valid @RequestBody ReplaceAccessRequest request) {
        final AccessReplacement replacement = service.replaceAccess(name, TableAccessService.requestedLists(
                request.get(), request.read(),
                request.sensitive(), request.write(), request.modify()));
        return AccessReplacedDto.of(
                AccessDto.of(replacement.access()),
                toPermissionsDto(replacement.permissions()),
                replacement.ineffectiveGrants().stream()
                        .map(grant -> new IneffectiveGrantDto(
                                grant.role(),
                                PrivilegeScope.grantKey(grant.privilege()),
                                grant.reason().code(),
                                grant.message()))
                        .toList());
    }

    private static PermissionsDto toPermissionsDto(EffectivePermissions permissions) {
        return new PermissionsDto(
                permissions.get(), permissions.read(),
                permissions.sensitive(), permissions.write(), permissions.modify(), permissions.rowsRestricted());
    }
}
