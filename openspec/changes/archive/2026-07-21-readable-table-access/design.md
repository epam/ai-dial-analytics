# Design: readable-table-access

## Context

`TableAccessController` gates both `GET` and `PUT /v1/tables/{name}/access` with `@FullAdminOnly`
(method security). At the URL layer, `OidcSecurityConfiguration` already requires
`FULL_ADMIN` or `READ_ONLY_ADMIN` for everything under `/v1/**` except the three per-table
mutating endpoints (which are `authenticated`-only and authorized per table).

## Goals / Non-Goals

**Goals:** let any application-role caller read a table's access lists; keep the replace
admin-only.

**Non-Goals:** no change to the per-table mutation authorization, the `permissions` reporting, or
`none`-mode behavior (method security inert, everything open — unchanged).

## Decisions

### D1: Drop `@FullAdminOnly` from the GET; add no new annotation

With the annotation removed, the `/v1/**` URL rule (`hasAnyAuthority(FULL_ADMIN,
READ_ONLY_ADMIN)`) is the effective gate — exactly the same gate as every other catalog read
(`GET /v1/tables`, `GET /v1/tables/{name}`). A caller with only a provider role keeps getting a
URL-level `403`, preserving the intended asymmetry (write-only callers cannot read the catalog).
Alternative — a `@PreAuthorize("hasAnyAuthority(...)")` on the method — rejected: it would
duplicate the URL rule and drift if roles evolve.

## Risks / Trade-offs

- [Read-only admins see role names] Role names are configuration-level identifiers, not secrets,
  and READ_ONLY_ADMIN already sees the whole catalog including `system` flags and the caller's
  own `permissions` → acceptable by design.

## Open Questions

None.
