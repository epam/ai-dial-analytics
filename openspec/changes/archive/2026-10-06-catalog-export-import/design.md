## Context

Promotion from one environment to another is a manual replay of API calls today. GET shapes are not request shapes, so they can't be posted
back:
- `TableDto` carries server fields, and strict bodies refuse them (`StrictRequestJsonConfiguration`).
- Table create refuses `columns`, keys and `ttl` (`TableMapper:55-59`).
- A table is two calls (create, then define schema), and ClickHouse DDL runs only in define schema.
- Folded transforms carry `output_vars`, which `CreateTransformRequest` refuses.

Deployment manager already ships a config export/import (`ConfigController`: `/export[/preview]`, `/import[/preview]`, multipart
import, `resolutionPolicy`). The admin UI drives it, and it should drive ADAS the same way.

Requirements are in
`specs/catalog-export-import/spec.md`.

## Goals / Non-Goals

**Goals:**
- One call exports a self-contained bundle of user tables, columns, grants and pipelines. Another call validates it and recreates
  them on another environment.
- Preview and apply share a single validation pass, so they cannot disagree.
- Repeat promotions into a partly populated environment converge.

**Non-Goals:**
- Declarative upsert: `OVERWRITE`, schema PATCH on import, replacing pipelines.
- OTLP sinks and their landing tables, saved queries (owner-scoped), and evaluators (a read-only archive since the fold).
- Moving data, runner state or backfill state.
- Writing grants on system tables.
- A lock against concurrent imports.

## Decisions

### D1. Server endpoints reusing the create services in-process
Rejected: a client-side replay tool. It would have to re-implement provenance injection, the two-step table create and the
`output_vars` handling, and it would drift as those change. Running the real services also means validation is the real validation.

### D2. Mirror deployment manager's API shape and vocabulary
Export selection goes in a JSON body, import is a multipart upload, and each call has a preview twin. `resolution_policy` takes DM's
enum names, and `import_action` uses `CREATE`/`SKIP`/`FAIL`. ADAS spells the parameter in snake_case, as its query parameters are.

Rejected:
- a `dry_run` flag: a separately named safe operation is harder to misuse;
- a JSON-body import: the UI would then need two upload paths.

ADAS deviates from DM in three places:
- `next` is kept on `SKIP` entries, so the UI can show the diff behind `differs`;
- there is no `UPDATE` action;
- validation is deeper: DM runs a static `collectErrors`, ADAS runs the real services (D4).

### D3. Import never mutates an existing object; `OVERWRITE` is refused
Overwriting would need:
- schema PATCH, whose DDL runs before the catalog write;
- a generation bump, which stalls rollups and re-derives nothing;
- pipeline replacement under a live runner.

That is a separate declarative-upsert feature. `SKIP_IF_EXISTS` plus `differs`/`prev`/`next` lets the operator see drift and fix
it with the existing PATCH endpoints.

The same rule covers system tables. External-reference grants are compared and reported, never written, because a promotion must not
silently widen or narrow the target's access to `dial_usage_log`. Rejected: applying them under a flag. Grants on a target's system tables are an
operator decision, made through `PUT /v1/tables/{name}/access`.

### D4. Validation = real services in a rolled-back transaction + no-op DDL
`CatalogImportValidator` opens one Postgres transaction, marks it rollback-only, and calls `create`, `defineSchema`,
`replaceAccess` and `PipelineService.create` in topological order. It then runs `AggregateSpecValidator` / `EnrichSpecValidator` plus
`requireNotWidenedAgainst` on each pipeline to fill `armable`.

This works because:
- the services have no `REQUIRES_NEW`, so they join the outer transaction;
- the validators read Postgres only.

ClickHouse DDL needs a seam. `TableSchemaCreationService` holds a concrete `ClickHouseDdlExecutor` (:48), also used by `safeDrop`.
The plan is to extract a small `DdlExecutor` interface and add an internal `defineSchema` overload that takes the executor. Validation
passes a no-op; the public method passes the real one. Swapping the bean globally is rejected, because a concurrent real request would
then skip its DDL.

Rejected: a pure planner that re-implements the checks. It would drift from the services, and it couldn't report `armable`.

**Spike gate:** task 1 prototypes the seam. If it turns out invasive, stop and return to the user before falling back to planner-only
validation, which would report `armable` as unknown.

Limitation: a SQL-level failure aborts the Postgres transaction, so the pass stops at that object. Only Java-side refusals are collected
exhaustively.

### D5. Apply is step-committed with compensation, not one transaction
DDL can't share a Postgres transaction, and each step should be its own Envers revision under the importing user. So
`CatalogImportService.apply` is not `@Transactional`. It follows `OtelSinkService`:
1. check every collision up front;
2. commit step by step;
3. on the first failure, compensate in reverse order through `TableCatalogService.delete` / `PipelineService.delete`.

