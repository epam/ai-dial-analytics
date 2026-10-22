## 1. Surface the sample's statement

- [x] 1.1 Render the compiled sample query to SQL inside `AggregateRefreshService.preview` and add it to
      `PreviewResult` alongside the existing statement and rows
- [x] 1.2 Add the matching member to `PipelineResponses.PreviewDto` and map it in `PipelineDataController`,
      keeping the existing `statement` field's name and meaning unchanged so no client breaks

## 2. Make the response self-describing

- [x] 2.1 Reword the `@Operation` description on `POST /v1/pipelines/{name}/preview` so it states that the
      statement is windowed and the sample is not, replacing the current "sample of the rows it would
      produce" wording that implies one produced the other
- [x] 2.2 Add `@Schema` descriptions to both statement members of `PreviewDto` naming which question each
      answers

## 3. Record the rationale in code

- [x] 3.1 Extend the Javadoc at `AggregateRefreshService:157-160` so it explains both halves together —
      the existing comment justifies the unwindowed sample but never mentions that the returned statement
      *is* windowed, which is the pairing that confuses a reader
- [x] 3.2 Note in the same place that the divergence is total for a drained pipeline, so a future reader
      comparing them recognises it as expected rather than as a regression

## 4. Tests

- [x] 4.1 Integration test: a preview response carries both statements, and they differ for a pipeline
      with a stored cursor
- [x] 4.2 Integration test: the drained-pipeline case — after refreshing to exhaustion, the refresh
      statement carries a cursor seek that returns nothing while the sample still reports full-input
      totals. This is the exact scenario that read as a bug during testing, so it is the one worth pinning
- [x] 4.3 Integration test: the sample statement, executed as-is, reproduces the sampled rows — otherwise
      the new field is decorative

## 5. Spec and close-out

- [x] 5.1 Confirm the shipped response matches `specs/aggregate-pipeline/spec.md` in this change, in
      particular that the sample was not quietly windowed to make the halves agree
- [x] 5.2 Run `./gradlew clean build` (Docker required for integration tests) and confirm it is green
