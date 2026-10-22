## Context

ADAS is the governed gate between analytics consumers and ClickHouse. It already has the
enrichment table primitive (create grain shell → `PATCH` columns → query-time LEFT JOIN on the
grain key) and a minimal insert endpoint, but not the runtime plumbing the external Enrichment
Runner (the Class-2 ML/LLM path) needs. Three concrete gaps:

- `StructuredQueryBuilder` throws `"cursor pagination is not supported"` — there is no
  incremental scan.
- Enrichment DDL declares `_updated_at DateTime64(3)` with **no default**, so the
  `ReplacingMergeTree` dedup version is effectively unset and writes don't collapse to a
  deterministic latest.
- `POST /v1/tables/{name}/rows` is insert-only, untargeted, and can't stamp a version.

Grounded facts (verified in the codebase): Java 25 / Spring Boot 4, Jackson 3, MapStruct+Lombok,
Gradle `-Werror`, checkstyle (Google, 180-char). Query pipeline is
`StructuredQueryController → StructuredQueryService → StructuredQueryBuilder (jOOQ) →
StructuredQueryExecutor` (reads with `final=1`); enrichments are auto-LEFT-joined onto their
source in `CatalogQueryService.buildModel`. Write path is
`TableController.writeRows → TableDataService.write → CatalogRowWriter.insert` (JSONEachRow,
dedicated writer credential, batch ≤ `maxWriteBatchRows`=10000). `TableDdlRenderer` renders
enrichment as `ReplacingMergeTree(_updated_at)` ORDER BY grain key. `dial_usage_log` is
`ReplacingMergeTree(_ingested_at)`, ORDER BY the 5-tuple
`(project_id, deployment, request_time, trace_id, core_span_id)`,
`_ingested_at DateTime64(3,'UTC') DEFAULT now64(3)`, and carries an `event_id UUID` = hash of
`(trace_id, core_span_id)`, tagged `identity`. Postgres migrations run to `V10` (next `V11`);
ClickHouse migrations run to `V2` under `db/migration/clickhouse` (next `V3`). Errors use the
`ErrorView{status,error,message,path,method}` contract with handlers in
`DefaultExceptionHandler`.

This change is the **`enrichment-runtime`** data-plane (plan items B0+B2+B3+B4 + B5 scan authz).
The evaluator/rule **registry** (B1) ships separately as `enrichment-registry`; both build on
`rework-uac-role-based-access`.

## Goals / Non-Goals

**Goals:**
- Incremental, exactly-once (per-effect) scan of a source via an opaque, ADAS-owned, signed
  cursor, exposed only through a governed rule-scoped endpoint.
- Versioned write-back so RMT collapses to the latest result per grain, through a rule-scoped
  endpoint that can't write arbitrary tables or columns.
- Cheap ingest-order range scans (minmax skip index) despite `_ingested_at` not leading the
  primary sort key.
- Least-privilege authorization keyed to the rule's target enrichment — no global read/write for
  the runner.

**Non-Goals:**
- The evaluator/rule registry and its CRUD/governance (→ `enrichment-registry`).
- Any dynamic dry-run or rendered/DIAL-calling preview (deferred from both services; any eventual
  rendering belongs in the runner, which owns the JSONata + template engine).
- Cursor pagination on the general `POST /v1/queries/execute` (stays deferred — smaller surface).
- Pushing `sampling` into scan SQL (kept runner-side).
- Generalizing per-source version/identity cursor columns to arbitrary sources (this change wires
  `dial_usage_log` only).
- Multi-writer / column-coalescing enrichment (RMT full-row replace makes it unsafe; one rule per
  enrichment is enforced by the registry).

## Decisions

**D1 — Reuse the query pipeline for the scan; don't fork a second query path.** The scan is a
governed read. Add a **scan mode** inside `StructuredQueryBuilder`/`StructuredQueryExecutor`
(source-only, no enrichment joins, projection pushdown, `final=1`, keyset predicate + tuple
ORDER BY + LIMIT) rather than a parallel engine. *Alternative rejected:* a generic
`POST /v1/queries/scan` — weaker governance, re-opens free-SQL/entitlement concerns for no
gain here.

