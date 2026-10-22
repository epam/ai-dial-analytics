## 1. Create becomes identity-only

- [x] 1.1 Slim `CreateTableRequest` to identity + catalog metadata: `{name, type, source_table?,
      description?, tag_order?}`; remove `columns`, `ordering_key`, `partition_by`, `grain_key`,
      `cardinality`. Update `@Schema` docs.
- [x] 1.2 Slim `CreateTableInput` and `TableMapper.toCreateInput` accordingly.
- [x] 1.3 In `TableCatalogService.create`, persist identity only as `PENDING` (no columns/grain, no
      ClickHouse DDL, no ACTIVE/FAILED handling); keep enrichment `source_table` validated + duplicate
      name (409). Reject any stray physical-schema field (422) if it can reach the service.
- [x] 1.4 Update `TableController.create` `@Operation` docs (identity-only, returns PENDING).

## 2. Draft schema resource — PUT /v1/tables/{name}/schema

- [x] 2.1 Add `DraftSchemaRequest` DTO: `{columns[], ordering_key?, partition_by?}` (source) /
      `{columns[], grain_key?, cardinality?}` (enrichment); reuse `ColumnRequest`/`PartitionByRequest`.
- [x] 2.2 Add `PUT /{name}/schema` to `TableController` (`@PreAuthorize modify`); map to a service call.
- [x] 2.3 Implement a draft-schema service method (in `TableSchemaService` or a new
      `TableDraftSchemaService`): allowed only when not `ACTIVE` (else 409); replace the stored
      `column_mapping` + set `ordering_key`/`partition_by` (source) / grain row (enrichment); catalog
      only (no DDL); validate consistency via `TableColumnRules` (identifier grammar, uniqueness,
      array rejection, `ordering_key`/`partition_by` ⊆ columns, `grain_key` a source column, column
      cap); do NOT require completeness. Reject on a `system` table (409).

## 3. Materialize endpoint — POST /v1/tables/{name}/materialize

- [x] 3.1 Add `POST /{name}/materialize` to `TableController` (`@PreAuthorize modify`, `@ResponseStatus`
      200) returning the materialized `TableDto`.
- [x] 3.2 Implement a materialize service method: allowed only when not `ACTIVE` (else 409); validate
      draft completeness/consistency (source: ≥1 column + non-empty `ordering_key` ⊆ columns +
      `partition_by` col present; enrichment: `grain_key` resolved + a source column, grain row created)
      → 422 if invalid (stays PENDING, no DDL); build `TableDdlRenderer.CreateSpec` from the stored
      draft, execute one `CREATE TABLE`, `markStatus(ACTIVE)`; on failure best-effort `DROP TABLE IF
      EXISTS` + `markStatus(FAILED)` + rethrow. Reject on a `system` table (409). Factor the
      create-spec-from-catalog + failure helper out of the old create path.
- [x] 3.3 Add `TableDefinitionEntity.isMaterialized()` (`status == ACTIVE`) for the state gates.

## 4. PATCH /v1/tables/{name}/schema becomes live-only

- [x] 4.1 In `TableSchemaService.patchSchema`, require `status == ACTIVE` (else 409, "materialize
      first"); remove any materialization branch.
- [x] 4.2 Keep the physical-key members (`ordering_key`/`partition_by`/`grain_key`/`cardinality`) and
      `tag_order` on `PatchSchemaRequest` as documented not-allowed fields, and reject any of them with a
      clear 422 in `TableMapper.toPatchInput` (mirrors the create-side rejection). `PatchInput` carries
      only add/drop/rename/update.
- [x] 4.3 Keep the existing ALTER path (drop/rename/add/update); update `TableController.patchSchema`
      `@Operation` docs (live-only, ALTERs, keys frozen).
- [x] 4.4 Enforce the FAILED invariant: a ClickHouse `ALTER` failure in `patchSchema` MUST leave the
      table `ACTIVE` (surface the `ErrorView`, do not `markStatus(FAILED)`) — confirm the code never
      transitions ACTIVE→FAILED anywhere. `FAILED` is produced only by `materialize`.

## 5. tag_order moves to table catalog metadata

- [x] 5.1 Add `tag_order` to `UpdateTableRequest` (merge-patch: absent/null leave, `[]` clear,
      non-empty replace); keep it on `CreateTableRequest`.
- [x] 5.2 In `TableCatalogService.updateDescription` (rename to `updateMetadata`), apply description +
      tag_order via `CatalogSchemaWriter.setTagOrder`/description.
- [x] 5.3 Ensure `PATCH .../schema` no longer touches `tag_order`; `GET` still returns it and sorts
      columns by it (unchanged read behavior).

## 6. Verify downstream ACTIVE gates are unchanged

- [x] 6.1 `TableDataService.write` still 409s on non-`ACTIVE` — verify, add/adjust a test for a
      never-materialized `PENDING` table.
- [x] 6.2 Query/discovery lists/queries only `ACTIVE` tables — verify a `PENDING` table is neither
      listed as queryable nor queryable.
- [x] 6.3 `DELETE /v1/tables/{name}` (DROP TABLE IF EXISTS) works for a never-materialized `PENDING`
      table — verify/add a test.

## 7. Tests

- [x] 7.1 Rework existing table-management tests to the new lifecycle: create (identity) → `PUT .../schema`
      → `materialize` → `PATCH .../schema`; remove assumptions that create issues DDL / returns ACTIVE.
- [x] 7.2 Create: `{name,type}` source and `{name,type,source_table}` enrichment → 201 PENDING, no DDL;
      a body with `columns`/keys → 422.
- [x] 7.3 `PUT .../schema`: stores a source draft (columns+ordering_key) and an enrichment draft
      (grain_key); replaces on re-PUT; 409 on an ACTIVE table; 422 on an inconsistent draft.
- [x] 7.4 `materialize`: source → `MergeTree` CREATE, ACTIVE; source with partition_by → matching
      `PARTITION BY`; enrichment → `ReplacingMergeTree` CREATE, ACTIVE; incomplete draft → 422 stays
      PENDING; already ACTIVE → 409; CREATE failure → DROP + FAILED + ErrorView, re-draft + retry works.
- [x] 7.5 `PATCH .../schema`: ALTER add/drop/rename/update on ACTIVE; 409 on PENDING/FAILED; key member
      → 422.
- [x] 7.5b `PATCH .../schema` whose ClickHouse `ALTER` fails on an ACTIVE table → ErrorView, status
      stays ACTIVE (never FAILED), and `PUT .../schema` / `materialize` still 409 on it afterwards.
- [x] 7.6 `PUT /v1/tables/{name}` sets/clears `tag_order`; system table rejects PUT/PUT-schema/
      materialize/PATCH/rows/delete with 409; `modify` role authorizes PUT-schema + materialize.

## 8. Specs & docs

- [x] 8.1 Keep the delta spec in `openspec/changes/defer-table-materialization/specs/table-management/`
      in sync with the shipped behavior.
- [x] 8.2 Update `docs/` table-management wording (create no longer materializes; draft/materialize/live
      lifecycle; new endpoints).
- [x] 8.3 Update `openspec/project.md` table-management summary (create is identity-only; explicit
      materialize; physical schema in `/schema`).
- [x] 8.4 `./gradlew clean build` passes (compile + checkstyle + tests).
