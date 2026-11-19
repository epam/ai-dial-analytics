## Why

A system table (`dial_usage_log`, `rate_analytics`, `dial_usage_log_payload`) is created by a migration, so its
creation is never in the audit trail, and `GET /v1/tables/{name}/revision/{revision}` answers `404 revision_not_found`
at every revision of it. Since access entries became editable per role, those tables are the ones whose grants, row
policies and column policies operators edit most. Every such edit is recorded in full, and the activity feed lists it,
yet no client can see what it changed. The 404 exists to keep a partial column list from being served, and a system
table's access entries do not depend on column history at all.

## What Changes

- The revision read of a **system** table returns its definition as recorded at that revision, `roles` included
  (under the existing `TABLE_GRANT` bar), at every revision at or after its first recorded state, instead of 404.
- That response carries **no `columns` member**, absent rather than empty. A system table's columns are never
  changed through the API (schema define, PATCH and delete are refused with 409), so the trail never holds their state.
  Its `ordering_key`, `partition_by`, `ttl` and `grain` are still served as recorded.
- In that response, `permissions.columns_restricted` is `true` whenever a column policy confines the caller. It is
  judged without a column list, so it fails closed.
- A revision before the system table's first recorded state, an unknown revision, and a non-numeric one stay
  `404 revision_not_found`.
- **Unchanged:** a non-system table whose creation predates the trail still answers 404 at every revision. Pipelines
  and saved queries keep their own creation guard.
- Client-visible change for the Admin UI and any other history client: the revision read of a system table can now
  answer 200 without `columns`. It is not marked breaking, because no client could receive a 200 for these tables
  before.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `audit-trail`: requirement "Historical table snapshots". A system table is exempt from the "creation must be in the
  trail" rule, and its snapshot omits `columns` and judges `columns_restricted` without them. The scenario "Snapshot of
  a table older than the trail" is narrowed to non-system tables.
- `table-access-control`: requirement "Hidden columns are absent from every read surface". Its rule that a snapshot's
  `columns_restricted` is judged over the revision's own columns gains the system-table exception, so the two specs
  give one answer.

## Impact

- **API:** `GET /v1/tables/{name}/revision/{revision}` for system tables only (200 instead of 404, no `columns`). Its
  OpenAPI description in `TableController` changes. No new endpoint, config key, migration or catalog schema change.
- **Code:** `AuditSnapshotService.snapshot` (branch on the revision row's `system` flag before the creation guard),
  `TableMapper.toDto(TableSnapshot, …)` (no column list when untracked), and `TableCallerPermissions` (columns
  restricted without a column list).
- **Tests:** new integration coverage for system-table revisions. `AuditTrailAuthIntegrationTest`'s
  `dial_usage_log/revision/1` probe must stop depending on whether revision 1 touched that table.
- **Docs:** a section in `docs/release-0.24-upgrade.md` for history clients.
- **Data:** existing system-table revisions already recorded become readable with no backfill.
