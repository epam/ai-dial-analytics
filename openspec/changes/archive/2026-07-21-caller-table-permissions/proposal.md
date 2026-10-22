# Proposal: caller-table-permissions

## Why

The per-table access lists (`write`/`modify` role names) are readable only by `FULL_ADMIN` via
`GET /v1/tables/{name}/access`, so a frontend acting for a regular user cannot know whether the
caller may write rows or modify a table — it would have to re-implement the server's role-matching
logic (and could not even fetch the lists). The server should tell each caller what it can do, so
UIs can enable/disable actions without duplicating authorization logic.

## What Changes

- `GET /v1/tables` (list) and `GET /v1/tables/{name}` (single get) responses gain a
  caller-effective `permissions` object: `{"write": <bool>, "modify": <bool>}`, computed
  server-side for the authenticated caller. Since create/update/patch responses reuse the same
  table DTO, they carry the field too.
- Effective-permission semantics mirror the enforcement already applied by the per-table mutating
  endpoints: `FULL_ADMIN` → both `true`; otherwise a flag is `true` iff one of the caller's raw
  provider roles appears in the table's corresponding access list. A `system` table reports both
  flags `false` for every caller (all modifying operations are refused for system tables). In
  `none` security mode both flags are `true` (nothing is enforced).
- The admin-only access-management API (`/v1/tables/{name}/access`) is unchanged.
- No new endpoints, no persistence changes, no new configuration.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `table-access-control`: new requirement — the system reports the caller's effective per-table
  permissions (write/modify flags) on the table read surfaces, consistent with the enforcement
  rules (FULL_ADMIN bypass, provider-role matching, system-table protection, `none`-mode inertness).
- `table-management`: the table list/get response shape gains the `permissions` object.

## Impact

- `web/dto/table/TableDto.java` — new nested `permissions` field.
- `web/mapper/table/TableMapper.java` — populates the field from the already-loaded definition
  (no extra catalog queries for the list).
- `service/table/grant/` — a caller-permissions resolver sharing the role-matching logic with
  `TableAccessEvaluator` so enforcement and reporting cannot drift.
- Tests: mapper/controller coverage plus integration coverage in `TableAccessIntegrationTest`.
- No API breaking changes (additive field), no DB migrations, no config changes
  (`docs/configuration.md` untouched).
