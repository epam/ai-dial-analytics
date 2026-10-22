## 1. Migration

- [x] 1.1 Add `src/main/resources/db/migration/postgres/V4__add_system_table_flag.sql`:
      `ALTER TABLE table_definition ADD COLUMN system BOOLEAN NOT NULL DEFAULT FALSE`, then
      `UPDATE table_definition SET system = TRUE WHERE name IN ('dial_usage_log', 'rate_analytics')`,
      with a header comment mirroring V3 (catalog-only, server-controlled, seeded on the two
      ingest-owned tables).

## 2. Persistence & mapping

- [x] 2.1 Add a `boolean system` field to `TableDefinitionEntity` mapped to the `system` column
      (getter `isSystem()`), defaulting to `false`.
- [x] 2.2 Add a `boolean system` field to `TableDto`; map it in `TableMapper` (entity → DTO). Do NOT
      add `system` to `CreateTableRequest` or `UpdateTableRequest`, and do NOT map any request field
      to it — create/update must never set it.
- [x] 2.3 Confirm `TableCatalogService.create` persists `system=false` for new tables (entity default;
      no request path can set it).

## 3. Enforcement

- [x] 3.1 Add a `SystemTableException` under `exception/` (or reuse `TableConflictException`) meaning
      "this table is system-owned and cannot be modified via the API".
- [x] 3.2 Add a guard (e.g. `requireNotSystem(entity)`) and invoke it after loading the definition in
      `TableCatalogService.updateDescription`, `TableCatalogService.delete`,
      `TableSchemaService.patchSchema`, and `TableDataService.write`, throwing before any Postgres
      write or ClickHouse DDL/insert.
- [x] 3.3 Add an `@ExceptionHandler` in `DefaultExceptionHandler` mapping the exception to HTTP 409
      with a stable `error` code (e.g. `table_is_system`) via the `ErrorView` contract; leak no
      internals. (If reusing `TableConflictException`, ensure the message/code is distinguishable.)

## 4. Docs & OpenAPI

- [x] 4.1 Document the `system` flag on `TableDto` with `@Schema` (read-only; owned by ADAS; blocks
      modification).
- [x] 4.2 Note the `system` flag and the two protected tables (`dial_usage_log`, `rate_analytics`)
      where table management is described (`README.md` prose and/or `docs/configuration.md` /
      `openspec/project.md` as appropriate); keep property descriptions in their canonical location.

## 5. Tests

- [x] 5.1 Migration/catalog: after startup, `dial_usage_log` and `rate_analytics` are `system=true`
      and every other definition is `system=false`.
- [x] 5.2 Read surface: `GET /v1/tables/{name}` and `GET /v1/tables` return the `system` flag
      (true for seeded tables, false for a user-created one).
- [x] 5.3 Rejection: `PUT`, `PATCH /schema`, `DELETE`, and `POST /rows` on a system table each return
      409 with the stable error code, and assert no state change / no ClickHouse DDL or insert was
      issued.
- [x] 5.4 Create: `POST /v1/tables` (including a body attempting `system=true`) persists
      `system=false`; a non-system table remains fully modifiable and queryable.
- [x] 5.5 Read paths unaffected: a query under `/v1/queries` against a system table still succeeds.

## 6. Verify

- [x] 6.1 `./gradlew clean build` passes (compile + checkstyle + tests).
- [x] 6.2 Confirm the `table-management` spec matches shipped behavior; run
      `openspec validate add-system-table-flag`.
