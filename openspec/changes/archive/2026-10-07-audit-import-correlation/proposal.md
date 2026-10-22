## Why

A catalog import (`POST /v1/catalog/import`) writes each object it creates as its own audit revisions, so the activity feed
shows its table creates, schema updates, column creates, pipeline creates and compensation deletes as ordinary activities that
cannot be told apart from hand-made ones. Nothing links them to each other or to the import that caused them: the `import_id`
returned in `ImportResult` exists only in the `catalog_import` log line. A reader of the feed — the admin UI included — can
neither tell that an activity was part of an import nor list everything one import did.

## What Changes

- Every activity carries an optional `importId`: present (the `ImportResult` `import_id` of that import) on every activity of a
  revision written while `/import` applies a bundle — creates and compensation deletes alike — and absent on every other
  activity. Presence alone says "this was part of an import"; the value says which one.
- The activity feed (`POST /v1/activities`) accepts `importId` as a filter and sort column (`eq`, `ne`, `in`), so
  `importId eq <import_id>` returns the whole import, and `GET /v1/activities/{id}` returns the field too.
- No parent "Import" activity is added to the feed, and the existing `parentActivityId` links are unchanged: an imported
  `TableColumn` activity still points at its `Table` activity.
- `/import/preview` writes no revision and so tags nothing; existing activities keep no `importId` (no backfill).
- Catalog schema: one Flyway migration adding a nullable, indexed `import_id` to `revinfo` and to `audit_activity`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `audit-trail`: activities gain the `importId` field, and the feed's allow-listed filter/sort columns gain `importId`.
- `catalog-export-import`: the "Best-effort compensation" requirement states that every revision an import writes is tagged
  with its `import_id`, so the import is recoverable from the activity feed, not only from the log.

## Impact

- **API**: additive field `importId` on `AuditActivityDto` (omitted when absent, per the global `NON_NULL` inclusion) and a
  new filter/sort column on `POST /v1/activities`. No breaking change; OpenAPI gains the field.
- **Catalog schema**: `V46` migration on PostgreSQL — `revinfo.import_id`, `audit_activity.import_id` (both `varchar(36)`
  null) and an index on `audit_activity.import_id`.
- **Code**: `service/audit` (`AuditRevisionEntity`, `AuditActivityEntity`, `AuditRevisionListener`, `AuditActivityService`
  allow-list, a new import-scope holder), `web/dto/audit/AuditActivityDto` and its mapper, and `CatalogImportService.applyPlan`
  (opens the scope around the create and compensation steps).
- **Config**: none.
