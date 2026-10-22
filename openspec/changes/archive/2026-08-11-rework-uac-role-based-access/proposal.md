# Rework per-table access control to a role-based model

## Why

The first cut of per-table access control (`add-table-access-control`) modelled grants as a separate
`table_grant` table with two subject kinds (`PROJECT`, `CLAIM_RULE`). In practice the intended
delegation is by **role**: "any caller carrying provider role X may write/modify this table". The
subject model was more machinery than needed, could not target an API key by its role (project keys
carry no claims and match only `PROJECT`), and kept access policy in a table separate from the
catalog row it governs. This change narrows the model to roles and co-locates it on the catalog row.

## What Changes

- **Storage** — replace the `table_grant` table with a JSONB `access` column on `table_definition`:
  `{"write": [role, ...], "modify": [role, ...]}`. `write` roles may insert rows; `modify` roles may
  change schema/description. The two are independent (WRITE does not imply MODIFY).
- **Subject model** — **BREAKING**: remove `PROJECT` and `CLAIM_RULE` subjects entirely. Access is
  keyed only by raw provider role name (the same names used as `roles-mapping` keys), matched against
  the caller's provider roles. A `FULL_ADMIN` caller bypasses the lists.
- **Raw-role retention** — the three auth paths (Bearer JWT, opaque, API key) now retain the caller's
  raw provider roles as prefixed authorities in addition to the mapped application roles, and a valid
  token that maps to no application role is now **authenticated** (previously rejected) so it can be
  authorized per-table.
- **BREAKING** (authorization gate): the three per-table mutating endpoints
  (`POST /v1/tables/{name}/rows`, `PATCH /v1/tables/{name}/schema`, `PUT /v1/tables/{name}`) require
  only authentication at the URL layer and are authorized by the table's `access` roles — so a caller
  with **no application role** can be authorized for a specific table by a matching role. Table create,
  delete, reads, and access management stay `FULL_ADMIN`-gated.
- **Access-management API** — replace `/v1/tables/{name}/grants` (list/create/revoke by id) with
  `/v1/tables/{name}/access` (`GET`, `PUT` full-replace), `FULL_ADMIN`-only.

## Impact

- Affected specs: `table-access-control` (reworked), `authentication` (raw-role retention;
  no-app-role tokens authenticated), `table-management` (per-table mutations reachable by role).
- Affected code: `TableDefinitionEntity` (+`TableAccess`), `TableAccessEvaluator`,
  `TableAccessController`/`TableAccessService`, the three auth converters/filter,
  `SecurityClaimsExtractor`, `OidcSecurityConfiguration`; removal of the `table_grant` machinery.
- Migration `V5` is replaced (unreleased) — no data migration. Concurrent `access` edits are
  last-writer-wins (optimistic locking is deliberately out of scope, tracked separately).
