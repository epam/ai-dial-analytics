## Context

`CursorPredicates` builds every pipeline-scoped window. That covers the scan (`ScanService`), the Class-1
probe and `INSERT … SELECT` (`MaterializeService`), and the aggregate refresh probe and dirty-key
sub-select (`AggregateRefreshService`). The same predicate list also keys the grain-join sub-selects, for
example `dial_usage_log_payload … WHERE request_time IN (SELECT request_time FROM dial_usage_log WHERE
<source predicates>)`.

The lower bound is `(toUnixTimestamp64Milli(_ingested_at), toString(event_id)) > (v, id)`. ClickHouse
cannot use that for index analysis, and `_ingested_at` is not in `dial_usage_log`'s sort key
`(project_id, deployment, request_time, trace_id, core_span_id)`. Every window statement therefore reads
the whole table, three times over when it also keys a payload join.

Measured on ClickHouse 26.3.24 against a populated `dial_usage_log`:

- **A plain `_ingested_at >= v` bound prunes nothing under `final=1`.** `idx_ingested_at` (minmax)
  selects a handful of parts, then `use_skip_indexes_if_final_exact_mode=1` expands back to all of them
  (PrimaryKeyExpand).
- **Setting `use_skip_indexes_if_final_exact_mode=0` would prune, but it is unsound here.** It is exact
  only for a lower bound on the version column, and breaks the upper bound, the safety lag and the
  bloom-index membership filters.
- **A data-derived bound on the partition column prunes, and is exact.**
  `request_time >= (SELECT min(request_time) FROM dial_usage_log WHERE _ingested_at >= v SETTINGS final=0)`.
  I replayed the real query_log statements (Class-1 probe + write, plain probe, aggregate scans) 3× each
  with and without it, and every pair returned md5-identical output. The probe/write family dropped from
  tens of millions of rows / hundreds of MiB to a few hundred thousand rows / a few MiB, about 7× faster.
- **A generic `_partition_id IN (SELECT DISTINCT _partition_id …)` form also works, but prunes less.**
  It read several times more rows than the partition-column bound on the same statements.
- **The local floor check passed.** On ClickHouse 25.3.14 and 26.3.21 with 5.1 M synthetic rows, the
  bound read 15–18× fewer rows. Results were identical once the safety-lag horizon was pinned.

The catalog already records `table_definition.partition_column` (`request_time` for `dial_usage_log` and
`dial_usage_log_payload`; optional for API-created sources), so the bound needs no new metadata.

## Goals / Non-Goals

**Goals:**

- A cursor- or `from`-bounded window on a partitioned source reads roughly what its window reaches, not
  the whole table.
- The scan, Class-1, aggregate refresh and grain-join key paths all get it, from one change in one place.
- Not a single observable outcome changes: page rows, cursors, `has_more`, `rows_written`, or written rows.

**Non-Goals:**

- **Unpartitioned enrichments joined into a window** (`usage_client_identity`, ordered by `event_id`,
  no partition). Their side is still read in full. That needs its own keyed-join or partition-alignment
  change.
- **The aggregate refresh's recompute half.** For example, the `sessions` re-aggregation over all
  affected keys is unbounded by design: it must see every row of a dirty key.
- **Upper bounds and the safety lag.** The lower bound is where the read cost is.
- **Cursorless reads with no `from`.** There is nothing to derive the bound from.

## Decisions

### D1. Bound on the catalog `partition_column`, minimum derived in-statement

The predicate is rendered beside the seek:

```sql
"<source>"."<partition_column>" >= (
    SELECT min("<partition_column>") FROM "<source>"
    WHERE "<version>" >= fromUnixTimestamp64Milli(<v>) SETTINGS final=0)
```

**Why it is exact.** The seek admits only rows with version > v, and each of those has a partition value
≥ the minimum over rows with version ≥ v. A row the bound removes therefore has version < v. Under FINAL,
removing such a row can change only a key whose winning version is also < v, and that winner is excluded
by the seek anyway. The argument does not need the partition column to be in the sort key. It needs only
that the seek stays in the statement, which it does.

