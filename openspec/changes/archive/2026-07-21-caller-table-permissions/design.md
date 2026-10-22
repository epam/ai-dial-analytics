# Design: caller-table-permissions

## Context

Per-table access control stores `write`/`modify` provider-role lists on each table definition
(`TableAccess`, JSONB `access` column) and enforces them via
`@PreAuthorize("@tableAccess.canWrite(#name)")` / `canModify` on the mutating endpoints
(`TableAccessEvaluator`). The lists themselves are only readable by `FULL_ADMIN`
(`GET /v1/tables/{name}/access`), so a frontend acting for a regular caller cannot determine
whether to offer "write rows" / "edit schema" actions without duplicating the matching logic —
which it cannot even do, lacking access to the lists.

Adjacent facts that shape the design:

- `SecurityClaimsExtractor` exposes the caller's application roles (`FULL_ADMIN`, …) and raw
  provider roles from the security context; both are empty in `none` mode.
- `system` tables refuse **every** modifying operation for **every** caller
  (`TableDefinitionEntity.requireModifiable()`), even `FULL_ADMIN`.
- In `none` security mode method security is inert — all mutations are open. `SensitiveColumnAccess`
  already models mode-awareness by injecting `${security.mode:none}`.
- `TableMapper` builds both the list item (from `TableSummary`) and the single-get/mutation response
  (from `TableView`); both aggregates carry the `TableDefinitionEntity`, which includes `access`.

## Goals / Non-Goals

**Goals:**

- Every table read surface (`GET /v1/tables`, `GET /v1/tables/{name}`) tells the caller what it may
  do with that table: `permissions: {write, modify}`.
- Reported flags exactly mirror what enforcement would decide — one shared implementation, so the
  two cannot drift.
- No extra catalog queries: list permissions are computed from the already-loaded definitions.

**Non-Goals:**

- No change to the enforcement path or the admin access-management API.
- No reporting of `create`/`delete` ability (they are static: `FULL_ADMIN`-only) and no reporting
  for query/read ability (governed by application roles, not per-table lists).
- No new endpoint (e.g. `GET /v1/tables/{name}/permissions`); the flags ride on existing responses.

## Decisions

### D1: Surface as a `permissions` object on the table DTO (not a separate endpoint)

The frontend's need is per-table flags next to the table metadata it already fetches. Embedding
`{"write": bool, "modify": bool}` in `TableDto` (list items and single get) avoids N+1 calls and
keeps the admin-only `/access` API unchanged. Mutation responses (create/update/patch) reuse
`TableDto`, so they carry the field too — harmless and consistent (create returns
`write=true, modify=true` for the admin who just created it). Alternative — a dedicated
`GET /v1/tables/{name}/permissions` endpoint — rejected: forces a call per table for list views,
which is exactly the duplication/awkwardness the change is meant to remove.

### D2: One resolver component shared with enforcement

New `TableCallerPermissions` component in `service/table/grant` computes
`EffectivePermissions resolve(TableDefinitionEntity)`:

1. `system` table → `write=false, modify=false` (nothing can modify it, not even `FULL_ADMIN`);
2. `security.mode=none` (injected `${security.mode:none}`) → both `true` (nothing is enforced);
3. caller has `FULL_ADMIN` application role → both `true`;
4. otherwise each flag is `true` iff a caller provider role appears in the table's corresponding
   access list (null-safe, same matching as `TableAccessEvaluator.isAllowed`).

`TableAccessEvaluator` delegates its role-matching to the same shared code (a package-private
helper or a method on the resolver taking `TableAccess` + `Permission`), so enforcement and
reporting share one matching implementation. The evaluator's observable behavior is unchanged —
in particular it still returns `false` for a missing table and does **not** grow a system-table
check (the service layer's `requireModifiable()` already produces the correct
`SystemTableException` error there, and evaluator runs only under method security).

Note the deliberate asymmetry: enforcement lets a system-table request through the `@PreAuthorize`
gate (admin) and rejects it in the service with the specific system-table error; reporting says
`false` up front because the operation can never succeed. The flags answer "will this action
work?", not "which layer would reject it?".

### D3: Mapper populates the field; resolver injected into `TableMapper`

`TableMapper` is hand-written and Spring-managed; it gains the resolver as a constructor
dependency and computes `permissions` in both `toDto(TableView)` and the list-item mapping from
the entity already in hand. List cost is O(tables) in-memory role-set lookups — no extra queries.
Alternative — enriching in the controller — rejected: three call sites (`list`, `get`, mutations)
versus one mapping point.

### D4: Wire shape `permissions: {"write": bool, "modify": bool}`

Field names match the `Permission` enum wire codes (`write`, `modify`) and the existing
`AccessDto` field names. Always present on list and single-get responses (both flags always
booleans, never null), so clients don't need absent-field handling.

## Risks / Trade-offs

- [Flags are point-in-time] An admin may change access lists between the read and the action; the
  action's own enforcement still applies → acceptable, standard for permission hints; UI failure
  path is the existing 403.
- [`none` mode reports all-true] Technically "anything goes" is accurate there; local UIs show
  all actions enabled → matches actual behavior, documented in the spec delta.
- [DTO grows on mutation responses too] Slight response size increase; additive and non-breaking.
- [Resolver consults security context outside method-security] Fine: `SecurityClaimsExtractor`
  reads the thread's `SecurityContextHolder` directly and is already used this way by
  `SensitiveColumnAccess` on the query path.

## Open Questions

None.
