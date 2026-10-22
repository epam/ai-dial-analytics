# Tasks: guard-system-table-access

## 1. Service guard

- [x] 1.1 In `TableAccessService.replaceAccess`, call `definition.requireModifiable()` after
  loading the entity (before touching the lists), and update the class javadoc to state the
  system-table refusal (409).

## 2. Data cleanup

- [x] 2.1 ~~Add Flyway migration `V12__clear_system_table_access.sql`~~ Dropped before merge:
  no environment ever persisted access lists on system tables, so there is nothing to clean up.

## 3. Tests

- [x] 3.1 Unit test: `replaceAccess` on a system table throws `SystemTableException` and does
  not persist; on a non-system table still replaces (existing behavior untouched).
- [x] 3.2 Integration test: `PUT /v1/tables/{name}/access` on a system table returns `409`
  ErrorView with machine code `table_is_system`; `GET` on the same table still returns `200`
  with empty lists.

## 4. Spec & docs

- [x] 4.1 Verify the delta spec `specs/table-access-control/spec.md` matches shipped behavior
  (statuses, machine code, GET unchanged) and `openspec validate guard-system-table-access`
  passes.

## 5. Verification

- [x] 5.1 `./gradlew clean build` passes (compile, checkstyle, tests).
