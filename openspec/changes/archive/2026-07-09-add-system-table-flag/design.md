## Context

The `table-management` catalog (`table_definition` in PostgreSQL) is the source of truth for which
ClickHouse tables ADAS exposes. Two of its rows — `dial_usage_log` and `rate_analytics` — are not
user-created: they are materialized by ClickHouse migrations and seeded into the catalog as
`SOURCE`/`ACTIVE`, and the `data-sync` ingest job writes rows into them continuously by talking to
ClickHouse directly with the writer credential (it does **not** go through `/v1/tables`). Every
mutating `/v1/tables` endpoint is `@FullAdminOnly`, but a `FULL_ADMIN` can still drop a column,
delete the table, change its description, or `POST` rows — any of which corrupts the ingest target.

There is no table-level protection today. The nearest precedent is the column-level `sensitive`
flag added in `V3__add_sensitive_columns.sql`: a boolean catalog attribute, defaulting `false`,
seeded on the two source tables, surfaced on the read API, and mirrored through the entity and DTO.
This change follows that pattern one level up, on the table definition.

## Goals / Non-Goals

**Goals:**
- Mark a table definition as system-owned via a `system` boolean on `table_definition`.
- Make the flag server-controlled only: seeded via migration, never accepted or changed by any API
  request (create, update, or schema PATCH).
- Reject every API modification of a `system` table — `PUT /{name}`, `PATCH /{name}/schema`,
  `DELETE /{name}`, `POST /{name}/rows` — with the stable `ErrorView` contract.
- Keep `system` tables fully readable, listable, and queryable; surface the flag on the read API.
- Seed `system = true` for `dial_usage_log` and `rate_analytics`; everything else is `false`.

**Non-Goals:**
- No API to set, clear, or toggle `system` (that is exactly what we are forbidding). Changing it is
  a database/migration operation.
- No per-operation granularity (no "allow description edits but block schema"). A `system` table is
  uniformly not modifiable through the API.
- No change to ingest, the query/discovery path, or the `sensitive`/role model.
- No user-facing "create a system table" flow — new tables are always `system = false`.

## Decisions

### Name the flag `system` (not `read_only`)
The tables are actively written — by the ingest job — so they are not literally read-only; they are
*owned/managed by ADAS*. `system` states that provenance, and "not modifiable via the API" follows
from it. It also avoids confusion with the existing `READ_ONLY_ADMIN` role. Alternative considered:
`read_only` — rejected because it overstates (ingest writes them) and collides conceptually with the
role name.

### Store as a column on `table_definition`, default `FALSE`
`ALTER TABLE table_definition ADD COLUMN system BOOLEAN NOT NULL DEFAULT FALSE` in a new
`V4__add_system_table_flag.sql`, then two `UPDATE`s setting it `TRUE` for the seeded tables. Mirrors
V3 exactly. The `NOT NULL DEFAULT FALSE` means every existing and future row is non-system unless a
migration says otherwise, so the API never has to invent a value. Add a `boolean system` field to
`TableDefinitionEntity` (JPA maps it to the column). Alternative considered: a separate
`system_table` table or an enum status — rejected as heavier than a boolean for a binary property,
and inconsistent with the `sensitive` precedent.

### Enforce with a single guard + a dedicated exception → 409 Conflict
Add `SystemTableException` (or reuse `TableConflictException`) thrown by a small guard invoked at the
top of `TableCatalogService.updateDescription`/`delete`, `TableSchemaService.patchSchema`, and
`TableDataService.write` after the definition is loaded: `if (entity.isSystem()) throw ...`. Map it
in `DefaultExceptionHandler` to **HTTP 409** with a stable `error` code (e.g. `table_is_system`).
409 matches the capability's existing "operation conflicts with the resource's state" usage
(`TableConflictException` already yields 409 for delete-with-enrichments and write-to-non-materialized).
Alternative considered: **403 Forbidden** — rejected because the block is not about the caller's
identity (even a `FULL_ADMIN` is refused); it is a property of the resource, which is a conflict, not
an authorization decision.

### Keep `system` off the request DTOs entirely; response-only on the read DTO
The flag never appears on `CreateTableRequest` or `UpdateTableRequest`, so there is no field for a
client to set and no validation to write — non-editability is structural, not a runtime check. Add
`boolean system` to `TableDto` (and map it in `TableMapper`) so `GET /{name}` and `GET /` show the
protection. This matches how `sensitive` is response-carrying on `TableDto.ColumnDto`. Create always
persists `system = false` (the entity default), independent of any request body.

### Enforce in the service layer, not in the controller/security layer
The guard lives in the services (next to the existing enrichment/materialization guards), not as a
new annotation or URL rule, because the decision depends on catalog state (`entity.isSystem()`) that
only the service has loaded. This also keeps the `GET` endpoints untouched.

## Risks / Trade-offs

- [A future legitimate need to edit a `system` table via the API] → Out of scope by design; the
  escape hatch is a DB migration flipping the flag. If user-created-but-locked tables are ever
  needed, introduce a separate `read_only` concept rather than overloading `system`.
- [Blocking `POST /{name}/rows` on `system` tables removes a manual write path for them] → Intended:
  these tables are fed by ingest (direct ClickHouse), so the API row-write path was never how they
  get data; blocking it prevents accidental/adversarial corruption at no cost to ingestion.
- [Error-code/status choice (409 vs 403) is a small contract decision clients may branch on] →
  Mitigated by picking 409 consistently with the capability's existing conflict semantics and
  pinning it in the spec so it is stable.
- [Seed UPDATEs must match the exact seeded names] → Low risk; `dial_usage_log` and `rate_analytics`
  are fixed catalog keys seeded in V1/V2, referenced by primary key in the V4 `UPDATE`s.

## Migration Plan

1. Ship `V4__add_system_table_flag.sql`: add the column (`NOT NULL DEFAULT FALSE`), then set it
   `TRUE` for `dial_usage_log` and `rate_analytics`. Flyway applies it on startup (additive, no
   backfill of other rows needed).
2. Deploy the entity/DTO/mapper/guard changes together with the migration.
3. Rollback: the column is additive and defaulted; reverting the app code leaves the (now-ignored)
   column in place harmlessly. A down-migration dropping the column is only needed for a full revert.
