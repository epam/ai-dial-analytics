> **Update (post-archive):** the `PUT`-draft + `POST`-materialize split described below was
> subsequently combined into a single atomic `POST /v1/tables/{name}/schema` (define the complete
> schema + materialize in one call; no durable draft, `PATCH` still the live-schema surface). The
> shipped behavior is authoritative in `openspec/specs/table-management/spec.md`.

## Context

`POST /v1/tables` currently records the catalog definition (`table_definition` + `column_mapping` /
`enrichment_grain`) **and** runs `CREATE TABLE` in one call. A source declares columns + `ordering_key`
+ `partition_by` at create; an enrichment declares `source_table` + `grain_key` + `cardinality` and is
a grain shell that grows columns via `PATCH .../schema`. Columns are therefore defined in two places
(create and PATCH), and the physical keys sit in the create/table API even though they are pure
physical-schema attributes.

Sorting a table's attributes by *when they can change* reveals the real seam:

- **Identity / relationship** — `name`, `type`, `source_table`: immutable forever.
- **Catalog metadata** — `description`, `tag_order`, per-column `display_name`/`tag`/`sensitive`: never
  touches ClickHouse, always mutable.
- **Physical schema** — columns **and** the keys (`ordering_key`/`grain_key`/`partition_by`/
  `cardinality`): all arguments to one `CREATE TABLE`; frozen at materialization (keys entirely,
  columns to `ALTER`-only).

Columns and the keys are the same kind of thing (physical schema); the keys only lived in create
because create was the call that ran `CREATE TABLE`. Making materialization an explicit step lets the
physical schema live in one resource, with a clean draft → materialize → live lifecycle.

## Goals / Non-Goals

**Goals:**
- Create is identity-only: `{name, type}` (source) / `{name, type, source_table}` (enrichment) plus
  optional catalog metadata (`description`, `tag_order`). No columns, no keys, no ClickHouse DDL.
- The physical schema (columns + keys) has exactly one home: `/v1/tables/{name}/schema` — `PUT` to
  define/replace the draft (pending), `PATCH` to evolve the live table (active).
- Materialization is an explicit `POST /v1/tables/{name}/materialize`.
- Boundaries are self-describing: the verb + lifecycle state determine the semantics, and wrong-state
  calls return guiding `409`s.
- Preserve every `ACTIVE`-gated invariant (row writes 409 on non-active; query/discovery active-only)
  and the materialize failure contract (best-effort `DROP` + `FAILED` + `ErrorView`).

**Non-Goals:**
- One-shot create (columns/keys inline on `POST`). Deliberately dropped — it is the source of the
  two-homes-for-columns problem. A composite convenience endpoint could be added later without
  muddying the core.
- Mutating identity/relationship (`name`/`type`/`source_table`) or the physical keys after
  materialization (ClickHouse `ORDER BY` immutability forbids the latter).
- Any change to enrichment join semantics, the query DSL, access-control model, or system-table
  protection (system tables still reject all mutation).

## Decisions

### D1 — Create is identity-only

`TableController.create` / `TableCatalogService.create` persist `{name, type, source_table?,
description?, tag_order?}` as `status=PENDING` and issue no ClickHouse DDL. `columns` or any physical
key in the body is rejected (422). For an enrichment, `source_table` is validated at create (must name
an existing `source`) and is immutable thereafter. No column mapping / grain row is written at create.

### D2 — The physical schema lives in `/v1/tables/{name}/schema`; PUT drafts, PATCH alters

- `PUT /v1/tables/{name}/schema` (new) — allowed only while **not materialized** (`PENDING`/`FAILED`).
  Body is the whole physical schema: `columns` + `ordering_key`/`partition_by` (source) or
  `grain_key`/`cardinality` (enrichment). It **replaces** the stored draft (idempotent, repeatable),
  is catalog-only (no ClickHouse DDL), and validates internal consistency (identifier grammar,
  uniqueness, `ordering_key`/`partition_by` ⊆ `columns`, `grain_key` is a `source_table` column,
  array-type rejection, limits). Completeness (a source needs ≥ 1 column and a non-empty
  `ordering_key`) is *not* required here — it is enforced at materialize. On an `ACTIVE` table → 409.
- `PATCH /v1/tables/{name}/schema` — allowed only while **materialized** (`ACTIVE`). Applies
  `add`/`drop`/`rename` as immediate `ALTER`s plus per-column metadata `update`, exactly as today. A
  physical key member is rejected (422 — frozen after materialization). On a `PENDING`/`FAILED`
  table → 409 ("materialize the table first"). This drops the key/tag_order members I would otherwise
  have added to PATCH — they no longer belong here.

Verb + state make the stakes visible: `PUT /schema` is always "edit the free draft", `PATCH /schema`
is always "alter the real table" (a `drop` here is a destructive `ALTER DROP COLUMN`).

### D3 — Materialization is an explicit endpoint

`POST /v1/tables/{name}/materialize` (new, no body): loads the draft, validates completeness +
consistency (source: ≥ 1 column, non-empty `ordering_key` ⊆ columns, `partition_by` column present;
enrichment: `grain_key` present and a `source_table` column, grain row created), builds a
`TableDdlRenderer.CreateSpec` from the stored draft, executes one `CREATE TABLE`, and flips to
`ACTIVE`. Already `ACTIVE` → 409; incomplete/invalid draft → 422 (stays `PENDING`, no DDL); ClickHouse
failure/timeout → best-effort `DROP TABLE IF EXISTS` + `markStatus(FAILED)` + `ErrorView` (a `FAILED`
table can be re-drafted via `PUT .../schema` and re-materialized).

