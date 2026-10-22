## Why

Operators build tables and pipelines by hand on one environment, then rebuild them on another with a long, error-prone
sequence of API calls. GET responses cannot be posted back: they carry server-owned fields (`status`, `grain`, `generation`, `state`)
that the strict request bodies refuse, a table takes two calls (create, then define schema), and folded transforms carry `output_vars`,
which create refuses. A one-call export and a validated one-call import make promotion repeatable, including into an environment that
already holds some of the objects.

## What Changes

- New `POST /v1/catalog/export` returns a versioned JSON bundle (`format: adas-catalog`, `format_version: 1`) of the components
  listed in the body (`table` / `pipeline` by name). An empty list exports everything. The dependency closure is computed in both
  directions, including enrichment references that appear only inside filter and measure expressions.
- New `POST /v1/catalog/export/preview` returns the resolved object list, each with the reason it was included, without the file.
- New `POST /v1/catalog/import/preview` and `POST /v1/catalog/import` take a `multipart/form-data` upload (`file` + optional `options`)
  and a required `resolution_policy` query parameter (`FAIL_IF_EXISTS` / `SKIP_IF_EXISTS`; `OVERWRITE` is refused with `422`). These
  mirror deployment manager's config export/import, so the admin UI can drive both services the same way.
- Import never mutates an existing object, system-table grants included. System tables travel as external references (column
  fingerprint + grants for comparison only), and OTLP landing tables and PENDING/FAILED tables are excluded.
- Both import calls share one validation pass: it runs the real create services inside a rolled-back Postgres transaction with
  ClickHouse DDL replaced by a no-op, then the arm-time checks, so preview reports `armable` per pipeline. `/import` then applies
  step by step, and on the first failure compensates best-effort by deleting what it created. Pipelines always land disabled.
- A pipeline name that `pipeline_aud` shows as deleted earlier is refused unless `options.acknowledge_reused_names` is true, because
  runner state (scan cursor, DLQ, spend counters) is keyed by pipeline name and is never deleted.
- An optional `options.role_map` rewrites grant roles. Every other environment-specific value is listed in `env_specific`.
- New error codes: `catalog_import_invalid` (422), `catalog_import_conflict` (409), `catalog_bundle_version_unsupported` (422),
  `resolution_policy_unsupported` (422), `catalog_export_invalid` (422), `payload_too_large` (413).
- New config keys: the multipart size limits (`CATALOG_IMPORT_MAX_FILE_SIZE`, `CATALOG_IMPORT_MAX_REQUEST_SIZE`). These are ADAS's
  first multipart endpoints.
- No Flyway migration. No change to existing endpoints' behaviour.

## Capabilities

### New Capabilities
- `catalog-export-import`: bundle format and versioning, export selection and closure, import preview/apply with collision policy,
  validation pass, compensation, re-used name guard, `role_map`, authorization, and the post-import runbook.

### Modified Capabilities
None. Behaviour shared with `table-management`, `table-access-control` and `pipeline-registry` (create services, grant ordering,
disabled create) is reused unchanged and specified from the import side in `catalog-export-import`.

## Impact

- **API**: four new `/v1/catalog/*` endpoints. Nothing under `/v1/pipelines`, so the runner contract (`Adas-Contract-Version`)
  is unaffected.
- **Code**: new `service/catalog/transfer/` (export service, import planner, validator, import service);
  `web/controller/catalog/CatalogTransferController`, DTOs in `web/dto/catalog/`; inverse mappers in `TableMapper` and
  `PipelineMapper.toRegistration`; a per-call DDL seam in `TableSchemaCreationService`; public static helpers extracted from
  `TableCatalogService` / `TableColumnRules` (no behaviour change); new handlers in `DefaultExceptionHandler`.
- **Config**: `spring.servlet.multipart.max-file-size` / `max-request-size`, with `docs/configuration.md` rows.
- **Docs**: new `docs/catalog-promotion.md` runbook; README section; CLAUDE.md surface list.
- **Follow-ups outside this repo**: `adas-mcp` export/import tools; OpenAPI snapshot refresh; runner state keyed by pipeline
  incarnation (which would let the re-used name guard go).
