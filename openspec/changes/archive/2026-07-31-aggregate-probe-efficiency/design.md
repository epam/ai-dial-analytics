## Context

`AggregateRefreshService.probe` (lines 215–239) builds a jOOQ select of the two cursor expressions,
applies the filter and window predicates, orders by the cursor tuple ascending, limits to
`aggregate.window-limit`, and runs it through `StructuredQueryExecutor.executeScan`. It then uses
`rows.getLast()` for the end tuple and `rows.size() == windowLimit` for `has_more`.

Both facts are aggregates over the limited set. The limit has to stay *inside* whatever replaces it,
because it is what defines the window — the end tuple is "the last row of the first N in cursor order",
not "the maximum row", and those differ whenever the window is capped. That constraint is what makes this
slightly more than a one-line change.

## Goals / Non-Goals

**Goals:**

- The probe's transferred result is one row, whatever the window contains.
- The window it selects is bit-for-bit the one the current code selects.
- The bounded-window path gains regression coverage, which it has none of today.

**Non-Goals:**

- Changing the window limit, the tick cadence, or their defaults.
- Touching the two other `executeScan` callers (the preview sample and the enrichment scan path).
- Any change to the compiled `INSERT … SELECT`.

## Decisions

### Aggregate over the limited subquery, in one statement

Wrap the existing ordered-and-limited select as a subquery and aggregate over it: the count of rows it
contains, and the maximum of the cursor tuple. Because the subquery is already ordered and limited to the
window, its maximum tuple *is* its last row in cursor order, and its count compared against the limit *is*
`has_more`. One statement, one row back, and the window definition stays where it already is rather than
being restated.

Using the tuple as a single comparable value keeps the version/identity tie-break exactly as the ordering
defines it; comparing the two columns independently would pick a maximum version and a maximum identity
that need not come from the same row, which is a genuinely wrong window and the main trap in this change.

### Detect the empty window by the returned count, not by an empty result

Today's probe reports "nothing new" as an **empty row list**. An aggregate does not behave that way: a
`count`/`max` over an empty subquery returns **one row**, carrying `0` and a null (or type-default) tuple.
So `rows.isEmpty()` — the current no-op test at `AggregateRefreshService:231` — becomes permanently false,
and the empty window stops being detected. The refresh would then pin its window to a null end tuple and
write from a nonsensical range instead of doing nothing.

The test has to become `count == 0`. This is the one place where the substitution is not
behaviour-preserving by construction, and it fails in the direction that writes rather than the direction
that errors, so it needs its own assertion rather than being covered by the general window-identity test.

*Alternative considered — reverse the order and take the first row (`ORDER BY … DESC LIMIT 1`).* Cheaper
to read, but it cannot be combined with the limit: reversing selects the last N of the input, not the last
row of the first N. It would need the limited subquery anyway, and then a second statement for the count.

*Alternative considered — two statements, one for the tuple and one for the count.* Correct, and simpler
per statement, but it probes the input twice and opens a gap in which the two could disagree. One
statement is both cheaper and tighter.

### Keep the returned `Window` record and its call site unchanged

The change is contained to how `Window` is populated. `refresh` already treats it as an opaque
`(version, identity, hasMore)`, so nothing downstream needs to know, and the diff stays reviewable as a
behaviour-preserving substitution.

### Fix the "cheap two-column keyset read" comment in the same change

The comment at `AggregateRefreshService:34` is why this cost survived review: it is true per row and
false in aggregate, and it reads as a claim that the probe is already bounded. Correcting it — noting
what it now does and that it previously streamed its window — is part of the fix, not tidying.

## Risks / Trade-offs

- **Getting the end tuple wrong changes which rows a refresh sees.** A subtly different window is not a
  visible failure: it silently shifts what the next run reads. → The window-identity test in the task list
  asserts the same cursor and the same written rows before and after; the tuple-comparison decision above
  is the specific trap it guards.
- **`max` over a tuple is engine-specific in how it renders.** → It is a single expression built by the
  same translator the rest of the compiler uses, and the integration test executes it against real
  ClickHouse rather than asserting on rendered SQL.
- **The aggregate still scans the window server-side.** This change removes the transfer, not the read;
  the probe remains O(window) inside ClickHouse. → That read is inherent to choosing a window and is what
  the limit already bounds. The transfer is the part that was unbounded in a dimension nothing was
  watching.

## Migration Plan

Pure substitution: no config, no schema, no API, no stored-state change. A deployment sees identical
outcomes with less traffic. Rollback is a code revert.

## Open Questions

None.
