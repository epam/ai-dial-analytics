## Why

Every cursor-bounded pipeline read on `dial_usage_log` reads the whole table, whatever the size of its
window. The keyset seek `(toUnixTimestamp64Milli(version), toString(identity)) > (v, id)` is a compare of
expressions over a column outside the sort key, so it prunes nothing. On a table of a few million rows the
cursor-bounded statement families read on the order of a TB and spend many minutes of ClickHouse time a day to process
windows of a few thousand rows. At ~10× that volume each statement reads on the order of 100 M rows, and a Class-1
pipeline runs on the order of a thousand of them a day.

The obvious fix, a plain `version >= v` bound, measurably does **not** work under the pipeline settings.
The `idx_ingested_at` skip index prunes to 6/107 parts. Then `use_skip_indexes_if_final_exact_mode`
expands the read back to 107/107 under `final=1`. What does work was replayed against a running deployment's real
statements and returned identical results. It is a bound on the source's catalog `partition_column`,
derived from the data inside the statement.

## What Changes

- Every keyset seek, and every caller-window lower bound `version > from`, on a source that declares a
  `partition_column` gains a second, data-derived predicate:
  `"<source>"."<partition_column>" >= (SELECT min(<partition_column>) FROM <source> WHERE <version> >= fromUnixTimestamp64Milli(v) SETTINGS final=0)`.
  It is exact: every row the seek admits satisfies it, so no window, cursor, page or written row changes.
- The bound is added in `CursorPredicates`, the single place every pipeline-scoped read composes its
  window from. It therefore reaches all of them at once: the scan, the Class-1 materialize probe and its
  `INSERT … SELECT`, the aggregate refresh probe and its dirty-key sub-select, and the grain-join key
  sub-selects that bound joined enrichments such as `dial_usage_log_payload` through
  `request_time IN (…)`.
- A source with no `partition_column` renders exactly the statement it renders today.
- No API, configuration, catalog-schema or migration change.

Measured against a running deployment by replaying query_log statements with and without the bound, 3 runs each:

| Statement family | Rows read | Bytes | Latency |
|---|---|---|---|
| Class-1 probe / write joining payload | ~16 M → ~0.2 M | ~500 MiB → 3–4 MiB | ~350 ms → ~50 ms |
| Plain cursor probe | ~5 M → ~0.2 M | 136 MiB → 2 MiB | ~130 ms → ~18 ms |
| Scans joining `usage_client_identity` | ~10 M → a few M | ~2× less | 1.3–1.5× faster |

The trick also holds on ClickHouse 25.3, the supported floor. A local 25.3.14 / 26.3.21 check on 5.1 M
synthetic rows read 15–18× fewer rows and returned identical results.

**Non-goals.** This change does not try to fix three things, and documents each:

- **An unpartitioned enrichment joined into a window.** `usage_client_identity` is ordered by `event_id`
  and has no partition, so its side of the join is still read in full.
- **The aggregate refresh's recompute half.** For example, the `sessions` re-aggregation over every
  affected key reads ~1.8 GiB in ~3 s, and is deliberately unbounded by the cursor.
- **A cursorless first page with no `from`.**

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `enrichment-runtime`: adds a requirement that a cursor-bounded read on a partitioned source restricts
  itself to the partitions its window can reach, through an exact, data-derived bound, without changing
  any observable outcome. It lives beside "Exactly-once keyset scan semantics" and "Catalog-configured
  scan cursor columns", because `CursorPredicates` is shared by the scan, Class-1 materialize and
  aggregate refresh paths.

## Impact

- **Code**:
  - `service/enrichment/support/CursorPredicates`, where the bound is built.
  - `CursorColumns` / `ScanSourceMetadata`, which carry the source table and its `partition_column`
    from the catalog.
  - The four call sites (`ScanService`, `MaterializeService`, `AggregateRefreshService`, and the
    `window(from, to)` helper).
- **Surfaces**: none. No `/v1` endpoint, config key, catalog column or migration changes. The rendered SQL
  of cursor-bounded reads on partitioned sources gains one predicate per seek.
- **ClickHouse**: each window statement runs one more scalar sub-select per seek. It reads only the
  window's own rows (typically ~100k) through the version-column skip index. The measured totals above
  already include that cost.
- **Degrades, never breaks**: a backfilled row with an old `partition_column` value pulls the derived
  minimum back. The read then covers more partitions, up to today's full scan, and stays correct.
