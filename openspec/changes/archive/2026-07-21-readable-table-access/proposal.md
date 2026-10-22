# Proposal: readable-table-access

## Why

A table's access role lists (`GET /v1/tables/{name}/access`) are `FULL_ADMIN`-only, so a
`READ_ONLY_ADMIN` — whose whole purpose is inspecting the system — cannot see which roles may
write to or modify a table. Reading the lists mutates nothing; only replacing them needs the
admin gate.

## What Changes

- `GET /v1/tables/{name}/access` becomes readable by any application role (`FULL_ADMIN` or
  `READ_ONLY_ADMIN`) instead of `FULL_ADMIN`-only.
- `PUT /v1/tables/{name}/access` (replace) stays `FULL_ADMIN`-only.
- A caller with no application role (e.g. one authorized only by a per-table provider role) still
  gets `403` on the read, as on every other catalog read.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `table-access-control`: the "Access-management API" requirement changes — the read is
  application-role-gated rather than admin-gated; the replace keeps requiring `FULL_ADMIN`.

## Impact

- `web/controller/table/TableAccessController.java` — drop `@FullAdminOnly` from `get` (the
  `/v1/**` URL rule keeps it application-role-gated); javadoc updates here and in
  `TableAccessService`.
- Tests: `TableAccessIntegrationTest` — read-only caller can `GET` but not `PUT`; roleless caller
  still `403` on `GET`.
- No DTO, persistence, or config changes.