> **Correction (post-archive review, 2026-10-09).** That last claim is wrong. The bound prunes parts
> *before* FINAL collapses a key, so "the winner is excluded anyway" holds only if the engine ranks versions
> by the cursor's version column, or if every version of a key shares one partition value. An
> `upsert_by_key` source collapses on `_updated_at`. If its cursor orders by another timestamp and it is
> partitioned outside its key, pruning an old-cursor-version winner hands the key back to the row it
> superseded. `ScanSourceMetadata` therefore renders the bound only for partition-local sources and for
> sources whose version column is `_updated_at`. The requirement in `enrichment-runtime` states this.

**Why the inner `SETTINGS final=0` is mandatory.** Without it, the inner read inherits `final=1` and
re-expands to the full table.

**Why it prunes.** The partition column is the argument of the partition expression, so ClickHouse keeps
a per-part minmax on it and prunes partitions, and inside them parts. The scalar sub-select is evaluated
before index analysis. The inner read itself prunes through the version column's minmax skip index
(`idx_ingested_at`), reading only the window's rows.

**Alternatives rejected:**

- **Fixed lag (`request_time >= v - 1 day`).** Unsound. Rows can arrive more than a day late,
  and backfill or re-ingest can be arbitrarily old.
- **`use_skip_indexes_if_final_exact_mode=0`.** Unsound for the other predicates, as described in Context.
- **Hardcoding `request_time`.** Correct for both seeded sources, but it reintroduces the per-source
  branch `ScanSourceMetadata` was built to remove.
- **Generic `_partition_id IN (…)`.** Needs no catalog field, but prunes only to whole partitions, so it
  was measured ~3.5× weaker on the payload-joining family. The catalog already has the column, so the
  generality buys nothing.
- **Deriving the minimum in Java with a separate query.** It would cost a round trip per statement, and
  for the Class-1 path a value fixed outside the statement would let probe and write disagree. In-statement
  derivation keeps "the write runs the same predicates its probe ran" literally true.

### D2. Lives in `CursorPredicates`, and `CursorColumns` carries the source and partition column

`CursorColumns` gains the source table name and the nullable `partition_column`, resolved by
`ScanSourceMetadata.cursorColumns(source)` from the same `table_definition` row it already reads. Both
lower-bound builders then append the bound whenever a partition column is present:

- `seekPast(...)` (both overloads);
- the `from` half of `window(...)`.

No call site changes its predicate list, so the scan, Class-1, aggregate and grain-join paths all pick it
up together. That matters because `CursorPredicates`' contract is that these paths agree literally.

The partition column is **qualified by the source table**, unlike the existing unqualified version and
identity references. Window statements join enrichments that carry a column of the same name
(`dial_usage_log_payload.request_time`), so an unqualified reference would be ambiguous. Inside the
scalar sub-select the names are unqualified, because its own `FROM` is the only scope.

**Alternative rejected:** adding the bound at each call site. It would invite exactly the probe/write
drift the class exists to prevent.

### D3. Applies to every source with a `partition_column`, with no skip-index precondition

API-created sources get no minmax skip index on their version column, so for them the inner sub-select
scans the version column in full. Even so, the statement as a whole reads less than today:

- one narrow column fully;
- a partition-pruned outer read;

against every projected column fully today. Gating on the presence of a skip index would need DDL
introspection for a marginal case, so this design does not gate.

## Risks / Trade-offs

- **[Backfill of old rows pulls the minimum back]** → The read widens toward today's full scan and stays
  correct. The spec scenario "A late row with an old partition value is still read" pins this, and an
  integration test covers it.
- **[A ClickHouse version treats the scalar sub-select differently]** → Verified on 25.3 (the floor) and
  26.3. The integration suite on 26.8 asserts both result identity and pruning (the parts read are fewer
  than the total), so a regression fails the build rather than silently costing reads.
- **[An enrichment with the same column name makes an unqualified reference ambiguous]** → The column is
  rendered qualified (D2), and a test joins payload to prove it.
- **[Rendered SQL changes may trip snapshot or shape tests]** → They are expected. Update them alongside
  the change, and assert byte-identical SQL for an unpartitioned source.
- **[The win is partial for usage_client_identity-joining scans]** → Accepted and stated as a non-goal.
  The measured gain there is 1.3–1.5×.

No new configuration, credential or migration. Deploy is a plain rollout: the bound is added per
statement, so there is no state or cut-over.

## Open Questions

None blocking. Two to observe on a running deployment after rollout:

- the drop in the query_log totals per statement family;
- whether any API source's inner sub-select (no skip index) shows up as noticeable cost.
