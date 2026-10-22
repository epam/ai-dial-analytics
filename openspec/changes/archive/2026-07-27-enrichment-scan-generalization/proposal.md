## Why

The rule-scoped incremental scan (`POST /v1/rules/{id}/scan`) carries two restrictions from its first version that
block group enrichment and the conversations/turns change that follows it:

1. **The scan's cursor columns are hardcoded to one table.** `ScanService.cursorColumns()` is a
   literal `if ("dial_usage_log".equals(source))` returning `(_ingested_at, event_id)`; every other
   source is rejected with "incremental scan is not supported for source '…' in this phase". Any new
   scannable source — the conversations/turns spines, an aggregate rollup — would have to edit that
   `if`, which is neither discoverable nor deployable without a code change.
2. **The scan can only replay from a cursor to the head.** `ScanRequest` is `{cursor, limit}` with no
   upper or lower bound, so a runner backfilling a specific range must replay the whole source from
   the start and discard everything outside the range it wanted.

Both are scan-layer plumbing that belongs with the enrichment runtime capability rather than with the
tracks that consume it. Landing them first means the Class-1 materialize path and the
conversations/turns change never have to reach back into `ScanService`.

## What Changes

- **Catalog-configured scan cursor columns (S1).** `table_definition` gains `version_column` and
  `identity_column`; `dial_usage_log` is seeded with `(_ingested_at, event_id)` — exactly what the
  hardcoded branch returned, so behaviour is unchanged. `ScanService` resolves them from the catalog
  and deletes the hardcoded `if`. A source with either column unconfigured is still rejected with
  HTTP 400 `bad_request` — the status and code the hardcoded branch already produced — now with a
  catalog-driven message naming the missing configuration rather than a phase limitation.
  - The two columns are **catalog-level only in this change** — no `/v1/tables` request field. They
    are set by migration (as `dial_usage_log` is) so that configuring a scannable source stays an
    operator action rather than a caller-supplied one. Exposing them on the table API is deferred.
- **Bounded scan windows (S2).** `ScanRequest` gains optional `from` and `to` (ISO-8601 instants)
  bounding the source's version column as the half-open range `(from, to]`. They add one predicate
  alongside the existing keyset and safety-lag conditions; ordering, `final=1`, projection, and the
  opaque cursor are untouched. A caller may combine `from`/`to` with a `cursor` — the cursor still
  drives exactly-once ordering, the window merely bounds it. `from >= to` is rejected with HTTP 400.
- **One window vocabulary for the phase.** The `{from?, to?}` shape and its `(from, to]` semantics are
  reused verbatim by the Class-1 materialize endpoint in the `class1-sql-enrichment` change, so the
  runner has one notion of "window" across both execution paths.

Out of scope (locked): no change to cursor encoding, safety-lag, projection, authorization, or the
write-back path; no `/v1/tables` API surface for the scan-metadata columns; no second seeded scan
source (nothing in group enrichment scans anything but `dial_usage_log`).

## Capabilities

### New Capabilities

<!-- None. Both items extend the existing enrichment-runtime capability. -->

### Modified Capabilities

- `enrichment-runtime`: the scan's version/identity columns become catalog-configured per source
  rather than hardcoded for `dial_usage_log`, and the scan request accepts an optional bounded
  version window.

## Impact

**New code:** none — two fields on `TableDefinitionEntity`, resolution logic in `ScanService`, two
fields on `ScanRequest`.

**Migration:** Postgres `V13__source_scan_metadata.sql` (two nullable columns on `table_definition`
plus the `dial_usage_log` seed). No ClickHouse DDL.

**Reuses:** `TableDefinitionRepository`, the existing `QueryValidationException` → 400/422 mapping,
the existing keyset/safety-lag predicate construction in `ScanService.buildScan`.

**Config:** none.

**Cross-change dependency:** `class1-sql-enrichment` consumes the `{from?, to?}` window shape;
the conversations/turns change consumes the catalog-configured cursor columns.
