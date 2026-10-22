## 1. Remove the throwaway metadata stack

- [x] 1.1 Delete `service/metadata/` (`MetadataEntity`, `MetadataRepository`, `MetadataService`).
- [x] 1.2 Delete `web/controller/metadata/MetadataController`, `web/dto/metadata/*`, and
  `web/mapper/metadata/MetadataMapper`.
- [x] 1.3 Delete `MetadataNotFoundException` / `MetadataAlreadyExistsException` and their
  `DefaultExceptionHandler` mappings (keep the generic `DataAccessException` →
  `postgres_unavailable` handler; it serves the catalog too).
- [x] 1.4 Delete `db/migration/postgres/V1__create_metadata.sql` (the catalog migration in §2.1
  replaces it as the new Postgres `V1`).
- [x] 1.5 Delete `integration/MetadataCrudIntegrationTest`; remove `/v1/metadata` references from
  `AnalyticsDataAccessApplicationTests`, README, and `docs/configuration.md`.
- [x] 1.6 Remove the `metadata-store` capability spec (`openspec/specs/metadata-store/`) — it is
  superseded by `table-management`; capture the removal as a spec delta in this change.

## 2. Postgres catalog: migration + persistence

- [x] 2.1 Add `db/migration/postgres/V1__create_table_catalog.sql` creating `table_definition`,
  `column_mapping`, `enrichment_join`, and `table_ordering_key` (columns, PK/FK, `type`/`status`
  constraints). This is the new Postgres `V1`, replacing the deleted `V1__create_metadata.sql`.
- [x] 2.2 In the same migration, backfill `dial_usage_log`: one `SOURCE` row with
  `status=MATERIALIZED`, its 38 `column_mapping` rows (mirroring `DialUsageLogSchema` /
  `V1__create_dial_usage_log.sql`), and `ordering_key` from the existing ClickHouse DDL. Do NOT
  re-create the ClickHouse table.
- [x] 2.3 Add JPA entities + repositories for `table_definition`, `column_mapping`, `enrichment_join`
  (+ `table_ordering_key` as an ordered `@ElementCollection`) under `service/table/`, plus
  `TableType`/`TableStatus` enums and a `QueryFieldType` JPA converter.

## 3. Table management service + ClickHouse DDL

- [x] 3.1 Add a catalog service (`TableCatalogService`): create/get/list/update(description)/delete
  over `table_definition`, enforcing name uniqueness (409), enrichment `source_table` validity (422),
  and the delete-blocked-by-enrichments rule (409).
- [x] 3.2 Add a schema service (`TableSchemaService` + transactional `CatalogSchemaWriter`) for
  `PUT schema`: validate (ordering_key/foreign_key reference declared columns; enrichment `source_key`
  exists on a `type=source` table; within-table exposed-name uniqueness (cross-table collisions are
  allowed — enrichment columns are surfaced qualified by their table name); ARRAY needs
  `element_type`; valid CH identifier), persist mapping, then materialize.
- [x] 3.3 Add a `QueryFieldType → ClickHouse DDL` type mapper (`ClickHouseTypeMapper`: `STRING→String`,
  `LONG→Int64`, `INTEGER→Int32`, `TIMESTAMP→DateTime64(3)`, `ARRAY→Array(element_type)`, etc.; columns
  non-nullable).