### D4 — `tag_order` becomes table catalog metadata

`tag_order` never touches ClickHouse and is a presentation hint over column tags, so it moves onto the
table resource: accepted by `POST /v1/tables` and `PUT /v1/tables/{name}` alongside `description`, and
removed from the schema endpoints. `UpdateTableRequest` gains `tag_order` (merge-patch: absent/null
leaves, `[]` clears, non-empty replaces). Per-column metadata (`display_name`/`tag`/`sensitive`/
`description`) stays with columns — set via `PUT .../schema` while drafting and via `PATCH .../schema`
`update` while live.

### D5 — Status lifecycle and downstream gates

`PENDING` (durable: defined, draft schema editable, no ClickHouse table) → `POST materialize` →
`ACTIVE` (ClickHouse table exists; columns evolve via `PATCH`, keys frozen), or → `FAILED` (last
materialize failed; re-draftable, retryable). `PENDING` and `FAILED` are the two *un-materialized*
states (draftable via `PUT .../schema`, materializable); `ACTIVE` is terminal for materialization.

**FAILED invariant (safety-critical).** `FAILED` SHALL be produced **only** by a failed
materialization — never from `ACTIVE`. A ClickHouse `ALTER` failure during a live `PATCH .../schema`
SHALL leave the table `ACTIVE` (it surfaces an `ErrorView`, status unchanged) and SHALL NOT transition
it to `FAILED`. Consequently `FAILED` always means "no confirmed ClickHouse table," and because row
writes require `ACTIVE` (`TableDataService.write` 409s otherwise), a `FAILED` table provably holds no
user data — which is what makes re-drafting a `FAILED` table via `PUT .../schema` and re-materializing
it safe (nothing to lose; the retry `CREATE TABLE` follows the materialize best-effort
`DROP TABLE IF EXISTS`). This closes the hazard where a materialized-with-data table could otherwise
fall into a draftable state and be wholesale-replaced. (Pre-existing and unchanged: a live `PATCH`
applies per-op ClickHouse `ALTER`s that are not transactional, so a mid-sequence failure may leave
earlier ops applied — the table stays `ACTIVE` and the caller retries the remainder.)

Consumers already gating on `ACTIVE` are unchanged: `TableDataService.write` 409s on non-active; the
query/discovery path lists only `ACTIVE` tables; `DELETE` uses `DROP TABLE IF EXISTS` (safe for a
never-materialized table). `TableDefinitionEntity` fields (`source_table`/`ordering_key`/
`partition_by`, grain row) are already nullable/optional, so no Flyway migration is needed.

### D6 — Authorization

`POST /v1/tables` (create) and `DELETE` stay `FULL_ADMIN`-only. `PUT /v1/tables/{name}` (description +
tag_order), `PUT /v1/tables/{name}/schema` (draft), `POST /v1/tables/{name}/materialize`, and
`PATCH /v1/tables/{name}/schema` (live) are authorized by `FULL_ADMIN` or a matching `modify` role —
they are all "shape the schema of an existing table" operations, consistent with today's `PATCH`/`PUT`
authorization. Row writes keep the `write` role. System-owned tables reject all of these regardless of
role.

## Risks / Trade-offs

- **[More endpoints / more calls]** A full table is now three calls (create → `PUT .../schema` →
  materialize) instead of one. → Accepted deliberately for clean boundaries; each call has a single
  clear contract, and a convenience composite can be added later.
- **[Create/enrichment behavior break]** Neither kind is `ACTIVE` after create; enrichment create no
  longer materializes a grain shell. → Documented breaking; row-write/query return clear 409s.
- **[Two schema verbs, state-gated]** `PUT` vs `PATCH` on `/schema` are mutually exclusive by state.
  → This is the point (visible stakes); wrong-state calls 409 with a guiding message.
- **[Draft consistency vs completeness]** `PUT .../schema` allows an incomplete draft (e.g. columns
  but no `ordering_key`); the gap only surfaces at materialize (422). → Intentional — drafting should
  be incremental; materialize is the single completeness gate.
- **[Test churn]** Broad — most table-management tests create-and-expect-`ACTIVE`. → Rework to the new
  lifecycle; add draft/materialize/live coverage.

## Migration Plan

Pure behavior/API change; no data migration. Seeded system tables (`dial_usage_log`, `rate_analytics`)
are already `ACTIVE` and materialized out of band; they reject every mutation and are unaffected. No
Flyway migration (relevant columns already nullable, grain already optional). Rollback = revert the
code; `PENDING` rows written by the new flow remain valid `PENDING` definitions under old code (never
materialized).

## Open Questions

- None blocking. Confirmed: create = identity only; physical schema (columns + keys) lives in
  `/v1/tables/{name}/schema` (`PUT` draft / `PATCH` live); materialization is an explicit
  `POST .../materialize`; `tag_order` is table catalog metadata; materialize/draft/live-schema
  authorized by `FULL_ADMIN` or `modify` role.
