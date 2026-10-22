## 1. Configuration

- [x] 1.1 Add a `preview` budget group to `AggregateProperties` — execution timeout, memory ceiling, and
      spill threshold — defaulting to 30s / 2 GiB / 1 GiB, and validating that the spill threshold is
      strictly below the memory ceiling, since spilling only helps if it triggers first
- [x] 1.2 Add the matching `aggregate.preview.*` keys to `application.yml` as bare `${ENV:default}` lines
      with no explanatory comments, per project convention
- [x] 1.3 Document the new `AGGREGATE_PREVIEW_*` variables in `docs/configuration.md` (the only place
      descriptions live), including the ceiling-must-sit-below-container-memory constraint and the
      spill-threshold/disk-space relationship from the design

## 2. Executor plumbing

- [x] 2.1 Add an `executeScan` overload on `StructuredQueryExecutor` taking caller-supplied
      `QuerySettings`, leaving the existing single-argument overload and its **five** other callers
      (refresh probe, `GroupFetchService`, `ScanService`, `MaterializeService` probe and select)
      behaviourally unchanged
- [x] 2.2 Add a unit test asserting the merge keeps `final = 1` in place alongside the caller's settings —
      the budget must add to the standing deduplication, not replace it. A budget that silently dropped
      `final = 1` would change what the preview *reports*, not just what it costs

## 3. Failure mapping

- [x] 3.1 Add a `PipelinePreviewBudgetExceededException` (pipeline service package) carrying which budget
      was exceeded
- [x] 3.2 Translate ClickHouse `TIMEOUT_EXCEEDED` / `MEMORY_LIMIT_EXCEEDED` server error codes into that
      exception, matching on the numeric code and letting anything unrecognised fall through to the
      existing `ClickHouseQueryException` → 400 path
- [x] 3.3 Add an `@ExceptionHandler` to `DefaultExceptionHandler` returning 422 with the stable
      budget-exceeded `error` code and a message naming the exceeded budget, echoing no driver text
- [x] 3.4 Unit-test the mapping, including the fallback: an unrecognised ClickHouse error must still
      surface as it does today, not as a budget refusal

## 4. Wire the budget into preview

- [x] 4.1 Build the budget `QuerySettings` from `AggregateProperties` and pass them from
      `AggregateRefreshService.preview` to the new `executeScan` overload — the sample query only, never
      the probe and never the rendered refresh statement
- [x] 4.2 Update the Javadoc at `AggregateRefreshService:157-160` so it records that the sample is
      unwindowed *by design and bounded by budget instead*, which is the pairing a future reader needs

## 5. Tests

- [x] 5.1 Integration test: a preview that fits its budget returns the statement and sample unchanged,
      with the sample still computed over the whole input (guards against a "fix" that quietly windows it)
- [x] 5.2 Integration test: with the budget configured near zero, a preview is refused with 422 and the
      budget-exceeded code, and no partial sample is returned
- [x] 5.3 Integration test: a refresh whose recompute exceeds the preview budget still succeeds, pinning
      that the budget did not leak onto the write path
- [x] 5.4 Verify the failure is still distinguishable from an outage — the existing
      ClickHouse-unavailable preview scenario must keep returning 503 `clickhouse_unavailable`

## 6. Spec and close-out

- [x] 6.1 Confirm the shipped behaviour matches `specs/aggregate-pipeline/spec.md` in this change,
      including that no sampling or windowing of the preview input was introduced
- [x] 6.2 Run `./gradlew clean build` (Docker required for integration tests) and confirm it is green
