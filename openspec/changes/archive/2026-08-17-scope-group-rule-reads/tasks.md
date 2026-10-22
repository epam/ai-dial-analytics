## 1. Scan projection for a group rule

- [x] 1.1 In `ScanService#scan`, collect the rule's bound input-binding columns into their own set
  instead of adding them straight to `projection`. Add them to `projection` only when
  `rule.getTriggerKind() != TriggerKind.GROUP`.
- [x] 1.2 Add that set to `guarded` unconditionally, alongside `signal.columns()`, so
  `sensitiveGuard.enforce` still refuses a non-entitled caller scanning a group rule that binds a
  sensitive column. This is the load-bearing half of the change — verify it with a test, not by reading.
- [x] 1.3 Update the `ScanService` class javadoc, which states the projection pushdown as
  `{filter + bound-binding columns} ∪ {grain} ∪ {cursor columns}` with no group exemption, and record
  why the guarded set and the projection are no longer the same set.

## 2. Group assembly retains the newest members

- [x] 2.1 In `GroupFetchService#buildAssembly`, order by the projected cursor expressions **descending**
  so the query selects the newest window. Leave every other clause (group-key predicate, rule filter,
  safety-lag, `limit + 1`) untouched.
- [x] 2.2 In `GroupFetchService#trim`, keep the accumulate-and-stop loop as it is — it now runs over a
  newest-first list — and reverse the retained members before returning, so the response stays ascending
  in the source's total order. Confirm the `truncated` flag is still set by the row cap (`size > limit`)
  and by the byte cap independently.
- [x] 2.3 Update the `GroupFetchService` class javadoc and `#trim`'s javadoc: state that the caps retain
  the most recent members, that the response order is unchanged, and that selection order and response
  order are deliberately separate. Name the runner-side counterpart (`GroupInputAssembler` trims from
  the front) so the two ends stay documented together.

## 3. Tests

- [x] 3.1 `ScanService` test: a `group` rule binding a source column returns pages **without** that
  column, while the `group_by` value, cursor columns and any `filter_sql` column are present.
- [x] 3.2 `ScanService` test: the same bindings on an `on_ingest` rule still project the bound columns —
  the row lane is unchanged.
- [x] 3.3 `ScanService` test: a caller who may not see sensitive columns scanning a `group` rule that
  binds a `sensitive` column (referenced by nothing else) is still rejected with 403
  `sensitive_column_not_entitled`.
- [x] 3.4 `GroupFetchService` test: a group with more members than the row limit returns the **newest**
  `limit` members, `truncated: true`, ordered ascending.
- [x] 3.5 `GroupFetchService` test: a group trimmed by the byte cap returns the newest members that fit,
  `truncated: true`, ordered ascending.
- [x] 3.6 `GroupFetchService` test: a group within both caps is returned whole, ascending, with
  `truncated: false` — the untrimmed path is unchanged.
- [x] 3.7 `GroupFetchService` test: a group whose newest member alone exceeds the byte cap returns that
  member (not an empty page) with `truncated: true`.
- [x] 3.8 Check the existing enrichment scan/assembly tests for ones that assert the old head-retaining
  behaviour or a group rule's projected bindings, and update them deliberately rather than by
  coincidence.

## 4. Dependent plan

- [x] 4.1 In the dependent conversation-insights enrichment plan, drop the
  `request_body_bytes < 131072` clause from `filter_sql` — it existed only to bound scan pages — and
  restore `batch_scan_limit` to a normal value, recomputing the coverage, transfer and cost figures that
  the ceiling drove.
- [x] 4.2 Retire the scan-page-size risk and the `truncated: false` blind spot from that plan, and note that the plan now
  requires this change to have shipped first.

## 5. Verification

- [x] 5.1 `openspec validate scope-group-rule-reads --strict` passes.
- [x] 5.2 `./gradlew clean build` passes (Docker required for the Testcontainers integration tests).
