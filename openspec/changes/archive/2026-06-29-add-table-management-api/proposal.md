## Why

ClickHouse table structure is hardcoded in Java (`DialUsageLogSchema.java`), so every new
queryable table — and every column-exposure decision — requires a code change and redeploy. We
want operators to define tables and their column mappings at runtime, stored in Postgres, and to
expose related tables (a source plus its enrichments) as a single flat query schema. The current
`metadata` Postgres table and `/v1/metadata` endpoints are throwaway scaffolding that proved the
Postgres path; they are replaced by this catalog.

## What Changes

- **NEW** `/v1/tables` management API: create, get, list, update (description only), delete table
  definitions. A definition has `{name, description, type}` where `type ∈ {source, enrichment,
  aggregation}`; an `enrichment` also names the `source_table` it enriches at create time.
- **NEW** create-time materialization: `POST /v1/tables` issues `CREATE TABLE` in ClickHouse
  immediately using the **writer** credential. A `source` declares `columns` + `ordering_key` +
  optional `partition_by` and is created as `MergeTree`; an `enrichment` declares `source_table` +
  `grain_key` + `cardinality` and is created as a `ReplacingMergeTree(_updated_at)` shell ordered by
  its grain key. Status is `ACTIVE` (created) / `FAILED` (ClickHouse create failed → retriable).
- **NEW** `PATCH /v1/tables/{name}/schema`: evolve columns via `add`/`drop`/`rename`, each applied as
  an immediate ClickHouse `ALTER`. Type-change is excluded; system columns (reserved `_`-prefix) and
  the grain key cannot be dropped/renamed.
- **NEW** runtime ClickHouse DDL from the management path (`CREATE TABLE` at create, `ALTER` on
  `PATCH` schema, `DROP TABLE` on delete) — the first non-migration DDL ADAS performs.
- **CHANGED** the structured-query read path now derives its field bindings from the Postgres
  catalog instead of `DialUsageLogSchema`. **BREAKING (internal)**: `DialUsageLogSchema.java` is
  deleted.
- **CHANGED** schema discovery returns a **flat** schema per source: source columns + every
  enrichment's exposed columns, with system/grain columns hidden. `execute` `LEFT JOIN`s only the
  enrichments whose columns a query references, on the shared `grain_key`
  (`source.grain_key = enrichment.grain_key`); enrichments are `ReplacingMergeTree` keyed on the
  grain so the 0..1 cardinality is enforced and the join cannot fan out.
- **NEW** validation/guardrails: snake_case identifiers with a reserved `_`-prefix; per-column
  `nullable`; `array` is not user-declarable in this phase (`object`→`JSON` stays); and configurable
  caps on columns-per-table and total tables.
- **REMOVED** the unused `complex` / `schemaIdField` / `/detailed` schema-discovery machinery
  (superseded by source+enrichment flattening).
- **REMOVED** the `metadata` table and the entire `/v1/metadata` stack (entity, repository,
  service, controller, DTOs, mapper, `V1__create_metadata.sql`, `MetadataCrudIntegrationTest`), and
  the `metadata-store` capability spec — all superseded by `table-management`.
- **NEW** Postgres migration creating `table_definition`, `column_mapping`, `enrichment_grain` (and
  source `ordering_key`/`partition_by`) and backfilling `dial_usage_log` as a `source` (status
  `ACTIVE`, no `CREATE TABLE` — it already exists in ClickHouse).
- `aggregation` is accepted as a valid type for forward compatibility; no special
  materialization/query behavior is implemented for it in this change.

## Capabilities

### New Capabilities
- `table-management`: runtime catalog of ClickHouse tables (source/enrichment/aggregation) and
  their column mappings, with CRUD over definitions, schema PUT, lifecycle/status, and the
  ClickHouse materialization (CREATE/DROP) side effects.

### Modified Capabilities
- `query-schema-discovery`: schema is now sourced from the Postgres catalog and returned as a flat
  per-source view (source + enrichment columns, FK columns hidden); the `complex`/`detailed`
  instance-schema path is removed.
- `structured-query`: field bindings come from the catalog; queries spanning enrichment columns are
  resolved by automatic `LEFT JOIN`s of the referenced enrichment tables.
- `metadata-store`: **REMOVED** as a capability — the throwaway `metadata` store and its
  `/v1/metadata` endpoints are deleted and superseded by `table-management`.

## Impact

- **Code**: `service/query/` (delete `DialUsageLogSchema`, add catalog-backed schema provider and
  join-aware bindings; `StructuredQueryBuilder` gains join emission); new `service/table/` (or
  similar) catalog service + ClickHouse DDL; new `web/controller/table/` + DTOs + MapStruct mapper;
  remove `service/metadata/`, `web/controller/metadata/`, related DTOs/mapper/exceptions.
- **Persistence**: new Postgres tables via Flyway (`db/migration/postgres`); `V1__create_metadata.sql`
  deleted and replaced by the new Postgres `V1` catalog migration.
- **ClickHouse**: management path uses the **writer** credential for runtime `CREATE`/`DROP TABLE`
  (the read path stays strictly read-only). Updates the "ClickHouse read path is read-only"
  operational note in `openspec/project.md`.
- **API**: adds `/v1/tables` and `/v1/tables/{name}/schema`; removes `/v1/metadata`. New
  `ErrorView` failure modes (409 conflicts for dependent enrichments, materialized-schema re-PUT;
  422 validation) via `DefaultExceptionHandler`.
- **Docs**: `docs/configuration.md` if any new env vars; `README` capability list.
