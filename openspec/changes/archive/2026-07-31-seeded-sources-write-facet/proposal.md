## Why

The `write` facet names the engine a table was materialized with — `append` is a plain `MergeTree` that
keeps every row, `upsert_by_key` a `ReplacingMergeTree` that collapses to the latest row per
`ordering_key`. For both seeded sources it names the wrong one.

`dial_usage_log` and `rate_analytics` are both `ENGINE = ReplacingMergeTree(_ingested_at)` ordered by
`(project_id, deployment, request_time, trace_id, core_span_id)`. That is deliberate, and their
ClickHouse migrations say why: the sort tuple is the **re-ingest dedup key**, so a log line delivered
twice collapses on the greatest `_ingested_at` instead of being counted twice. V1 goes as far as
documenting which columns must be deterministic for a span for the collapse to be sound. This is the
`upsert_by_key` discipline exactly.

Both are nonetheless recorded as `append`. `write_mode` arrived in `V19` with an `APPEND` default and the
seeded rows predate it; their DDL is hand-written (`clickhouse/V1`, `V2`) rather than rendered by
`TableDdlRenderer`, so nothing ever reconciled the declaration against the engine.

**Nothing has misbehaved yet only because no read path consults the facet.** Deduplication is applied as
the statement-wide `final = 1` setting, which asks ClickHouse what engine a table has rather than asking
the catalog. The disagreement stayed invisible precisely because the mechanism ignored the thing that
disagreed.

It stops being invisible for anything that reasons from the declaration:

- An operator comparing raw SQL against ADAS sees a gap and a catalog that explains it wrongly. On a real
  `dial_usage_log`, a few percent of rows share an ordering key, so ADAS returns fewer rows than a raw count.
  That is the engine doing its job, but the catalog says the table keeps every row, so the only available
  reading is "ADAS is losing a few percent of an append-only log" — a conclusion that costs real investigation
  time. It was reached during this testing pass.
- Governance already reasons from write discipline (`aggregate-pipeline` rejects an `append` rollup
  target because it would accumulate instead of replacing), and future rules will. A facet that is wrong
  about the two tables everything reads is a bad foundation to add rules on top of.

## What Changes

- The catalog records `dial_usage_log` and `rate_analytics` as `upsert_by_key`, matching the engine they
  were materialized with and the intent their DDL documents.
- The `table-management` spec gains a requirement that a seeded source's `write` facet agrees with its
  physical engine — the same shape as the existing seeded-`sensitive` requirement, except that this value
  is not an operator's to choose: the DDL decides it.
- The `structured-query` deduplication requirement keeps its guarantee and its mechanism, and gains what
  it was missing: the statement that the catalog's `write` facet must describe the engine truthfully,
  rather than standing as an independent second opinion nothing reconciles.

**No behavioural change.** Reads and writes are byte-for-byte unaffected on every surface: `final = 1`
already deduplicates both tables and will continue to. This corrects what ADAS *believes*, not what it
does.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `table-management`: the "Table write discipline" requirement gains the statement that the facet
  *describes* the engine rather than merely selecting it at creation, plus a scenario pinning the two
  seeded sources' values.
- `structured-query`: "Read result deduplication (latest-version-wins)" gains the cross-reference that
  ties the guarantee to the write discipline, so the two cannot drift again.

## Impact

- **Code**: none. This is a catalog migration and two spec corrections.
- **Catalog**: a Flyway migration setting `write_mode = 'UPSERT_BY_KEY'` on the two seeded sources.
  Metadata only — `write` selects the engine at materialization and both tables are already materialized,
  so nothing issues an `ALTER`.
- **API**: `GET /v1/tables/dial_usage_log` and `GET /v1/tables/rate_analytics` report
  `write: "upsert_by_key"` instead of `"append"`. That is the value they should always have reported.
- **Not affected**: the query surfaces, the enrichment paths, the aggregate pipeline, and every rendered
  statement. The `write` facet is immutable through the API, so this cannot be done as an operator edit —
  a migration is the only route.

## Superseded diagnosis

This change began as `final-semantics-append-sources`, on the premise that `final = 1` is not a no-op on a
plain `MergeTree` and was therefore silently deduplicating an append-only log. Both halves were tested
against ClickHouse 25.3.14.14 and both are false — see the design's "What the measurements actually
showed". The corrected finding is the one above: a documentation defect, not a data one.
