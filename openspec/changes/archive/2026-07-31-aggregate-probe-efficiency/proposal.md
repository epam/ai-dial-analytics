## Why

Every aggregate refresh begins with a probe that fixes the window end. It needs exactly two facts: the
last cursor tuple in the window, and whether the window filled its limit. It currently obtains them by
**streaming the entire window to the service** and reading `rows.getLast()` and `rows.size()`.

Measured live: one probe returned **65,307 rows** to ADAS. The bound is `aggregate.window-limit`, default
**100,000**, so each probe can move up to 100k rows over the wire — per pipeline, per tick, on a default
`aggregate.tick-cron` of every minute. The two facts extracted from them are one row's worth of data.

The class doing this describes itself as "a cheap two-column keyset read" (`AggregateRefreshService:34`).
Per row it is; in aggregate it is not, and the comment is why the cost is easy to miss. It also sits
badly beside the sibling write path's stated principle — "the derived values are computed inside
ClickHouse and no row payload ever crosses the wire" — which is exactly the property the probe gives up.

This costs nothing in correctness. It costs bandwidth, service heap, and the headroom to run more
pipelines on a shorter tick, which is the direction the capability is built to go.

## What Changes

- The probe derives its window end and its more-work-remains flag **inside ClickHouse**, returning a
  single row instead of the window's contents.
- The window semantics are unchanged: the same predicates, the same ordering, the same limit, the same
  end tuple, the same `has_more`. A refresh must derive the identical window before and after.
- The misleading "cheap two-column keyset read" comment is corrected to say what it now is, and what it
  previously was.
- Test coverage is added for the bounded-window path, which the suite does not currently exercise at all:
  its window limit is never filled, so `has_more` and the split-bucket recompute have no regression guard.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `aggregate-pipeline`: gains a requirement that the probe's result size is independent of its window's
  size. The existing requirements — the probe "fixes the window end over the input's cursor tuple", and
  "a run that fills its limit SHALL report that more work remains" — remain true word for word and are
  not edited; what they lack is any statement about *cost*, which is why the current implementation
  satisfies them while streaming 100k rows. Without the new requirement this fix is an implementation
  detail a later refactor can undo silently.

## Impact

- **Code**: `AggregateRefreshService.probe` and the `Window` record it builds; the class Javadoc.
- **API**: none. `rows_written`, `has_more` and `lag_seconds` are unchanged for every input.
- **Performance**: up to `aggregate.window-limit` rows per probe removed from the wire and from service
  heap. Most visible on a busy deployment with several pipelines on a short tick, which is the
  configuration the change is worth doing for.
- **Tests**: `AggregatePipelineIntegrationTest` gains bounded-window coverage. During live testing a
  45,000-row bucket split across two windows by the row limit correctly ended at its full total — the
  strongest evidence that the recompute-in-full contract holds under a split bucket, and it has no test.
- **Not affected**: the compiled `INSERT … SELECT`, the cursor's stored shape, governance, scheduling,
  and every other capability.
