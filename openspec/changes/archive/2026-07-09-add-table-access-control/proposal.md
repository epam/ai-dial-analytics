## Why

Table mutations are gated by a single global role: any `FULL_ADMIN` can write to and reshape
*every* table, and no one else can touch any table. There is no way to let a specific team or
project populate and evolve just the tables it owns without handing over blanket admin rights.
This change adds fine-grained, per-table access control so an admin can delegate write and
schema-modify rights to a named subject (a project key, or a claim-based rule) on a per-table basis.

## What Changes

- Introduce **per-table grants**: a grant is `(subject, permission, table)`, persisted in the
  Postgres catalog alongside table definitions.
  - **Subjects**: `PROJECT` (matches a project-key caller by its `project`) or `CLAIM_RULE`
    (an inline `{claim, operator, value}` predicate over the caller's identity claims, with
    operators `EQUALS`, `CONTAINS`, `STARTS_WITH`).
  - **Permissions**: `WRITE` (row inserts) and `MODIFY` (schema PATCH + description PUT). The two
    are independent — `MODIFY` does not imply `WRITE`.
- Add an admin-only grant-management API under `/v1/tables/{name}/grants` (list / create / revoke),
  requiring `FULL_ADMIN`.
- **BREAKING** (authorization behavior): the per-table mutating endpoints stop requiring
  `FULL_ADMIN` outright and instead accept `FULL_ADMIN` **or** a matching grant:
  - `POST /v1/tables/{name}/rows` → `WRITE` grant or `FULL_ADMIN`
  - `PATCH /v1/tables/{name}/schema` → `MODIFY` grant or `FULL_ADMIN`
  - `PUT /v1/tables/{name}` → `MODIFY` grant or `FULL_ADMIN`
- Catalog-level and destructive operations stay `FULL_ADMIN`-only: `POST /v1/tables` (create),
  `DELETE /v1/tables/{name}` (drop), and all grant-management endpoints.
- Retain the caller's identity (project flag / raw claims) on the security context so grant
  evaluation can run per request; today only the principal name and mapped roles survive.
- Grants may target **any** catalog table (including seeded tables such as `dial_usage_log`); there
  is no system/custom distinction. Read endpoints are unchanged (any authenticated caller).

## Capabilities

### New Capabilities
- `table-access-control`: the per-table grant model (subjects, permissions), the admin grant-management
  API, and the request-time evaluation semantics that gate the per-table mutating endpoints.

### Modified Capabilities
- `authentication`: the "Mutating endpoints require FULL_ADMIN" requirement changes — the per-table
  `rows`/`schema`/description endpoints now also accept a matching grant, while create, delete, and
  grant management remain `FULL_ADMIN`-only; the caller's project/claims identity is retained on the
  security context for grant evaluation.
- `table-management`: the row-write, schema-PATCH, and update endpoints gain grant-based
  authorization as an alternative to `FULL_ADMIN`; deleting a table cascades its grants.

## Impact

- **New code**: `table_grants` migration + repository, grant model/service, grant evaluation bean
  (`@PreAuthorize("@tableAccess.canWrite(#name)")` / `canModify(#name)`), grant DTOs/controller
  sub-resource, and a caller-identity abstraction populated for API-key and Bearer/opaque callers.
- **Modified code**: `TableController` (swap `@FullAdminOnly` on rows/schema/put), the API-key
  authentication token/filter and the JWT/opaque converters (carry project flag + claims), and the
  security wiring.
- **Data**: new Postgres table `table_grants` (FK to the table catalog, `ON DELETE CASCADE`).
- **Docs/spec**: new `table-access-control` spec; deltas to `authentication` and `table-management`;
  `docs/configuration.md` if any new tunables are added.
- No change to `none` security mode — per-table checks stay inert exactly as `@FullAdminOnly` is today.
