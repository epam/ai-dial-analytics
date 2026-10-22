package com.epam.aidial.analytics.web.controller.table;

import com.epam.aidial.analytics.service.audit.AuditSnapshotGuards;
import com.epam.aidial.analytics.service.audit.AuditSnapshotService;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.TableCatalogService;
import com.epam.aidial.analytics.service.table.TableDataService;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import com.epam.aidial.analytics.service.table.TableSchemaService;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest;
import com.epam.aidial.analytics.web.dto.table.DefineSchemaRequest;
import com.epam.aidial.analytics.web.dto.table.PatchSchemaRequest;
import com.epam.aidial.analytics.web.dto.table.TableDto;
import com.epam.aidial.analytics.web.dto.table.TableListDto;
import com.epam.aidial.analytics.web.dto.table.UpdateTableRequest;
import com.epam.aidial.analytics.web.dto.table.WriteRowsRequest;
import com.epam.aidial.analytics.web.dto.table.WriteRowsResponse;
import com.epam.aidial.analytics.web.mapper.table.TableMapper;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Table-management API with an explicit lifecycle: create records identity ({@code POST /v1/tables},
 * status {@code PENDING}, no ClickHouse DDL); {@code POST /{name}/schema} defines the complete physical
 * schema and materializes the table in one call (issues {@code CREATE TABLE}, status {@code ACTIVE});
 * a materialized table's columns then evolve via {@code PATCH /{name}/schema} (immediate {@code ALTER}s).
 * {@code PUT /{name}} updates catalog metadata (description, tag ordering) and {@code POST /{name}/rows}
 * writes rows. The catalog drives the query path under {@code /v1/queries} — a source plus its
 * enrichments flattens into one queryable entity.
 */
@RestController
@RequestMapping("/v1/tables")
@RequiredArgsConstructor
@Tag(name = "Tables", description = "Manage the catalog of ClickHouse tables and their column mappings.")
public class TableController {

    private final TableCatalogService catalogService;
    private final TableSchemaCreationService schemaCreationService;
    private final TableSchemaService schemaService;
    private final TableDataService dataService;
    private final AuditSnapshotService snapshotService;
    private final TableMapper mapper;
    private final SecurityModeResolver securityMode;
    private final SecurityClaimsExtractor claims;

    @PostMapping
    @RequiresPrivilege(Privilege.TABLE_CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a table definition (identity only)",
            description = "Records the table identity as PENDING and issues no ClickHouse DDL. Define its "
                    + "physical schema and materialize it via POST /{name}/schema. Columns and physical keys "
                    + "are not accepted here (422).")
    public TableDto create(@Valid @RequestBody CreateTableRequest request) {
        return mapper.toDto(catalogService.create(mapper.toCreateInput(request)));
    }

    @PostMapping("/{name}/schema")
    @RequiresPrivilege(value = Privilege.TABLE_MODIFY, table = "name")
    @Operation(summary = "Define and materialize a table's schema",
            description = "Sets the complete physical schema (columns plus physical keys — ordering_key/"
                    + "partition_by for a source, grain_key/cardinality for an enrichment — and an optional "
                    + "row-expiry ttl) of a "
                    + "not-yet-materialized table and issues CREATE TABLE in ClickHouse, flipping it to ACTIVE. "
                    + "Rejected if already materialized (409) or the schema is incomplete/inconsistent (422); "
                    + "on a ClickHouse failure the table is marked FAILED and may be retried. For an "
                    + "**enrichment**, the five provenance columns are **injected** if you do not declare "
                    + "them — `pipeline_generation` (long), `model` "
                    + "(string), `enriched_at` (timestamp), `group_version` (long), `truncated` (boolean) "
                    + "— appended in that order with the `system` tag. A provenance column you declare "
                    + "yourself is left exactly as you declared it: injection only ever adds, never "
                    + "duplicates, overwrites or reorders. `model`, `group_version` and `truncated` stay "
                    + "empty on an enrichment computed by a `sql` transform, which makes no model call and "
                    + "has no group. The retired `evaluator_version` is not injected: nothing has written "
                    + "it since the evaluator was folded into its pipeline.")
    public TableDto defineSchema(
            @Parameter(description = "Table name") @PathVariable String name,
            @Valid @RequestBody DefineSchemaRequest request) {
        return mapper.toDto(schemaCreationService.defineSchema(name, mapper.toDefineInput(request)));
    }

    @GetMapping
    @RequiresPrivilege(value = Privilege.TABLE_LIST, deferred = true)
    @Operation(summary = "List table definitions", description = "Returns all table definitions, ordered by name.")
    public TableListDto list() {
        return mapper.toListDto(catalogService.list());
    }

    @GetMapping("/{name}")
    @RequiresPrivilege(value = Privilege.TABLE_GET, table = "name")
    @Operation(summary = "Get a table definition", description = "Returns the definition and its exposed columns.")
    public TableDto get(@Parameter(description = "Table name", example = "dial_usage_log") @PathVariable String name) {
        return mapper.toDto(catalogService.get(name));
    }

