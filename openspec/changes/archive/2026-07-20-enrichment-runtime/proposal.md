## Why

The external Enrichment Runner (the Class-2 ML/LLM path) needs to **incrementally scan
a source exactly-once** and **write versioned enrichment results back** through ADAS as the
governed gate. Neither exists today: `StructuredQueryBuilder` throws `"cursor pagination is not
supported"`, the enrichment `_updated_at DateTime64(3)` column has **no default** (so the
`ReplacingMergeTree` dedup version is effectively unset), and `POST /v1/tables/{name}/rows` is
insert-only and untargeted. This change delivers the **data-plane** the runner scans and writes
through — the hard blocker for runner bring-up.

## What Changes

- **B0 — Grain readiness (verify-only, happy path is a no-op).** Adopt the existing
  `dial_usage_log.event_id UUID` (a deterministic hash of `(trace_id, core_span_id)`, already
  `identity`-tagged and in `column_mapping`) as the enrichment grain key. Gated on a
  verify-before-build check that `event_id` is 1:1 with rows
  (`count() - uniqExact(event_id) FROM dial_usage_log FINAL = 0`). Fallback only if it fails:
  a materialized single-column grain over the full identity tuple (consumes an extra CH
  migration slot, renumber at apply).
- **B2 — Governed incremental scan.** New `POST /v1/rules/{id}/scan` (body `{cursor?, limit?}` →
  `{rows[], nextCursor, hasMore, lagSeconds}`). ADAS resolves the rule's already-validated
  source / filter / referenced columns / grain, then runs a **keyset (seek) scan** built inside
  the existing query pipeline: source-only (no enrichment LEFT JOINs), projection pushdown,
  `final=1`, tuple-compare keyset predicate on an opaque HMAC-signed cursor, `ORDER BY` the
  cursor tuple, `LIMIT`. Cursor tuple = `(_ingested_at, event_id)`. Configurable safety-lag
  excludes the still-mutating recent window. New `CursorCodec` (base64url ∥ key-id ∥ entity ∥
  tuple + HMAC, with a rotating keyring). The general `/execute` `CursorPage` stays deferred.
- **B3 — Versioned, rule-scoped write-back.** New `POST /v1/rules/{id}/rows`, symmetric with
  scan: ADAS resolves the rule's `target_enrichment`, validates the row column-set ⊆
  {rule output columns} ∪ {grain key} ∪ {provenance}, authorizes by target-enrichment access,
  then delegates to the existing `TableDataService.write`. Give enrichment `_updated_at` a
  `DEFAULT now64(3)` so every write lands with an increasing RMT version (write path otherwise
  unchanged — `_updated_at` stays caller-unsettable; ClickHouse stamps it).
- **B4 — Scan index.** Add a **minmax skip index on `_ingested_at`** to `dial_usage_log` and
  `MATERIALIZE` it, so the ingest-order keyset range scan stays cheap even though `_ingested_at`
  doesn't lead the primary sort key.
- **B5 (scan authz only).** Authorize the rule-scoped scan/write by the rule's
  **target-enrichment access** (reuse the `rework-uac-role-based-access` `access` model) — no new
  `ENRICH` permission and no global `READ_ONLY_ADMIN` for the runner. (Write grants themselves
  ship with `enrichment-registry`.)
- **Error contract:** new stable codes `invalid_cursor` (400), `write_column_not_allowed` (422),
  `rule_access_denied` (403), `unknown_rule` (404) in `DefaultExceptionHandler`.

Out of scope (locked): the evaluator/rule registry + CRUD (→ `enrichment-registry`, B1); any
dynamic dry-run / rendered preview; pushing `sampling` into scan SQL; generalizing cursor
metadata to arbitrary sources; column-coalescing multi-writer enrichment.

## Capabilities

### New Capabilities
- `enrichment-runtime`: the governed data-plane for the Enrichment Runner — rule-scoped
  incremental keyset scan (`POST /v1/rules/{id}/scan`) with an opaque ADAS-owned cursor and
  safety-lag, and rule-scoped versioned write-back (`POST /v1/rules/{id}/rows`) with
  column-set governance and target-enrichment authorization.

### Modified Capabilities
- `structured-query`: the query pipeline gains a **keyset scan mode** (source-only, projection
  pushdown, `final=1`, cursor keyset predicate + ordering) exposed only through the governed
  rule-scoped scan endpoint.
- `table-management`: enrichment DDL gives `_updated_at` a `DEFAULT now64(3)` so RMT collapses
  by grain to the latest write; the write-rows contract documents `_updated_at` as a
  system-stamped (caller-unsettable) version column.

## Impact

- **New code:** `web/controller/enrichment/RuleDataController` (hosts `/scan` + `/rows`);
  `web/dto/enrichment/*`; `service/query/CursorCodec`; scan-mode additions in
  `StructuredQueryBuilder`/`StructuredQueryExecutor`; `service/query/model/{CursorPage,
  QueryResultPage}` (+`nextCursor`,`hasMore`); `service/enrichment/RuleWriteValidator`;
  new `@ExceptionHandler`s in `DefaultExceptionHandler`.
- **Migrations:** ClickHouse `V3__dial_usage_log_scan_index.sql` (minmax index) and
  `V4__enrichment_updated_at_default.sql` (`_updated_at` default). Verify `V3/V4` still free at
  apply; the B0 fallback would push these numbers by one.
- **DDL:** `TableDdlRenderer` enrichment `_updated_at ... DEFAULT now64(3)` for new tables.
- **Config:** scan safety-lag + cursor HMAC keyring (documented in `docs/configuration.md`).
- **Cross-change dependency:** the rule-scoped endpoints resolve the `rule` /
  `enrichment_grain` records **owned by `enrichment-registry` (B1)**. The keyset engine, cursor
  codec, scan index, and `_updated_at` DDL are independent and can land first; the endpoints are
  end-to-end functional only once the registry's rule schema exists. Both changes build on
  `rework-uac-role-based-access` (currently in-flight) and should land after it merges.
