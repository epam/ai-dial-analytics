# Tasks — modify-table-ttl

## 1. Catalog model & migration

- [x] 1.1 Add Postgres migration `V17__add_table_ttl.sql`: nullable `ttl_column TEXT`,
  `ttl_interval INTEGER`, `ttl_unit TEXT` on `table_definition` + CHECK `ttl_all_or_none`
  (all set or all null), mirroring `partition_by_both_or_neither`.
- [x] 1.2 Add `TtlUnit` enum (`HOUR`, `DAY`, `WEEK`, `MONTH`, `YEAR`) in `service/table/` with
  lowercase `@JsonValue` code, mirroring `PartitionGranularity`.
- [x] 1.3 Add `ttlColumn`/`ttlInterval`/`ttlUnit` fields to `TableDefinitionEntity`
  (`@Enumerated(STRING)` for the unit).

## 2. DDL rendering

- [x] 2.1 Extend `TableDdlRenderer.CreateSpec` with an optional TTL and append
  `TTL <col> + INTERVAL <n> <UNIT>` in `renderCreate` (`toDateTime(<col>)` wrap for `timestamp`
  columns, bare column for `date`), rendering the column's physical `source_name`.
- [x] 2.2 Consolidate the PATCH path into a **single multi-command `ALTER TABLE` statement**:
  replace the per-operation `renderAlterAdd/Drop/Rename` usage with a renderer that emits one
  statement carrying the commands in canonical order `DROP` → `RENAME` → `ADD` →
  `MODIFY TTL`/`REMOVE TTL` (omit the statement entirely when there are no physical commands).
- [x] 2.3 Unit-test the new rendering in `TableDdlRendererTest`: TTL clause in CREATE (date vs
  timestamp wrap, each unit keyword), multi-command ALTER composition and ordering, MODIFY vs
  REMOVE TTL commands, no-op cases.

## 3. Declare TTL at materialization (`POST /v1/tables/{name}/schema`)

- [x] 3.1 Add optional nested `ttl` request member (`{column, interval, unit}`) to
  `DefineSchemaRequest`; flatten into `DefineSchemaInput` via `TableMapper`; add `ttl` to
  `CreateTableRequest` only as a rejected tripwire field (422 in `toCreateInput`, like `columns`).
- [x] 3.2 Add shared TTL validation (used by define + PATCH): column is a declared column of the
  table, non-nullable, type `date`/`timestamp`; `interval >= 1` → `TableValidationException`.
- [x] 3.3 Persist the TTL in `CatalogSchemaWriter.persistSchema` and carry it into the
  `CreateSpec` in `TableSchemaCreationService` so the rendered `CREATE TABLE` includes the clause.
- [x] 3.4 Unit tests: define-with-TTL happy path, invalid TTL (unknown/nullable/non-time column,
  interval 0) rejected 422 leaving the table `PENDING`.

## 4. TTL via `PATCH /v1/tables/{name}/schema`

- [x] 4.1 Add optional `ttl` member to `PatchSchemaRequest` with tri-state semantics: absent/null
  = leave unchanged; full object = set/replace; empty object `{}` = remove; partial object → 422.
  Flatten into `PatchInput` (e.g. a `Change<TtlInput>`-style tri-state) in `TableMapper`.
- [x] 4.2 Implement in `TableSchemaService.patchSchema`: validate the TTL against the post-ops
  column set (just-added/renamed columns referenceable); reject a `drop` of the stored
  TTL-referenced column with 422 regardless of any `ttl` member; on a `rename` of it, update the
  stored TTL column in the same catalog transaction; idempotent `{}` removal when no TTL stored
  (no DDL).
- [x] 4.3 Wire the single consolidated `ALTER` execution: build all commands, execute once via
  `ClickHouseDdlExecutor`, then persist column mapping + TTL (`CatalogSchemaWriter.applyPatch`) in
  one transaction. A failed `ALTER` leaves catalog and table untouched.
- [x] 4.4 Unit tests for the service flow: set/replace/remove, idempotent remove, absent leaves
  unchanged, partial object 422, TTL on just-added column, drop-of-TTL-column 422,
  rename-of-TTL-column catalog follow, PENDING → 409, ALTER failure leaves catalog unchanged.

## 5. Read surface

- [x] 5.1 Add `TableDto.TtlDto` and map the entity TTL in `TableMapper` (omitted when unset,
  `NON_NULL`); verify single-get carries it and mutation responses returning the table DTO do too.

## 6. Integration & finalization

- [x] 6.1 Add a dedicated `TableTtlIntegrationTest`: define schema with TTL → `SHOW CREATE TABLE`
  contains the TTL clause; PATCH sets/replaces/removes the TTL on an ACTIVE table (verify via
  `SHOW CREATE TABLE` and `GET`); combined columns+TTL PATCH lands in one statement; invalid
  command in a combined PATCH leaves the table completely unchanged (all-or-nothing); rename of
  the TTL column keeps `GET` and `SHOW CREATE TABLE` consistent; drop of the TTL column → 422;
  PENDING table PATCH → 409; TTL at create → 422.
- [x] 6.2 Auth/system coverage: system table PATCH with `ttl` → 409 `table_is_system` (existing
  behavior, assert explicitly); a `modify` grant authorizes a non-admin TTL PATCH
  (`TableAccessIntegrationTest` pattern).
- [x] 6.3 Update `openspec/project.md` table-management summary: TTL surface + single-statement
  PATCH semantics; no `docs/configuration.md` change (no new env vars).
- [x] 6.4 `./gradlew clean build` passes (compile + checkstyle + tests).