**D2 — The scan is rule-scoped, not free-SQL.** `POST /v1/rules/{id}/scan` resolves the stored,
already-validated filter/source/grain from the rule record, so there are no free-SQL round-trips
at runtime and authorization can key off the rule's target enrichment. *Alternative rejected:*
letting the runner submit filters at scan time — that would move governance to runtime and
duplicate the registry's static gate.

**D3 — Cursor is opaque, ADAS-owned, HMAC-signed, and rotatable.** `CursorCodec` produces
`base64url(format-version ∥ key-id ∥ entity ∥ tuple)` + HMAC over a config secret. Decode verifies
HMAC and that the entity matches the rule's source (else 400 `invalid_cursor`). A **keyring**
keyed by `key-id` lets the secret rotate without invalidating outstanding cursors (dual-key
window). The server is **stateless** about cursor position — it signs/decodes only; the runner
persists the cursor. This makes live and backfill scans of the same rule indistinguishable
server-side and lets `cursor` omitted ⇒ scan from source start. *Alternative rejected:* a plain
tuple echoed by the caller — lets a caller forge a position and read past entitlement/lag.

**D4 — Cursor tuple = `(_ingested_at, event_id)`; advance by ingest/version order.** This is a
compact unique total order (given the B0 uniqueness check). Advancing by `_ingested_at` (not the
source sort key) means a re-ingested/late row gets a higher `_ingested_at`, reappears after the
cursor, and is re-enriched **by design** — the scan tolerates re-ingestion and the
runner tolerates retries because enrichment writes collapse by grain. *Fallback:* the full 5-tuple
`(_ingested_at, project_id, deployment, request_time, trace_id, core_span_id)` if `event_id` isn't
1:1 (see B0/D8).

**D5 — Safety-lag over the still-mutating window.** Exclude `_ingested_at > now64(3) − lag`
(configurable, default 5s). Relies on `_ingested_at` being assigned at ingest (`DEFAULT now64(3)`)
and never backdated. Response carries `lagSeconds = server_now − max(_ingested_at)` below the
horizon so the runner emits its lag metric without decoding the cursor.

**D6 — Versioned write via DDL default, not per-row stamping.** Give enrichment
`_updated_at DateTime64(3) DEFAULT now64(3)` in `TableDdlRenderer` (new tables) + a ClickHouse
migration `ALTER … MODIFY COLUMN` for any existing enrichment tables. `writeRows`/`CatalogRowWriter`
stay unchanged: `_updated_at` remains caller-unsettable and is omitted from JSONEachRow, so
ClickHouse stamps the default. *Alternative rejected:* stamping `_updated_at` per row in
`CatalogRowWriter` — more code, per-writer, and easy to bypass; the DDL default covers all writers.

**D7 — Rule-scoped write-back symmetric with scan.** `POST /v1/rules/{id}/rows` resolves the
rule's `target_enrichment`, validates the row column-set ⊆ {rule outputs} ∪ {grain key} ∪
{provenance}, authorizes by target-enrichment access, then delegates to `TableDataService.write`.
The runner never names a table and can't write arbitrary columns; generic
`POST /v1/tables/{name}/rows` stays for admins. Because the registry enforces **one rule per
enrichment**, each grain has a single writer, so provenance columns are unambiguous. Provenance
(`evaluator_version`, `model`, `enriched_at`) are ordinary user columns added via `PATCH …/schema`.

**D8 — B0 grain: use the existing `event_id`, gated by a verify-before-build check.** No new
column on the happy path. Apply-time gate:
`SELECT count() - uniqExact(event_id) FROM dial_usage_log FINAL` must be `0`. If it fails, the
fallback adds a materialized single-column grain `cityHash64(...)` over the full identity tuple
(+ `MATERIALIZE COLUMN`), exposes it in `column_mapping`, and uses it as grain/cursor tie —
consuming the next CH migration slot and pushing B4/B3 migration numbers by one.

