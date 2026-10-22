## Context

`ScanService` is the single governed read the Enrichment Runner drives. It resolves everything from
the stored rule, projects only rule-referenced columns, seeks by an opaque `(version, identity)`
cursor, hides a still-mutating window behind a safety-lag, and reads `final=1`. Two of its inputs are
not derived from the rule at all:

- the **cursor columns**, which come from a hardcoded `if` on the source name
  (`ScanService.cursorColumns()`), and
- the **window**, which does not exist — the scan always runs from the cursor to the safety-lag
  horizon.

This change replaces the first with catalog metadata and adds the second. Nothing else about the scan
contract moves.

## Goals / Non-Goals

**Goals:**

- Delete the hardcoded source branch without changing `dial_usage_log`'s behaviour by one row.
- Make "this source is scannable, and here is its total order" a catalog fact.
- Let a caller bound a scan to a version range so a backfill does not replay from the source start.
- Establish a single `{from?, to?}` window vocabulary that the Class-1 materialize path reuses.

**Non-Goals:**

- Exposing the scan-metadata columns on the `/v1/tables` API (deferred; migrations configure them).
- Multi-column version or identity tuples — the cursor stays a two-element `(version, identity)`.
- Changing cursor encoding, safety-lag semantics, projection, or authorization.
- Seeding a second scannable source. Nothing in group enrichment scans anything but `dial_usage_log`.

## Decisions

**D1 — Store the scan metadata on `table_definition`, not in a new `source_scan_metadata` table.**
The relationship is strictly 1:1 with a table definition, both columns are nullable for the many
tables that are not scan sources, and the definition is already loaded on the scan path
(`RuleService.sourceOf` resolves through it). A child table would add a join and a second lifecycle
for two nullable strings. *Alternative rejected:* a `source_scan_metadata(table_name PK,
version_column, identity_column)` child table — cleaner normalization, but it buys nothing here and
costs a repository, an entity, and a cascade rule.

**D2 — No `/v1/tables` request field for the two columns in this change.** Declaring a table's total
order is an operator-level statement about physical guarantees ("this column is monotonic at ingest
and never backdated") that the scan's exactly-once semantics depend on; the safety-lag design note in
the shipped spec makes that explicit. Letting any table author set it through the API without those
guarantees would silently break exactly-once. Migrations configure it until there is a reason to
expose it. *Alternative rejected:* adding it to `DefineSchemaRequest` now — it widens the
table-management contract for a capability with no caller yet, and the conversations/turns change
seeds its spines in its own migration anyway.

**D3 — Keep the existing rejection for an unconfigured source, with a catalog-driven message.** The
hardcoded branch threw `QueryValidationException`, which the error contract maps to HTTP 400
`bad_request`; that stays. (An earlier plan described it as a 422 — it was not, and the code is the
authority.) Only the message changes, from "not supported … in this phase" to one naming the missing
`version_column`/`identity_column` configuration, so callers keying on status keep working.
*Alternative rejected:* a new error code — the original error contract deliberately has no per-cause
code here, and `QueryValidationException` already carries the detail in `message`.

**D4 — `from`/`to` are ISO-8601 instants over the version column, not opaque cursors.** The window's
purpose is human/operator-driven backfill ("re-run July"), which is expressed in time, not in an
opaque signed token. The cursor remains the only thing that carries exactly-once position; the window
is an independent bound. Instants also make the window meaningful across a cursor-key rotation.
*Alternative rejected:* `from`/`to` as cursor tokens — symmetric with `cursor`, but it forces a caller
who knows a time range to first obtain a cursor at that position, which no endpoint offers.

**D5 — The window is half-open `(from, to]`, matching the keyset predicate.** The existing seek is
`(version, identity) > cursor`, i.e. exclusive at the low end; the safety-lag is `version <= horizon`,
inclusive at the high end. `version > from AND version <= to` composes with both without a boundary
row being returned twice or skipped when a caller chains adjacent windows. *Alternative rejected:*
closed `[from, to]` — chaining `[a,b]` then `[b,c]` would re-emit every row at exactly `b`.

**D6 — `from`/`to` compose with `cursor` rather than replacing it.** All three predicates are ANDed.
A backfill that supplies only `{from, to}` walks the window from its start; a backfill that pages
within a window echoes `nextCursor` and keeps re-supplying the same `{from, to}`. This keeps one code
path and means `hasMore`/`nextCursor` mean the same thing in both modes. *Alternative rejected:* a
separate windowed-scan endpoint — two code paths for one predicate difference.

**D7 — Reject `from >= to` with HTTP 400 rather than returning an empty page.** An inverted window is
always a caller bug and is otherwise indistinguishable from a legitimately exhausted range, which
would make a backfill silently report "done". *Alternative rejected:* returning an empty page — quiet
data loss in the runner's backfill accounting.

## Risks / Trade-offs

- **A misconfigured `version_column` breaks exactly-once silently** (e.g. a column that is backdated
  or non-monotonic) → mitigated by D2: the columns are operator-set via migration, and the shipped
  spec's safety-lag requirement already states the monotonicity precondition. The scan still fails
  loudly if the column does not exist (ClickHouse rejects the statement → 400).
- **A `to` above the safety-lag horizon is silently clamped** by the existing lag predicate → this is
  correct behaviour (the horizon must always win) and is stated in the spec so a caller does not
  mistake a short page for an exhausted window; `hasMore` and `nextCursor` continue to describe
  progress within the window.
- **Two nullable columns on `table_definition` that most rows never use** → accepted; the alternative
  (D1) costs more than the two nulls.

## Migration Plan

1. `V13__source_scan_metadata.sql` adds the two nullable columns and seeds `dial_usage_log` with
   `('_ingested_at', 'event_id')` — byte-identical to what the deleted branch returned.
2. `TableDefinitionEntity` gains the fields; `ScanService` resolves through
   `TableDefinitionRepository` and the hardcoded branch is deleted in the same commit.
3. `ScanRequest` gains `from`/`to`; omitted values leave the query shape exactly as it is today.
4. **Rollback:** the migration is additive and the seeded values reproduce the previous hardcoded
   behaviour, so reverting the code alone is safe; the columns can be left in place.

## Open Questions

- Whether the scan-metadata columns should eventually be surfaced read-only on `GET /v1/tables/{name}`
  so an operator can confirm a source is scannable without a database query. Deferred — no caller
  needs it yet.
