## Why

The data-sync job hard-codes the target table's structure in Java: `RowMapper` reads fixed JSON
paths, `UsageLogRow` declares a fixed set of 19 typed fields, and `toColumnMap()` pins the exact
ClickHouse column names. Any change to the table — a new column, a renamed field, a different source
path — requires a code change and redeploy. The structure needs to be configurable so operators can
evolve the table without touching code.

## What Changes

- Introduce an external **mapping file** (referenced by a new `sync.mappingFile` env-driven path)
  that declares the target table's columns: for each column, its name, source JSON path, extraction
  kind, optional transform, and optional default.
- Replace the hard-coded `RowMapper` → `UsageLogRow` → `toColumnMap()` chain with a generic,
  config-driven mapping engine that resolves each declared column from a parsed record and emits a
  `Map<String, Object>` keyed by ClickHouse column name.
- **Allowlist semantics**: only columns declared in the mapping are read from the source and written
  to ClickHouse. The "drop sensitive payload fields" guarantee is preserved by construction — heavy
  fields like `request.body` are simply never declared, so they can never be persisted.
- A **closed, named transform registry** (`iso-to-ch-datetime`, `string-array`) reproduces the
  existing timestamp normalization and string-array handling. No expression language.
- A **`$sourceFile`** synthetic source token injects the originating blob name (provenance + dedup
  key) into a declared column.
- **Fail-fast startup validation**: the service introspects the target table (`DESCRIBE TABLE`) at
  boot and refuses to start if the configured columns don't reconcile with the table's actual
  columns/types.
- The shipped mapping file reproduces the current `dial_usage_log` schema exactly, so default
  behavior is unchanged.

Out of scope (unchanged by this proposal):
- **Operator owns DDL** — the service still issues no `CREATE`/`ALTER`; the table is pre-provisioned.
- **Single feed → single table** — multi-feed / multi-table support is not introduced here.
- `LogParser` (gzip NDJSON streaming) and `UsageLogWriter` (batched `JSONEachRow` insert) are
  largely unchanged; `UsageLogWriter.insert` operates on generic row maps instead of `UsageLogRow`.

## Capabilities

### New Capabilities
<!-- None — this reshapes how the existing data-sync capability maps records. -->

### Modified Capabilities
- `data-sync`: The "Log parsing and transformation" and "Sensitive payload fields are dropped"
  requirements change from a hard-coded mapping to a config-driven mapping engine; a new requirement
  covers the external mapping file, the transform registry, and fail-fast startup validation against
  the table schema.

## Impact

- **Code**: `RowMapper`, `UsageLogRow`, `UsageLogWriter` (insert signature), `DataSyncService`
  (wiring), and the `sync.*` configuration (`SyncProperties` + new mapping-file loading). New
  mapping-engine and mapping-config classes under `com.epam.aidial.analytics.sync`.
- **Config**: New `sync.mappingFile` env var; a shipped default mapping file mirroring the current
  `dial_usage_log` columns. Documented in `application.yml` + `README.md`.
- **Behavior**: No change to ingested data by default. New startup dependency — ClickHouse must be
  reachable at boot for the schema-reconciliation check.
- **Tests**: `RowMapperTest` is replaced/repurposed by mapping-engine tests; new tests for transform
  registry, allowlist behavior, `$sourceFile` injection, and startup validation (match + mismatch).