**D9 — Scan index: minmax on `_ingested_at`.** Add a minmax skip index and `MATERIALIZE` it for
existing parts so the ingest-order range scan prunes granules even though `_ingested_at` doesn't
lead the primary sort key. Run as an explicit, monitored, off-peak migration (`MATERIALIZE INDEX`
cost on existing parts).

**D10 — Authz reuses the rework-uac `access` model.** Scan (read/scan flavour) and write (write
flavour) are authorized by the rule's target-enrichment `access`. No new `ENRICH` permission, no
global `READ_ONLY_ADMIN` for the runner. `FULL_ADMIN` satisfies both. *Fallback if rule-scoped
authz slips:* grant the runner `READ_ONLY_ADMIN` and tighten later.

**D11 — Cross-change dependency handling.** The rule-scoped endpoints read the `rule` /
`enrichment_grain` records owned by `enrichment-registry`. The keyset engine, `CursorCodec`, scan
index (B4), and `_updated_at` DDL (B3b) are independent and land first and are testable in
isolation; the controllers integrate once the registry's rule schema exists. Sequence both after
`rework-uac-role-based-access` merges.

## Risks / Trade-offs

- **Grain non-uniqueness on `dial_usage_log`** → B0 verify-before-build gate; materialized-tuple
  fallback + migration renumber if the check fails.
- **`final=1` + keyset cost on large scans** → minmax skip index (B4) + projection pushdown +
  monitoring; no-FINAL is a caveated fallback.
- **`MATERIALIZE INDEX`/`COLUMN` cost on existing parts** → explicit, monitored, off-peak
  migration.
- **Page-boundary collision skips a row** → unique cursor tuple with `event_id` (or full 6-tuple)
  tiebreaker; collision test forces equal `_ingested_at` values.
- **HMAC secret rotation invalidating outstanding cursors** → keyring with `key-id` + dual-key
  window; unknown/retired key → 400 `invalid_cursor`.
- **Source is RMT (re-ingest) not append-only** → re-enrichment by design; reconcile
  the runner doc's "append-only" wording.
- **Migration number races** → use next free (`V3` index, `V4` `_updated_at`); re-check at apply;
  B0 fallback shifts them by one.
- **Coordinating with `rework-uac-role-based-access`** → land after it merges; build on its
  `access` model, don't fork it.

## Migration Plan

1. Land after `rework-uac-role-based-access` merges.
2. **B0/B4 first:** run the `event_id` uniqueness check; add the minmax scan index
   (`V3__dial_usage_log_scan_index.sql`) and `MATERIALIZE` off-peak.
3. **B3b:** `_updated_at DEFAULT now64(3)` in `TableDdlRenderer` + `V4__enrichment_updated_at_default.sql`
   `ALTER … MODIFY COLUMN` for any existing enrichment tables (likely none).
4. **B2:** keyset scan mode + `CursorCodec` + config (safety-lag + HMAC keyring).
5. **B2/B3 endpoints:** `RuleDataController` (`/scan` + `/rows`) once the registry rule schema is
   present; `RuleWriteValidator`; new `@ExceptionHandler`s + error codes.
- **Rollback:** migrations are additive (index + column default) — drop the index / revert the
  default to roll back; the endpoints are new surface (remove without affecting existing paths).

## Open Questions

- **B0 verify-before-build** (`count() - uniqExact(event_id) FROM dial_usage_log FINAL = 0`) — must
  be run against a live/representative ClickHouse at apply; drives the happy-path-vs-fallback and
  the migration numbering. Requires Docker/Testcontainers or a CLI-started ClickHouse.
- Confirm `V3`/`V4` (ClickHouse) are still free at apply time.
- Exact seam for reading the `rule`/`enrichment_grain` records across the change boundary — a
  shared read repository vs. depending on `enrichment-registry`'s entities — resolved when the two
  changes integrate.
