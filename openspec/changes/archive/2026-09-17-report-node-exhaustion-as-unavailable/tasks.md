## 1. Pin what the real server actually says

- [x] 1.1 Add `integration/ClickHouseMemoryLimitMessageIntegrationTest` — the pin for the **per-query**
      form, and the thing that makes a future ClickHouse rename fail the build rather than silently revert
      behaviour. Run a tracked read with `max_memory_usage` at `1KB` (the shape in
      `QueryBudgetIntegrationTest` plus the 262144-byte payload fixture is already proven to trip the
      tracker; a two-row projection stays under `min_untracked_memory` and never reaches it), walk to the
      `ServerException` cause and assert: code is 241, the message does **not** carry the node-wide marker,
      it renders `maximum: 1.00 KiB`, and `ClickHouseBudget.translate` on it yields the budget refusal.
      **The first pass dropped two of those assertions** — it checked only `contains("maximum:")` and a
      `doesNotContain("(total)")` that is vacuous on 26.3, where the alternatives read `User …`/`Total …`
      and contain no `(total)` at all. Both restored in review: the anchored head
      `DB::Exception: Query memory limit exceeded` and the exact `maximum: 1.00 KiB`. The query also shrank
      from `numbers(50000000)` to `numbers(100000)`, which trips the same tracker.
- [x] 1.2 **Not built, deliberately** — see 1.3. `max_server_memory_usage` is server-only (a query setting
      answers `115 UNKNOWN_SETTING`), and the allow-list adopted in 2.1 never matches on the node-wide
      wording, so a container that produced it would pin nothing the code reads.
- [x] 1.3 Escape hatch taken and recorded in `design.md`: the per-query spelling is machine-pinned by 1.1
      against whatever server the suite runs, and the node-wide wording is inferred from the observed
      `Query`/`User` pattern rather than claimed as observed.

## 2. Attribute the refusal (the P1)

- [x] 2.1 In `clickhouse/ClickHouseBudget.java`, split the 241 arm of `translate`: claim the budget's
      ceiling only when the message **positively identifies the statement's own ceiling**, anchored
      immediately after the driver's `DB::Exception: ` prefix; otherwise return `ex` unchanged so the
      pre-existing `ClickHouseUnavailableException` (503) stands. Leave the 159 arm exactly as it is.
- [x] 2.2 Put the one spelling in a named constant with a comment recording the version it was verified
      against and that the surrounding sentence churns. This constant is what a ClickHouse upgrade must
      re-check; there is no marker *set* to maintain, because the allow-list needs no knowledge of the
      alternatives.
- [x] 2.3 Extend `clickhouse/ClickHouseBudgetTest` — it has **no `translate` coverage at all** today, which
      is where the whole decision lives. Cover, using the verbatim strings observed in 1.1: our own ceiling
      → refusal; the per-user form → returns the original exception (`isSameAs`); a node-wide form →
      `isSameAs`; `<Unreadable error message>` → `isSameAs`; a message that embeds our spelling later in
      the text → `isSameAs` (the anchoring test); 159 unchanged in both directions.
- [x] 2.4 Add the node-wide sibling case to `service/query/QueryBudgetTest` (beside `:82`) and
      `service/pipeline/PreviewBudgetTest` (beside `:76`), and note in their javadoc that their hand-written
      `ServerException` strings pin nothing about the real server — task 1.1 is what does.
- [x] 2.5 Add `service/pipeline/PipelineReadBudgetTest` — it does not exist, and this is the translation
      with the dead-letter blast radius.
- [x] 2.6 Add the node-wide 241 to `clickhouse/ClickHouseFailuresTest` (beside `:61`) asserting it is
      **still** `isUnavailable`, with a comment that this classifier never reads the message. The 503 the
      budget now falls back to is the one this class assigns, so the two must stay in their documented order.

## 3. The narrowing message and the wasted probe

- [x] 3.1 In `service/enrichment/MaterializeService.narrowing` (`:379-384`), use the existing `narrowed`
      flag (`:373`) to distinguish a write that narrowed and then ran out of budget from one that never got
      to narrow. The current message claims "across narrowing retries" in both cases.
