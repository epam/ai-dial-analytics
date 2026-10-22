## 1. Schema

- [x] 1.1 Confirm `V46` is still the next free Flyway version (this branch and the default branch), then add
  `V46__audit_import_id.sql`: nullable `varchar(36)` `import_id` on `revinfo` and on `audit_activity`, plus
  `audit_activity_import_idx` on `audit_activity (import_id)`
- [x] 1.2 Map `importId` (`UUID`, `@JdbcTypeCode(SqlTypes.VARCHAR)`, column `import_id`) on `AuditRevisionEntity` and
  `AuditActivityEntity`, with Javadoc stating what presence means

## 2. Import scope and listener

- [x] 2.1 Add `AuditImportScope` in `service/audit`: `ThreadLocal`-backed `open(UUID)` returning an `AutoCloseable` that
  clears it, `current()` returning `Optional<UUID>`; `open` throws `IllegalStateException` when a scope is already open.
  Javadoc names the same-thread-commit invariant it relies on
- [x] 2.2 `AuditRevisionListener.newRevision` copies `current()` onto the revision (inside the existing never-break-the-caller
  guard); `record()` copies the revision's `importId` onto each new activity
- [x] 2.3 Unit tests in `AuditRevisionListenerTest`: a revision under an open scope tags the revision and every activity; no
  scope leaves both null; closing the scope clears it; nested `open` is refused

## 3. Import wiring

- [x] 3.1 `CatalogImportService.applyPlan`: generate the id as a `UUID`, open `AuditImportScope` in try-with-resources around
  the create loops and `compensate`, and keep the `ImportResult` / `catalog_import` value identical (`toString()`)
- [x] 3.2 Confirm preview and `CatalogImportValidator` open no scope (no code change expected)

## 4. Feed

- [x] 4.1 Add `"importId", FilterableColumn.uuid("importId")` to `AuditActivityService.PAGE_MAPPER`
- [x] 4.2 Add a trailing `UUID importId` (with `@Schema` and `@param` doc) to `AuditActivityDto` and map it in the audit mapper

## 5. Integration tests

- [x] 5.1 `CatalogTransferIntegrationTest`: a completed import — feed filtered `importId eq <import_id>` returns exactly the
  table create, schema `Table` update, `TableColumn` creates and pipeline create, each tagged; `TableColumn` activities keep
  their `Table` parent
- [x] 5.2 `CatalogTransferIntegrationTest`: a compensated import (second table fails) — the rolled-back table's `Delete`
  carries the same `importId` as its `Create`
- [x] 5.3 A table created through `POST /v1/tables` carries no `importId`; `GET /v1/activities/{id}` returns the field for an
  imported activity and omits it otherwise
- [x] 5.4 `AuditActivityFeedIntegrationTest`: `importId` `ne` returns untagged activities, `in` works, `lt` and `co` are
  rejected with `invalid_filter_value`, a non-UUID value is rejected with `invalid_filter_value`

## 6. Specs and verification

- [x] 6.1 Re-check the delta specs against the shipped behavior; run `openspec validate audit-import-correlation --strict`
- [x] 6.2 No config property is added, so `docs/configuration.md` is unchanged — confirm
- [x] 6.3 `./gradlew clean build` passes (Docker env per local setup)
