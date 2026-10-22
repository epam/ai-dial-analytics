## 1. Capture the current behaviour before changing it

- [x] 1.1 Add an integration test that drives a refresh whose window **fills** `aggregate.window-limit`
      (lower the limit in test config rather than seeding a huge input), asserting `has_more` is true and
      that draining to `has_more: false` reaches the same rollup as a single unbounded run
- [x] 1.2 Add the split-bucket case to that test: a single group key whose rows straddle the window
      boundary must end at its **full** total, not the last window's slice. This is the recompute-in-full
      contract under the one condition that actually stresses it, and it is currently untested
- [x] 1.3 Record the stored cursor and written rows from these runs as the baseline the substitution must
      reproduce

## 2. Replace the probe

- [x] 2.1 Rewrite `AggregateRefreshService.probe` to aggregate over the existing ordered-and-limited
      select as a subquery, returning one row: the row count, and the maximum of the cursor tuple
- [x] 2.2 Compare the cursor tuple as a **single tuple**, not as two independent maxima — a maximum
      version and a maximum identity drawn from different rows is a different window, and the failure is
      silent
- [x] 2.3 Derive `has_more` from the returned count against `aggregate.window-limit`, preserving the
      current fill-the-limit semantics exactly
- [x] 2.4 Replace the empty-window test: an aggregate over an empty subquery returns **one row** carrying
      `count = 0`, so the existing `rows.isEmpty()` no-op check becomes permanently false and the refresh
      would pin its window to a null end tuple and write from it. Detect the empty window by `count == 0`
- [x] 2.5 Keep the `Window` record and `refresh`'s use of it unchanged, so the diff reads as a
      behaviour-preserving substitution

## 3. Correct the record

- [x] 3.1 Fix the "cheap two-column keyset read" claim in the class Javadoc
      (`AggregateRefreshService:34`) — state what the probe does now, and that it previously transferred
      its whole window, so the cost that hid behind that phrase cannot hide again
- [x] 3.2 Note the tuple-comparison requirement at the code site, since it is the one part of this that
      is easy to "simplify" into a bug later

## 4. Verify the substitution

- [x] 4.1 Re-run the tests from group 1 against the new probe: identical stored cursor, identical
      `rows_written`, identical `has_more`, identical rows in the target
- [x] 4.2 Assert the probe returns a single row — the point of the change, and the only thing a future
      regression would quietly undo
- [x] 4.3 Confirm the empty-window no-op still holds (probe matches nothing → no write, cursor unmoved).
      Assert it directly rather than inferring it from a green suite: this is the one case the aggregate
      form does not preserve for free, and it fails by writing rather than by erroring
- [x] 4.4 Confirm the other five `executeScan` callers (preview sample, `GroupFetchService`,
      `ScanService`, `MaterializeService` probe and select) are untouched

## 5. Spec and close-out

- [x] 5.1 Confirm the shipped behaviour matches `specs/aggregate-pipeline/spec.md` in this change
- [x] 5.2 Run `./gradlew clean build` (Docker required for integration tests) and confirm it is green
