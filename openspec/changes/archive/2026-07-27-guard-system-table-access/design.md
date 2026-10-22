# Design: guard-system-table-access

## Context

`TableDefinitionEntity.requireModifiable()` is the single system-table guard: every modifying
`/v1/tables` operation (update, schema create/change, row write, delete) calls it and gets a
`409` ErrorView with machine code `table_is_system` for `system` tables. The one modifying
operation that does NOT call it is `TableAccessService.replaceAccess` behind
`PUT /v1/tables/{name}/access`, so an admin can persist write/modify role lists on a system
table. The lists are never effective — `TableAccessEvaluator` is only consulted for endpoints
whose services then call `requireModifiable()`, and `TableCallerPermissions.resolve` reports
`(false, false)` for system tables — but the stored entry contradicts the invariant that system
tables (fed by the external data-sync ingest) have no API-grantable permissions.

## Goals / Non-Goals

**Goals:**
- Refuse `PUT /v1/tables/{name}/access` for `system` tables with the existing
  `409 table_is_system` contract.
- Clear any access lists already persisted on system-table definitions.
- Keep the spec (`table-access-control`) in sync.

**Non-Goals:**
- Changing `GET /v1/tables/{name}/access` (reads stay open; system tables report empty lists).
- Changing enforcement (`TableAccessEvaluator`) or reporting (`TableCallerPermissions`) — both
  already treat system tables as unwritable/unmodifiable.
- Adding a defense-in-depth system check inside `TableAccessEvaluator`. The evaluator's answer
  is irrelevant for system tables because every guarded endpoint's service refuses them first;
  duplicating the check would blur the single-guard design.

## Decisions

1. **Guard in `TableAccessService.replaceAccess` via `requireModifiable()`** — the service
   already loads the `TableDefinitionEntity`; calling the entity's existing guard reuses the
   single source of the rule and the established `409 table_is_system` ErrorView mapping in
   `DefaultExceptionHandler`. Alternative — a controller-level check — rejected: authorization
   and existence checks for this surface live in the service layer (see class javadoc), and the
   controller has no entity in hand.
2. **`409 table_is_system`, not `422 validation_error`** — the refusal is about the target's
   state (system-owned), not the request body; every other system-table refusal already uses
   this code, and clients should see one stable machine code for "this table is system-owned".
3. **No cleanup migration** — a `V12` migration clearing `access` on `system = true` rows was
   drafted, then dropped before merge: the access-management feature had not yet been used to
   set lists on system tables in any environment, so there is no stored state to clean up. The
   service guard alone establishes and maintains the invariant.

## Risks / Trade-offs

- [Breaking change for clients that PUT access on system tables] → Such a PUT never had any
  effect; the new `409` uses the same contract those clients already receive from every other
  system-table mutation. Called out as **BREAKING** in the proposal.
- [Pre-existing system-table lists would survive without a cleanup migration] → None exist:
  the feature was never used against system tables in any environment, and the guard prevents
  any from appearing going forward.

## Migration Plan

No database migration; deploy is a plain service update.