- [x] 3.2 Check the remaining budget **before** the halve-and-re-probe at `:391-392`, so a refusal that has
      already spent the budget does not pay for a probe whose result cannot be used.
- [x] 3.3 Add to `service/enrichment/MaterializeNarrowingTest`: a `WindowWrite` throwing
      `ClickHouseUnavailableException` propagates it and calls `WindowProbe` **zero** times — this is the
      control-flow change from task 2.1 and is currently uncovered. Extend `:134` to assert the new
      no-narrowing-attempted phrasing rather than just `"execution budget"`.

## 4. Stop the driver re-executing a refusal (separable — see design.md)

- [x] 4.1 In `clickhouse/ClickHouseClientWrapper.buildClient` (`:91-99`), stop the driver retrying a
      server-side refusal: set max retries to 0, or drop `ServerRetryable` from the fault causes. Record
      which and why in a comment — the default is `retry=3` and it applies to the deterministic 422 too.
      **Scope was wider than this task recorded, and initially incomplete.** ADAS opens *four* clients;
      the first pass reached two. Review caught `UsageLogWriter` and `CatalogRowWriter` still replaying —
      the ingest path, where the batch is largest. All four now share `ClickHouseClients.builder(…)`, so
      the next cross-cutting setting cannot reach only some of them. Dropping `ServerRetryable` also gave
      up driver retry for codes it covered that `isResourceExhaustion` did not recognise, so
      `UNEXPECTED_END_OF_FILE` (3) and `FILE_DOESNT_EXIST` (107) — a merge completing under a read — were
      added there rather than left to surface as `400 bad_request`.
- [x] 4.2 Add a test that a server-side resource refusal reaches the caller after exactly one execution.
      **Initially checked off without the test existing** — caught in review. Now real:
      `ClickHouseMemoryLimitMessageIntegrationTest.refusedStatementReachesTheServerOnce` counts
      `system.query_log` exception rows for a per-run marker, and was confirmed to fail (4 executions)
      when `ServerRetryable` is restored. The two unit tests around it can only assert the constant.
- [x] 4.3 If the review prefers a narrower blast radius, lift this group into its own change rather than
      dropping it; it touches every statement, not only budgeted ones.

## 5. Docs and the deferral lists

- [x] 5.1 `docs/sql-reference.md:476-479` presents "query budget exceeded" as flatly deterministic. Add the
      distinction, for both the REST and Flight columns — Flight moves to `UNAVAILABLE` automatically via
      `FlightErrorMapper`'s type dispatch, so the table is the only thing to change there.
- [x] 5.2 `docs/clickhouse-server.md` — record that the 241 message text is now load-bearing, which
      versions it was verified against, and that a server upgrade must re-check the constant from task 2.2.
- [x] 5.3 `docs/release-0.2-upgrade.md` — extend the existing "ClickHouse resource exhaustion → 503 (was
      400)" row, or add one, so a caller keying on `422` for this condition is warned.
- [x] 5.4 No configuration property is added or renamed, so `docs/configuration.md` gains nothing.
- [x] 5.5 Move items **4** and **5** to the resolved follow-ups, and
      record the new driver-retry finding there if task 4 is split out rather than shipped here.
- [x] 5.6 In the runner repo, close **R1** as documentation only: narrow the prose at
      `openspec/specs/resilience-and-dlq/spec.md:250-253` and the javadoc at
      `worker/RetryableStatus.java:14-20`, which concede a flaw that no longer exists, and add the
      `RetryableStatusTest` that does not exist. **No runner behaviour change.**

## 6. Verification

- [x] 6.1 Confirm `QueryBudgetIntegrationTest` and `AggregatePreviewBudgetIntegrationTest` still pass
      untouched — they provoke the per-query form only, so if either starts failing the per-query matcher
      is wrong.
- [x] 6.2 Confirm `StructuredQueryControllerTest:84` and `SqlQueryControllerTest:108` still pass; they
      assert `clickhouse_unavailable` for connectivity and neither asserts 241 → 422.
- [x] 6.3 Run `./gradlew clean build` and confirm it passes. Docker must be available for the integration
      tests.
