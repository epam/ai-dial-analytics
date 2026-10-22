## 1. Spec

- [x] 1.1 In `openspec/specs/aggregate-pipeline/spec.md`, requirement "Scheduled refresh recomputes every
      affected group key in full": after the "When the declaration carries a time bucket…" paragraph, add
      the no-bucket paragraph — no such bound exists, the recompute reads the whole input, and the bucket
      bound exists only because a time bucket implies it.
- [x] 1.2 Add the grain guidance paragraph: a plain group key SHOULD be a primary-key prefix of the input
      or carry an index that prunes usefully at the expected dirty-set size; a key that is neither costs a
      full input read per refresh; narrowing the `filter` on a primary-key-prefix column prunes both
      halves of the write.
- [x] 1.3 Add the trap paragraph: the absence SHALL NOT be closed by bounding the recompute on a time
      column, naming both wrong candidates (ingest-time axis excludes a key's earlier-ingested rows;
      event-time column excludes a key's rows outside the window's span) and why the loss is silent.
- [x] 1.4 Add the scenario "A grain with no time bucket has no prunable bound" immediately after
      "A time-bucketed refresh does not read the whole input", using exactly four hashtags.

## 2. Operator and developer docs

- [x] 2.1 In `docs/configuration.md`, aggregate-pipelines section (around the paragraph on
      `AGGREGATE_TICK_CRON` and refresh cost), add one sentence pointing operators at the grain guidance:
      a rollup grouped by a plain column that is not a primary-key prefix of its input reads the whole
      input each refresh.
- [x] 2.2 In `AggregateCompiler.bucketBound`'s javadoc, add the note at the `return List.of()` branch —
      what an empty bound means, that it follows from the grain, and that neither `_ingested_at` nor the
      event-time column is a valid substitute.

## 3. Verify

- [x] 3.1 `openspec validate document-unbucketed-grain-cost` passes.
- [x] 3.2 `./gradlew clean build`
      is green (javadoc-only code change, but checkstyle runs on it).
- [x] 3.3 Confirm no behavioural change shipped: `git diff` touches only the spec, `docs/configuration.md`,
      and `AggregateCompiler` comments.
