## Why

External clients reach ADAS through SQL — most commonly `pandas.read_sql("SELECT * FROM dial_usage_log", adbc_conn)`
over Arrow Flight SQL, or `POST /v1/queries/execute-sql`. `SELECT *` is the natural first query in
every notebook, but on a usage-log-shaped table it drags the raw payload columns (`request_body`,
`response_body` — full LLM request/response JSON, often tens of KB per row) along with the 36 small
columns the analyst actually wanted. A 1000-row page that should be a few hundred KB becomes tens of
MB: slow ClickHouse reads, slow Arrow encoding, and a first-query experience that looks like the
service is broken. The client has no way to know which columns are the expensive ones, and no
transport-level way to opt out — the pandas/ADBC connector sends nothing but SQL text.

The same asymmetry exists on the DSL surface: a row-mode query with no `select` expands to every
source column, payloads included.

## What Changes

- Add a per-column catalog metadata flag **`heavy`** (default `false`), mirroring the existing
  `sensitive` flag: catalog-only, never part of the ClickHouse DDL, declarable when a schema is
  defined and changeable later via the schema `PATCH` merge-patch.
- **Wildcard projections skip heavy columns.** A SQL `SELECT *` (and `SELECT <alias>.*`) and a
  row-mode DSL query with an empty/omitted `select` both expand to the entity's non-heavy **source**
  columns. Naming a heavy column **explicitly** — in SQL or in the DSL `select` — returns it
  unchanged, and heavy columns stay fully usable in `filter`/`WHERE`, `group_by`, `sort`, and
  function arguments.
- **BREAKING (SQL surfaces): a star no longer expands to enrichment columns.** `SELECT *` currently
  expands to the whole flat schema, dotted enrichment columns included, which also drags a `LEFT JOIN`
  per enrichment into the cheapest-looking query in the surface. The two wildcard surfaces now agree
  on one rule — source columns only — so an enrichment join stays opt-in exactly as the DSL already
  specifies. Enrichment columns remain selectable by their quoted qualified name
  (`SELECT "deployment_ref.display_name" FROM …`), which is the only way they were ever addressable
  in SQL anyway.
- **Discovery advertises the flag** so a client can see what a wildcard leaves behind:
  `GET /v1/queries/entities/schema/{name}` fields and `GET /v1/tables/{name}` columns carry `heavy`.
- A wildcard that would expand to **nothing** (every visible field heavy) is rejected with a clear
  `400 bad_request` / `INVALID_ARGUMENT` naming the entity and telling the caller to list columns
  explicitly — instead of failing as invalid SQL or an empty projection.
- Seed `dial_usage_log.request_body` and `dial_usage_log.response_body` as heavy (they are already
  `sensitive`, so only `FULL_ADMIN` callers — and `security.mode=none` deployments — see any
  behavior change at all). Every other seeded column keeps `heavy = false`.
- Document the flag (`docs/heavy-columns.md` alongside the existing tag docs) including the
  "how do I still get the payload" answer.

The heavy flag itself is inert by default: with `heavy = false` everywhere, projections behave exactly
as today. The two deliberate behavior changes are the seeded `dial_usage_log` payload columns (visible
only to callers entitled to see them) and the star/enrichment rule above (SQL surfaces only, and only
for entities that actually have enrichments attached).

## Capabilities

### New Capabilities

None — this extends existing capabilities.

### Modified Capabilities

- `table-management`: column metadata gains the `heavy` flag (schema definition, schema `PATCH`
  `update`/`add`, rename preservation, `GET /v1/tables/{name}` responses) plus the seeded heavy set
  on `dial_usage_log`.
- `query-schema-discovery`: base-schema fields carry `heavy`; the flag is not role-filtered (it is a
  cost hint, not an access control).
- `structured-query`: the empty/omitted row-mode `select` expansion excludes heavy columns, and an
  expansion that would be empty is rejected.
- `sql-query-translation`: `SELECT *` / `SELECT t.*` expands to the non-heavy source columns, before
  validation, so the produced `StructuredQuery` and the reported result columns agree; enrichment
  columns are no longer star-expanded (**BREAKING**).
- `query-translation-endpoint`: an omitted row-mode `select` now renders as `SELECT *` for every
  entity — with one shared wildcard rule the star round-trips exactly, so the enriched-entity special
  case (spell the source columns out) is gone.
- `rest-sql-endpoint`: `SELECT *` response shape follows the new star expansion.
- `flight-sql-endpoint`: the Arrow schema of a `SELECT *` statement carries the non-heavy source
  columns only.

## Impact

- **Postgres catalog**: new migration adding `column_mapping.heavy BOOLEAN NOT NULL DEFAULT FALSE`
  and seeding the two `dial_usage_log` payload columns. Next free slot on merged history is `V18`.
- **API**: additive only — `heavy` appears in table-management column payloads and in the query
  schema-discovery field payloads; accepted (optional) in `POST /v1/tables/{name}/schema` columns and
  in `PATCH /v1/tables/{name}/schema` `add`/`update` entries.
- **Query pipeline**: `QueryFieldBinding` carries the flag; `StructuredQueryBuilder` empty-projection
  expansion and a new pre-validation SQL star expansion in `service.query.sql` consume it. All four
  query surfaces (DSL execute, REST SQL, Flight SQL, translate endpoints) inherit the behavior from
  the shared pipeline — no per-surface logic.
- **Consumers**: notebooks that relied on `SELECT *` returning payload columns must name them
  explicitly. Documented; no config toggle (a per-request override cannot reach the Flight/pandas
  client, which sends only SQL text).