- [x] 3.4 Add ClickHouse DDL execution (`ClickHouseDdlExecutor`) using the **writer** credential:
  render (`TableDdlRenderer`) + run `CREATE TABLE ... ENGINE = MergeTree ORDER BY (ordering_key)`
  (adding the enrichment FK column with the source-key's inferred type) and `DROP TABLE IF EXISTS`.
- [x] 3.5 Implement lifecycle/status: `DECLARED` on create; `MATERIALIZED` on successful PUT;
  `FAILED` on ClickHouse failure; re-PUT allowed while `DECLARED`/`FAILED`, 409 once `MATERIALIZED`.

## 4. Web layer for /v1/tables

- [x] 4.1 Add `web/controller/table/TableController` for `POST/GET/GET-list/PUT(update)/DELETE
  /v1/tables` and `PUT /v1/tables/{name}/schema`.
- [x] 4.2 Add request/response DTOs (records, `@Schema`, snake_case `@JsonProperty`) and a hand-written
  `TableMapper` (domain ↔ DTO); list response is an object (`{tables:[...]}`).
- [x] 4.3 Add `@ExceptionHandler`s to `DefaultExceptionHandler` for the new failure modes
  (`TableConflictException` → 409; `TableValidationException` → 422; `TableNotFoundException` → 404),
  all via `ErrorView`.

## 5. Wire the read path to the catalog

- [x] 5.1 Delete `DialUsageLogSchema`/`DialUsageLogSchemaProvider`/`DialUsageLogQueryRepository`; add
  `CatalogQueryService` building `QueryFieldBinding`s from `column_mapping`, each field qualified by
  its owning source/enrichment table (`JooqTypes` helper for jOOQ types).
- [x] 5.2 Update schema discovery (`QuerySchemaController` → `CatalogQueryService`): list materialized
  sources only; return the flat schema = source columns + enrichment exposed columns (FK columns
  hidden).
- [x] 5.3 Remove the unused `complex`/`schemaIdField`/`detailed` machinery
  (`QueryableEntitySchemaProvider`, `QueryEntityRegistry`, `QueryEntityConsistencyCheck`,
  `StructuredQueryRepository`, `QueryEntityDto`/`QueryEntitySchemaDto` fields, `/detailed` endpoint).
- [x] 5.4 Update `StructuredQueryBuilder` (`QueryEntityModel`/`EnrichmentJoin`) to collect referenced
  fields across `select`/`filter`/`sort`/`group_by` and emit a `LEFT JOIN` per referenced enrichment
  on `source.source_key = enrichment.local_key`; route `execute` through `CatalogQueryService`.

## 6. Configuration, docs, and verification

- [x] 6.1 Update `openspec/project.md` operational note: management path performs runtime ClickHouse
  DDL (`CREATE`/`DROP TABLE`) with the writer credential; read path stays read-only.
- [x] 6.2 Updated `docs/configuration.md` (PostgreSQL Table Catalog section) and README
  (table catalog); removed `/v1/metadata` mentions. No new env vars (reuses `POSTGRES_*`/writer creds).
- [x] 6.3 Added tests: `TableCatalogServiceTest` (CRUD validation/conflict), `ClickHouseTypeMapperTest`,
  `TableDdlRendererTest`, `EnrichmentJoinRenderTest` (LEFT JOIN render), `TableManagementIntegrationTest`
  (create→materialize→query→409→delete), and updated discovery/render tests; backfill `dial_usage_log`
  verified queryable by the existing ClickHouse integration suite.
- [x] 6.4 `./gradlew clean build` passes (compile + checkstyle + tests, incl. testcontainers).

## 7. Second wave — grain join model, create-time materialization, schema evolution (D8–D13)

> Aligns the join model with the upstream table design. Supersedes the
> first-wave `foreign_key`/freeze model. Tasks 1–6 above describe what already shipped; this section
> migrates it to the new model.

- [x] 7.1 **Grain join model (D8).** Replace `enrichment_join` (`local_key`/`source_key`) with an
  `enrichment_grain` (`grain_key`, `cardinality`) catalog table + entity/repository. Migrate the
  Postgres migration accordingly (new install; edit V1 since it is unreleased scaffolding).
- [x] 7.2 **Grain validation (D8).** Enforce `grain_key ∈ source.ordering_key`; drop the
  `source_key`-exists check. Reject `cardinality != ZERO_OR_ONE`.
- [x] 7.3 **Join emission (D8/D9).** Update `StructuredQueryBuilder` to join
  `ON source.grain_key = enrichment.grain_key` — a single-key keyed lookup on `grain_key` alone.
  (The partition-aligned variant was later removed; see D9's superseded note.)
- [x] 7.4 **ReplacingMergeTree + system columns (D9).** `TableDdlRenderer` selects engine by kind:
  enrichments → `ReplacingMergeTree(_updated_at)` `ORDER BY (grain_key)`; sources → `MergeTree`.
  Stamp `_updated_at` on enrichments; do NOT add `_writer` (deferred until auth). ADAS-created tables
  never put system columns or the grain key into `column_mapping`, so the exposed flat schema hides
  them by construction. NB: the backfilled `dial_usage_log` keeps its historically-exposed
  `_ingested_at`/`_source_file` (a documented backfill exemption; changing it belongs to the
  query-schema-discovery capability, not here).
- [x] 7.5 **Source partitioning (D11).** Add `partition_by {column, granularity}` to the source
  definition + catalog; render `PARTITION BY` (e.g. `toYYYYMM(col)`); validate the column is declared.
- [x] 7.6 **Create-time materialization (D10).** `POST` issues `CREATE TABLE` immediately: source
  carries `columns`+`ordering_key`+`partition_by?`; enrichment is a grain shell. Removed the
  `DECLARED` state and the `PUT .../schema` materialize-then-freeze path; status → `ACTIVE`/`FAILED`.
- [x] 7.7 **PATCH schema evolution (D10).** Add `PATCH /v1/tables/{name}/schema` with
  `add`/`drop`/`rename` → immediate ClickHouse `ALTER`; reject type-change, and drop/rename of system
  columns, the grain key, or ordering-key columns (422). Existing `TableValidationException`→422
  handler reused.
- [x] 7.8 **Read schema on GET (D10 support).** `GET /v1/tables/{name}` returns the current exposed
  `columns` (and grain/partition_by) so operators can see what to evolve.
- [x] 7.9 **Identifiers (D12).** Tighten user-declared identifiers to `^[a-z][a-z0-9_]*$`, ≤64 chars,
  reject the `_`-prefix on API input (`requireUserIdentifier`); a looser renderable grammar still
  quotes system/backfilled columns.
- [x] 7.10 **Nullability (D12).** Add `nullable` to column declarations → `Nullable(T)`; enrichment
  user columns default nullable, source columns default non-nullable.
- [x] 7.11 **Postpone ARRAY in user schemas (D12).** Reject `type=array`/`element_type` on
  create/`PATCH`; keep `array` valid only for the backfilled `dial_usage_log` (`execution_path`),
  which is inserted via migration and bypasses the API validator.
- [x] 7.12 **Limits (D13).** Add `tables.limits.*` config (`max-columns-per-table`=256,
  `max-tables`=1000, `max-write-batch-rows`=10000); enforce columns/tables caps at POST/PATCH and the
  write-batch cap at row-write (422); documented in `docs/configuration.md`.
- [x] 7.14 **Row-write API.** `POST /v1/tables/{name}/rows` inserts rows (keyed by physical column
  name) into a materialized table via the writer credential (`CatalogRowWriter` + `TableDataService`
  + `WriteRowsRequest`/`WriteRowsResponse`); 404 unknown table, 409 not materialized, 422 unknown
  column. Covered by `EnrichmentJoinIntegrationTest` (write source + enrichment rows, verify the grain
  LEFT JOIN resolves). NB: once limits (7.12) land, enforce `max-write-batch-rows` here.
- [x] 7.13 **Backfill + tests.** Updated the `dial_usage_log` backfill to the new catalog shape
  (`status=ACTIVE`, `partition_by`); added/upgraded tests: `ReplacingMergeTree`/`PARTITION BY`/`ALTER`
  render, nullability mapping, create-time materialization + ClickHouse-failure path, `PATCH`
  add/drop/rename + grain protection, identifier/array validation, and `GET` column exposure.
  `./gradlew clean build` passes.

## 8. Column identifying tag

> Adds a single, optional, free-form `tag` (≤64 chars) per column so consumers can identify a column
> among many. Catalog-only metadata — never part of the ClickHouse DDL.

- [x] 8.1 **Persistence.** Add `tag TEXT` to `column_mapping` in `V1__create_table_catalog.sql` (edited
  in place — unreleased scaffolding; a stale local DB must be recreated) and a `String tag` field to
  `ColumnMappingEntity`.
- [x] 8.2 **Service input + write path.** Add `tag` to `ColumnInput`; set it in
  `CatalogSchemaWriter.columnEntity`; preserve it across `renameColumn`; add `retagColumn`
  (metadata-only). Normalize/validate tags in `TableColumnRules` (blank → null, reject > 64 chars, 422).
- [x] 8.3 **DTOs + mapper.** Add `tag` to `CreateTableRequest.ColumnRequest` and `TableDto.ColumnDto`;
  map it in `TableMapper` (`toColumnInput`/`toDto`).
- [x] 8.4 **PATCH retag.** Add a `retag` operation (`{name, tag}`) to `PatchSchemaRequest`/`PatchInput`
  and `TableSchemaService.patchSchema`, applied last (drop → rename → add → retag) against the post-op
  exposed names; map it in `TableMapper`.
- [x] 8.5 **Query schema discovery.** Add `tag` to `QuerySchemaFieldDto`; populate it in
  `CatalogQueryService.addColumn` for source and enrichment columns.
- [x] 8.6 **Demo UI.** Surface the tag in the column editor + detail table (with a retag action) on
  `tables.html` and in the field table on `schema.html`.
- [x] 8.7 **Tests + build.** Extend `TableManagementIntegrationTest` (create with tag → GET/discovery
  reflect it; retag set/clear; rename preserves tag; over-long tag 422) and adjust existing
  `ColumnInput`/`QuerySchemaFieldDto` constructors. `./gradlew clean build` passes.
