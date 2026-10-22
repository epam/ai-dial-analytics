## Why

The `aggregate-pipeline` spec states the recompute's pruning bound as though it were a property of
*refresh*. It is a property of **time-bucketed** refresh: `AggregateCompiler.bucketBound` returns
`List.of()` the moment a declaration carries no `trunc`, so a grain built from plain columns only gets no
bound at all and its recompute reads the whole input on every tick. The first such rollup is now live —
`conversations_rollup` groups by `chat_id`, which is not a prefix of `dial_usage_log`'s
`ORDER BY (project_id, deployment, request_time, trace_id, core_span_id)` — and nothing in the spec, the
configuration docs, or the compiler tells an operator that the grain they picked decides whether a refresh
costs a scan or a seek.

Nothing is broken: the rollup is correct and the drain completed. The gap is that the cost is invisible at
declaration time, when it is the only moment it can still be chosen.

## What Changes

- State in the `aggregate-pipeline` spec what happens when a declaration carries **no** time bucket: no
  prunable bound exists and the recompute reads the whole input each refresh.
- Add **grain guidance** next to it: a plain group key SHOULD be a primary-key prefix of the input, or carry
  an index that prunes usefully at the expected dirty-set size; a key that is neither costs a full input
  read per refresh.
- Record **why the obvious bounds are wrong**, so the gap is not mistaken for a bound waiting to be written.
  The recompute must see every row of every dirty key; bounding it on `_ingested_at` excludes rows ingested
  before the window, and bounding it on the event-time column excludes rows that happened outside the
  window's span. Both produce plausible numbers with nothing recording the loss. A time bucket is
  bound-able only because the bound is *implied by the key itself*.
- Point operators at the guidance from `docs/configuration.md`, and carry the same note in
  `AggregateCompiler.bucketBound`'s javadoc at the `return List.of()` branch.

No behaviour changes. No code changes beyond a javadoc.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `aggregate-pipeline`: the requirement "Scheduled refresh recomputes every affected group key in full"
  currently describes the bucket bound without saying what applies in its absence. It gains the
  no-bucket case, the grain guidance, and a scenario covering a grain with no time bucket.

## Impact

- `openspec/specs/aggregate-pipeline/spec.md` — one requirement extended, one scenario added.
- `docs/configuration.md` — one sentence in the aggregate-pipelines section.
- `src/main/java/com/epam/aidial/analytics/service/pipeline/AggregateCompiler.java` — javadoc only, on
  `bucketBound`.

No API, schema, migration, configuration, or dependency impact. No existing rollup changes.