`TableCatalogService.delete` also deletes any pipeline targeting the table. That's why the ownership check runs before each delete.

Rejected: an advisory lock. There's no precedent for one, and per-object uniqueness (`existsById`, `UNIQUE(target)`) already makes
concurrent imports fail safely.

### D6. Bundle built from request shapes, parsed in two stages
- Tables travel as `CreateTableRequest` + `DefineSchemaRequest`, through new inverse mappers in `TableMapper`.
- Pipelines travel as the existing `view=source` projection (the GET→PATCH round-trip contract). The transform is
  `PatchTransformRequest`-shaped, so `output_vars` passes through.
- A new `PipelineMapper.toRegistration(BundlePipeline)` reuses `transformFieldsOf` / `toSpec`, keeps `requireKindFields`, and forces
  `enabled=false`.

Rejected: converting `output_vars` to `outputs`. `EnrichResolver.composeSchema` builds `response_format` only from `outputs`, so the
model's behaviour would change.

**Parsing.** The `file` part is a `MultipartFile`, which the HTTP converter doesn't bind. Import therefore parses it explicitly:
1. Stage 1 reads the envelope into a `JsonNode` with the lenient primary mapper, and checks `format` and `format_version`.
2. Stage 2 binds with the converter's strict mapper and runs `Validator.validate` by hand. `@Valid` on a part covers only parts Spring
   binds itself.

This order is what gives a newer bundle a readable `catalog_bundle_version_unsupported` instead of an unknown-field `400`.

Bean-validation failures stay `400`, matching `DefaultExceptionHandler`. Semantic refusals are `422`.

### D7. Columns exported as stored, provenance included
`withInjectedProvenance` adds only missing names, so a fully declared enrichment round-trips byte-for-byte. Stripping provenance
columns is rejected: `evaluator_version` is never injected, so stripping it would lose it.

There is one accepted asymmetry: an enrichment with no declared provenance gains the injected columns, and they count toward the column
limit. The spec states this.

### D8. Re-used name guard instead of a runner change
The runner keys its scan cursor by pipeline name (`ScanPageCommitter:71`), and its DLQ and spend counters by name and generation (runner
V9). It never deletes them, and a recreated pipeline restarts at generation 1, so it would resume from a stale cursor and skip
history.

Keying runner state by pipeline incarnation is the real fix. That is a cross-repo change and a tracked follow-up. Until it lands,
`pipeline_aud` delete revisions drive an acknowledgement guard.

### D9. 4xx bodies are summary-only `ErrorView`
`ErrorView` is the only failure shape. `/import` 4xx carries a one-line message that points to `/import/preview`, which always returns
the full report with `200`. Rejected: embedding `ImportPreview` in the error body, which would break the single failure contract.

### D10. Authorization repeated programmatically
Calling services in-process skips the controller gates, so import re-checks `TABLE_MODIFY`, `REGISTRY_ADMIN`, `TABLE_GRANT` and
`TABLE_SENSITIVE`. Every programmatic check is skipped when `SecurityModeResolver.isEnforced()` is false, as `PipelineAuthorizer`
does.

Export applies per-table visibility (`404`) and sensitive clearance (`403`). The `403` is a post-fetch denial on a table the caller is
already allowed to see, so it doesn't leak existence.

Neither path is under `/v1/pipelines`, so the runner contract is unaffected.

## Risks / Trade-offs

- [DDL seam proves invasive] → Spike first, with a stop-and-ask gate (D4).
- [Compensation can leave orphans: a crash, or a ClickHouse DROP that runs before the catalog commit fails] → Spec says best effort.
  Re-run with `SKIP_IF_EXISTS` converges. Each step is audited and logged under `import_id`.
- [`differs` false positive between legacy `output_vars` and an equivalent `outputs`] → Accepted and documented.
- [String comparison is sensitive to key order inside `outputs`/`inputs`] → Intended: order is meaningful there.
- [Concurrent creates can exceed `max-tables` slightly] → Existing race in `TableCatalogService.create`, documented, not fixed here.
- [`transform.model` may not exist on the target, and nothing checks it at arm time] → Listed in `env_specific`, and it is the
  runbook's first step.
- [First multipart endpoints: Spring's 1 MB default is too small] → Explicit limits, with the default sized from a measured full
  export.
- [A new upload exception falls through to the 500 catch-all] → Map `MaxUploadSizeExceededException` (413) and
  `MissingServletRequestPartException` (400) explicitly.

## Migration Plan

- No Flyway migration and no change to existing endpoints. Deploy is additive.
- Two new env vars, with defaults, documented in `docs/configuration.md`.
- Removing the feature means deleting the endpoints; nothing is persisted beyond the audit revisions of imported objects.

## Open Questions

- Default upload size: measure a full export during apply (a starting value of 20 MB is assumed).
