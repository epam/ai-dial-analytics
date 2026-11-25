## Why

A catalog import only creates objects: an existing table or pipeline is kept unchanged under `SKIP_IF_EXISTS` (reported `SKIP`
with `differs: true`), and `OVERWRITE` is refused with `422 resolution_policy_unsupported`. A bundle that is imported into many
environments and then evolves — the recommended catalog in `docs/recommended-catalog/` is the main case — can therefore only
reach an environment that already has its objects through hand-written steps: re-declare the pipeline with the bundle's
expressions, patch the column descriptions, add the new column, each by an admin who knows the system, on every environment.
Every change to the recommended catalog so far has been of that kind (a new nullable column, changed descriptions, changed
`enrich` output expressions). Import needs a mode that evolves an existing object towards the bundle wherever the existing
mutation services allow it safely.

## What Changes

- `resolution_policy=OVERWRITE` is accepted on both import calls instead of refused. Under it an existing ACTIVE user table or
  pipeline whose definition differs from the bundle is **updated** in place, through the same services `PUT /v1/tables/{name}`,
  `PATCH /v1/tables/{name}/schema` and `PATCH /v1/pipelines/{name}` use:
  - a table's `description` and `tag_order`, each column's `description`, `display_name`, `tag` and `heavy` follow the bundle;
  - a column the bundle has and the target lacks is added;
  - a column's `sensitive` flag is tightened (`false` → `true`) and never loosened;
  - a pipeline whose declaration differs is re-declared from the bundle.
- `OVERWRITE` never drops, renames or retypes anything. A column the target has and the bundle lacks, a column order that differs,
  and a loosening of `sensitive` are kept and reported. A difference no safe path reaches — a column's `type`, `element_type`,
  `nullable` or enum values; a table's `type`, `write` or frozen keys; a pipeline's `kind` or `target`; or a table with a column
  hidden from the caller — makes that entry `FAIL`, so the import is refused as any other validation failure is.
- **Target-owned fields** keep the target's value on an updated object: every `advanced` member, `trigger.cron`, the table `ttl`,
  and the deployment binding `transform.preset` / `transform.model` / `transform.route` while the bundle's preset equals the
  target's (a changed preset takes the bundle's whole binding). The `env_specific` report covers exactly this field set, on created
  and updated objects alike, and each item gains the target's value and whether the import keeps it.
- An enabled `aggregate` pipeline that is updated is disabled first, since `PATCH` refuses re-declaring an enabled aggregate, and
  stays disabled, like a created one: the operator re-arms it in dependency order. An `enrich` pipeline is re-declared in place and
  keeps its `enabled` state.
- `ImportAction` gains `UPDATE`; an entry whose differences are all kept is `SKIP`, so re-running a bundle converges and writes no
  revision. Object entries gain `changes[]` (what the import does), `not_applied[]` (what it keeps, and why) and `effects[]` (the
  consequences it does not act on: a generation bump, an aggregate rebuild from zero, a pipeline to re-arm, existing rows not
  re-derived). Under `SKIP_IF_EXISTS` an entry also lists the `changes` `OVERWRITE` would make. `ImportResult` entry `status` gains
  `updated`.
- Updating needs, programmatically: `TABLE_MODIFY` on each updated table, `TABLE_SENSITIVE` when a change adds a sensitive column
  or tightens `sensitive`, and `REGISTRY_ADMIN` when a pipeline is updated.
- Compensation reverts updates as well as deleting creations: a pipeline is re-declared to its previous declaration and `enabled`,
  and a table's metadata is restored and the columns this import added are dropped. Reverts carry the import's `import_id`.
- **BREAKING (API)**: the `422 resolution_policy_unsupported` error code is withdrawn; a client that sends `OVERWRITE` now gets an
  import. New enum values appear in `import_action` (`UPDATE`) and `status` (`updated`).
- The bundle's shape does not change, so `format_version` stays `4`.
- `docs/catalog-promotion.md` gains a re-promotion step, and `docs/recommended-catalog/README.md` an "Updating an installation"
  section.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `catalog-export-import`: the Purpose, "Resolution policy" (defines `OVERWRITE`), "Import entries" (`UPDATE`, `changes`,
  `not_applied`, `effects`, `updated`), "Shared validation pass" (runs the update services), "Import authorization" (update
  privileges), "Pipelines are imported disabled" (an updated aggregate lands disabled), "Best-effort compensation" (reverts),
  "Environment-specific values reported" (the target-owned field set, kept on update) and the runbook requirement change. A new
  requirement, "Updating an existing object", defines the diff and what may and may not be updated.
- `pipeline-registry`: "Imported pipelines are registered disabled through the registration path" no longer says an import never
  patches an existing pipeline; it re-declares one through the `PATCH` path under `OVERWRITE`.
- `table-management`: "Table definitions are promoted between environments through catalog export and import" no longer says an
  import never patches an existing table; under `OVERWRITE` it updates one through the update and schema-patch paths.

## Impact

- **API**: `POST /v1/catalog/import` and `/import/preview` accept `resolution_policy=OVERWRITE`. `ImportEntry` gains `changes`,
  `not_applied` and `effects`; `EnvSpecificValue` gains `target_value` and `kept`; `import_action` gains `UPDATE`; `status` gains
  `updated`. `resolution_policy_unsupported` is removed from the error codes. `docs/openapi.json` is regenerated.
- **Code**: `service/catalog/transfer/` (`CatalogImportPlanner`, `CatalogImportValidator`, `CatalogImportService`,
  `PlannedObject`, new `ObjectDiff` and `TargetOwnedFields`), `web/dto/catalog/` (`ImportAction`, `ImportEntry`,
  `EnvSpecificValue`, `ResolutionPolicy`), `service/table/TableSchemaService` (a `DdlExecutor` overload of `patchSchema`),
  `web/mapper/pipeline/PipelineMapper` (a re-declaration built from a bundle pipeline), `CatalogTransferController` (policy
  description), and the removal of `ResolutionPolicyUnsupportedException` and its handler.
- **Data**: no migration.
- **Config**: no new property.
- **Compatibility**: bundles are unchanged and stay `format_version` `4`; `FAIL_IF_EXISTS` and `SKIP_IF_EXISTS` behave as before
  apart from the added `changes` preview and the wider `env_specific` list.