    @GetMapping("/{name}/revision/{revision}")
    @RequiresPrivilege(value = Privilege.TABLE_GET, table = "name")
    @Operation(summary = "Get a table definition as it stood at a revision",
            description = "Returns the same shape as GET /{name}, reconstructed from the audit trail at "
                    + "the end of the given revision — the columns, ordering key and grain it had then, "
                    + "excluding columns dropped by then. 404 if the table did not exist at that revision "
                    + "or the revision is unknown. Revision ids come from the activity feed. A caller holding "
                    + "TABLE_GRANT (every caller in `none` mode) additionally receives the table's `access` "
                    + "lists and `row_policies` as of that revision.")
    public TableDto snapshot(
            @Parameter(description = "Table name", example = "dial_usage_log") @PathVariable String name,
            @Parameter(description = "Revision id, as carried by an activity") @PathVariable String revision) {
        // No gate of its own, exactly like the live read above it: a past state must be neither more nor
        // less accessible than the present one. Note the permissions member of the returned DTO is
        // resolved from that revision's access lists, so it answers "what could this caller have done
        // then" — consistent with every other field of a point-in-time read. The grant members follow the
        // live grant reads' bar (global TABLE_GRANT), so a past grant is never more visible than a present one.
        final boolean includeGrants = !securityMode.isEnforced() || claims.hasPrivilege(Privilege.TABLE_GRANT);
        return mapper.toDto(snapshotService.snapshot(name, AuditSnapshotGuards.parseRevision(revision)), includeGrants);
    }

    @PutMapping("/{name}")
    @RequiresPrivilege(value = Privilege.TABLE_MODIFY, table = "name")
    @Operation(summary = "Update a table definition",
            description = "Updates catalog metadata only: the description and the per-table tag ordering.")
    public TableDto update(
            @Parameter(description = "Table name") @PathVariable String name,
            @Valid @RequestBody UpdateTableRequest request) {
        return mapper.toDto(catalogService.updateMetadata(
                name, request.description(), mapper.toTagOrderChange(request)));
    }

    @DeleteMapping("/{name}")
    @RequiresPrivilege(Privilege.TABLE_DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a table definition",
            description = "Drops the ClickHouse table. Blocked (409) if a source still has enrichments, or "
                    + "if a pipeline reads this table as its input — that dependency breaks here rather "
                    + "than at the pipeline's next refresh. A pipeline whose *target* this is goes with the "
                    + "table instead: it exists only to fill it.")
    public void delete(@Parameter(description = "Table name") @PathVariable String name) {
        catalogService.delete(name);
    }

    @PatchMapping("/{name}/schema")
    @RequiresPrivilege(value = Privilege.TABLE_MODIFY, table = "name")
    @Operation(summary = "Evolve a materialized table's schema",
            description = "Applies add/drop/rename column operations and the optional ttl merge-patch (set/replace "
                    + "with a full object, remove with {}) as ONE multi-command ClickHouse ALTER statement — "
                    + "validated by ClickHouse as a whole, so it is rejected or applied in its entirety — plus "
                    + "catalog-only column metadata updates, on an ACTIVE table. Rejected (409) before "
                    + "materialization — define and materialize the "
                    + "schema first via POST /{name}/schema. Type changes and drops/renames of system, grain, or "
                    + "ordering-key columns are rejected (422), as is a drop of the TTL-referenced column. On an "
                    + "enrichment, dropping or renaming a column another pipeline reads, or one that receives a "
                    + "transform output of the pipeline targeting it, is rejected (409) naming those pipelines — "
                    + "checked before any ALTER is issued, so a refused patch leaves ClickHouse untouched. For an "
                    + "enrichment, this is where you add a provenance column an already-materialized target "
                    + "is missing — `pipeline_generation` (long), `model` "
                    + "(string), `enriched_at` (timestamp), `group_version` (long), `truncated` (boolean); "
                    + "one that is absent is silently skipped on write. This endpoint does **not** inject "
                    + "them: it applies exactly the operations you list, because widening an explicit "
                    + "operation list into a five-column DDL on a populated table is not something to do on "
                    + "a caller's behalf. `pipeline_generation` is the exception you do not have to add by "
                    + "hand: a startup reconciler adds it to every enrichment an `enrich` pipeline targets. "
                    + "On an `llm` target, a patch that edits a column's `description` bumps the targeting "
                    + "pipeline's `generation`, because an output's prose defaults to that description and "
                    + "it is therefore a live input to the composed prompt.")
    public TableDto patchSchema(
            @Parameter(description = "Table name") @PathVariable String name,
            @Valid @RequestBody PatchSchemaRequest request) {
        return mapper.toDto(schemaService.patchSchema(name, mapper.toPatchInput(request)));
    }

    @PostMapping("/{name}/rows")
    @RequiresPrivilege(value = Privilege.TABLE_WRITE, table = "name")
    @ApiResponse(responseCode = "503", description = "`table_relayout_in_progress`: the table is being relaid out by "
            + "ADAS and holds its writers for a minute or two; retry the same request shortly")
    @Operation(summary = "Write rows into a table",
            description = "Inserts rows (each keyed by physical column name) into the table's ClickHouse table "
                    + "using the writer credential. The minimal data-write surface for populating sources and "
                    + "enrichments; the table must be ACTIVE. A row of an enrichment aligned with its source "
                    + "(`partition_by` set) must carry the source row's value of that column, which the table is "
                    + "partitioned and joined by.")
    public WriteRowsResponse writeRows(
            @Parameter(description = "Table name") @PathVariable String name,
            @Valid @RequestBody WriteRowsRequest request) {
        return new WriteRowsResponse(dataService.write(name, request.rows()));
    }
}
