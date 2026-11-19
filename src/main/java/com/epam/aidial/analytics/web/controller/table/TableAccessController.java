package com.epam.aidial.analytics.web.controller.table;

import com.epam.aidial.analytics.exception.InvalidRequestParameterException;
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
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

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

    private static final String ROLE_VALUE = "Raw provider role, sent as an ordinary URL-encoded query value (role "
            + "`/analytics/viewers` is `?role=%2Fanalytics%2Fviewers`, `team%ops` is `?role=team%25ops`). Any character is "
            + "allowed. Trimmed; blank, or given more than once, is 400 bad_request.";
    private static final String ROLE = ROLE_VALUE + " Missing is 400 bad_request too.";
    private static final String ROLE_OR_LIST = "Optional: omit it to list every role's entry, give it to read one role's. "
            + ROLE_VALUE;

    private final TableAccessService service;

    // OpenAPI has one GET per path, so the one-role read below documents both handlers.
    @GetMapping(params = "!role")
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @Operation(hidden = true)
    public AccessEntriesDto list(@Parameter(description = "Table name") @PathVariable String name) {
        return AccessEntriesDto.of(service.list(name));
    }

    @GetMapping(params = "role")
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @Operation(operationId = "getTableAccess",
            summary = "List a table's access entries, or read one role's entry",
            description = "Without `role`, returns `{generation, roles, ineffective_grants}`: every role's entry on the table, "
                    + "keyed and sorted by role, the table's access `generation`, and every entry's report grouped by role in "
                    + "that order; 403 without TABLE_GRANT, then 404 for an unknown table. "
                    + "With `role`, returns `{generation, role, privileges, rows, columns, ineffective_grants}`: that role's "
                    + "entry, the generation and that entry's report; 400 for a blank or repeated role, 403 without "
                    + "TABLE_GRANT, then 404 for an unknown table, then 404 when the role has no entry. `ineffective_grants` "
                    + "is the report a PUT returns, judged on the entries as stored at the read, and `[]` when empty.",
            parameters = @Parameter(name = "role", in = ParameterIn.QUERY, description = ROLE_OR_LIST, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "200", description = "Every entry without `role`, one role's entry with it",
            content = @Content(schema = @Schema(oneOf = {AccessEntriesDto.class, AccessEntryDto.class})))
    public AccessEntryDto get(
            @Parameter(description = "Table name") @PathVariable String name,
            @Parameter(hidden = true) @RequestParam String role) {
        return AccessEntryDto.of(service.get(name, role));
    }

    @PutMapping
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @Operation(operationId = "replaceTableAccess",
            summary = "Replace one role's access entry",
            description = "Replaces the role's whole entry: absent or null `privileges` means none, absent `rows` or "
                    + "`columns` means null, and no other role's entry changes. An entry left with no privileges, no `rows` "
                    + "and no `columns` is removed. `generation` is the table's generation as last read: a PUT that would "
                    + "store exactly the entry the role already has answers 200 and writes nothing, whatever generation it "
                    + "carries (a retry is safe); otherwise a generation above the table's, or older than the role's last "
                    + "change, is refused 409 access_conflict (read the access again and reapply), while a change to another "
                    + "role since the read does not conflict. A missing or non-integer generation is 422. A PUT that changes "
                    + "the entry increments the generation by one. `rows` is `{\"filter\": ...}` or "
                    + "`{\"where\": \"<SQL condition>\"}`, stored and returned as `filter`. `columns` is a non-empty "
                    + "allowlist of the table's exposed column names and `<enrichment>.<column>` for its enrichments' "
                    + "columns. Checks run in this order: 400 for a missing, blank or repeated role or a body that does not bind, "
                    + "403, 404 unknown table, 409 enrichment_follows_source (an enrichment's entry takes only write and modify, "
                    + "judged on the body as sent), 422 unusable generation, 200 unchanged entry, 409 access_conflict, 422 "
                    + "role (bundle or privilege name), 409 table_is_system (write or modify on a system table), then the "
                    + "remaining 422 checks. 409 table_busy when another change to the table holds it past the lock "
                    + "timeout. `ineffective_grants` reports privileges that change nothing: already_global, "
                    + "sensitive_without_verb, lifts_row_policies and lifts_column_policies; and the standing warning "
                    + "everyone_reads_all_rows, a read for `*` with no row policy on a table that is not an enrichment.")
    @ApiResponse(responseCode = "409", description = "access_conflict (the role changed since it was read), "
            + "enrichment_follows_source, table_is_system or table_busy")
    public AccessEntryReplacedDto put(
            @Parameter(description = "Table name") @PathVariable String name,
            @Parameter(description = ROLE) @RequestParam String role,
            @RequestBody PutAccessEntryRequest request) {
        final RowsRequest rows = request.rows() == null ? null : new RowsRequest(request.rows().filter(), request.rows().where());
        return AccessEntryReplacedDto.of(service.put(name, role,
                new EntryRequest(request.generation(), request.privileges(), rows, request.columns())));
    }

    @DeleteMapping
    @RequiresPrivilege(Privilege.TABLE_GRANT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteTableAccess",
            summary = "Remove one role's access entry",
            description = "Removes the role's entry (privileges, rows and columns) and increments the table's access "
                    + "generation. `generation` is optional: without it the entry is removed whatever changed since it was "
                    + "read; with it, a value above the table's generation or older than the role's last change is refused "
                    + "409 access_conflict, and a non-integer one 422. Checks run in this order: 400 for a missing, blank or "
                    + "repeated role, 403 without TABLE_GRANT, 404 for an unknown table, 422 unusable generation, 404 when the role "
                    + "has no entry, 409 access_conflict. 409 table_busy when another change to the table holds it past the "
                    + "lock timeout.")
    @ApiResponse(responseCode = "409", description = "access_conflict (the role changed since it was read) or table_busy")
    public void delete(
            @Parameter(description = "Table name") @PathVariable String name,
            @Parameter(description = ROLE) @RequestParam String role,
            @Parameter(description = "Optional: the table's access generation as last read, an integer") @RequestParam(required = false) String generation) {
        service.delete(name, role, generation);
    }

    /**
     * A missing role is unbound; a blank one is trimmed to nothing and so missing too; a repeated one would bind
     * comma-joined, as a role nobody carries: all {@code 400} before any check.
     */
    @InitBinder("role")
    void bindRoleTrimmed(WebDataBinder binder, WebRequest request) {
        final String[] roles = request.getParameterValues("role");
        if (roles != null && roles.length > 1) {
            throw new InvalidRequestParameterException("role must be given once");
        }
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
    }
}
