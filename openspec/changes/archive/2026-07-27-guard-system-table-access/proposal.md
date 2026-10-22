# Guard system-table access replacement

## Why

`PUT /v1/tables/{name}/access` currently accepts and persists write/modify role lists for
`system` tables (e.g. the seeded `dial_usage_log`). System tables are materialized out of band
and fed by an external ingest process — no API caller should ever write rows or change schema
on them, so no permission entry for them should exist. Today the entry is never exercisable
(every actual mutation is refused by the system-table guard with `409 table_is_system`, and
permission reporting shows `write=false, modify=false`), but accepting the replace is
misleading: it stores grants that suggest access is possible and that the guard alone stands
between a configuration mistake and corruption of externally-owned data.

## What Changes

- `PUT /v1/tables/{name}/access` on a `system` table is refused with the existing system-table
  error contract: `409` ErrorView with machine code `table_is_system` — the same response every
  other modifying operation on a system table already returns. **BREAKING** for any client that
  relied on the (previously accepted, never effective) replace.
- `GET /v1/tables/{name}/access` on a system table is unchanged (still returns the lists,
  which are empty in practice).
- No data migration: the access-management feature was not yet used to set lists on system
  tables in any environment, so there is no stored state to clean up — the guard alone
  establishes the invariant.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `table-access-control`: the "Per-table access model" and "Access-management API" requirements
  change — access lists no longer apply to `system` tables; replacing a system table's access
  is refused with `409 table_is_system`; system tables carry empty lists (none were ever set).

## Impact

- `TableAccessService.replaceAccess` — add the system-table guard (`requireModifiable()`).
- `openspec/specs/table-access-control/spec.md` — requirement + scenario updates (via delta).
- Tests: unit coverage for the service guard; integration coverage for the 409 contract.
- No new config, no new endpoints, no changes to enforcement/reporting (already treat system
  tables as unwritable/unmodifiable).
