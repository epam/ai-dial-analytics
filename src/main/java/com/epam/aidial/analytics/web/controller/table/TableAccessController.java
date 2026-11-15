package com.epam.aidial.analytics.web.controller.table;

import com.epam.aidial.analytics.service.table.TableAccessService;
import com.epam.aidial.analytics.service.table.TableAccessService.EntryRequest;
import com.epam.aidial.analytics.service.table.TableAccessService.RowsRequest;
import com.epam.aidial.analytics.web.dto.table.access.AccessEntriesDto;
import com.epam.aidial.analytics.web.dto.table.access.AccessEntryDto;
import com.epam.aidial.analytics.web.dto.table.access.AccessEntryReplacedDto;
import com.epam.aidial.analytics.web.dto.table.access.PutAccessEntryRequest;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

/**
 * A table's access configuration as one entry per raw provider role, {@code {privileges, rows, columns}}, under one
 * table-wide access generation. Every method requires {@link Privilege#TABLE_GRANT}: an entry discloses which provider
 * roles exist and what each may do. See {@code openspec/specs/table-access-control/spec.md}, "Role-keyed access API".
 */
@RestController
@RequestMapping("/v1/tables/{name}/access")
@RequiredArgsConstructor
@Tag(name = "Table access", description = "Read and replace a table's per-role access entries.")
public class TableAccessController {

    private static final String ROLE = "Raw provider role, as one percent-encoded path segment: `/` as `%2F`, `%` as `%25` "
            + "(role `/analytics/viewers` is `%2Fanalytics%2Fviewers`). Decoded once and trimmed. A role containing `;`, "
            + "`\\`, `//`, a NUL, CR or LF, U+2028 or U+2029, or exactly `.` or `..`, cannot be addressed (400).";

    private final TableAccessService service;

    @GetMapping
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @Operation(summary = "List a table's access entries",
            description = "Returns every role's entry on the table, sorted by role, and the table's access `generation`. "
                    + "Requires TABLE_GRANT (403 forbidden), then 404 for an unknown table.")
    public AccessEntriesDto list(@Parameter(description = "Table name") @PathVariable String name) {
        return AccessEntriesDto.of(service.list(name));
    }

    @GetMapping("/{role}")
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @Operation(summary = "Read one role's access entry",
            description = "Returns the role's entry and the table's access `generation`. 403 without TABLE_GRANT, then 404 "
                    + "for an unknown table, then 404 when the role has no entry.")
    public AccessEntryDto get(
            @Parameter(description = "Table name") @PathVariable String name,
            @Parameter(description = ROLE) @PathVariable String role) {
        return AccessEntryDto.of(service.get(name, role));
    }

    @PutMapping("/{role}")
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @Operation(summary = "Replace one role's access entry",
            description = "Replaces the role's whole entry: absent or null `privileges` means none, absent `rows` or "
                    + "`columns` means null, and no other role's entry changes. An entry left with no privileges, no `rows` "
                    + "and no `columns` is removed. `generation` must be the one last read: a stale one is refused 409 "
                    + "access_conflict (read the access again and reapply); a missing or non-integer one 422. Every "
                    + "successful PUT increments the generation, an unchanged one included. `rows` is `{\"filter\": ...}` "
                    + "or `{\"where\": \"<SQL condition>\"}`, stored and returned as `filter`. `columns` is a non-empty "
                    + "allowlist of the table's exposed column names and `<enrichment>.<column>` for its enrichments' "
                    + "columns. Checks run in this order: 400 for a body that does not bind, 403, 404 unknown table, 409 "
                    + "enrichment_follows_source (an enrichment's entry takes only write and modify, judged on the body as "
                    + "sent), 422 unusable generation, 409 access_conflict, 422 role (blank, bundle or privilege name), 409 "
                    + "table_is_system (write or modify on a system table), then the remaining 422 checks. "
                    + "`ineffective_grants` reports privileges that change nothing: already_global, sensitive_without_verb, "
                    + "lifts_row_policies and lifts_column_policies.")
    @ApiResponse(responseCode = "409", description = "access_conflict (stale generation), enrichment_follows_source or "
            + "table_is_system")
    public AccessEntryReplacedDto put(
            @Parameter(description = "Table name") @PathVariable String name,
            @Parameter(description = ROLE) @PathVariable String role,
            @RequestBody PutAccessEntryRequest request) {
        final RowsRequest rows = request.rows() == null ? null : new RowsRequest(request.rows().filter(), request.rows().where());
        return AccessEntryReplacedDto.of(service.put(name, role,
                new EntryRequest(request.generation(), request.privileges(), rows, request.columns())));
    }

    @DeleteMapping("/{role}")
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove one role's access entry",
            description = "Removes the role's entry (privileges, rows and columns) and increments the table's access "
                    + "generation. Carries no generation and never answers 409. 403 without TABLE_GRANT, then 404 for an "
                    + "unknown table, then 404 when the role has no entry.")
    public void delete(
            @Parameter(description = "Table name") @PathVariable String name,
            @Parameter(description = ROLE) @PathVariable String role) {
        service.delete(name, role);
    }
}
