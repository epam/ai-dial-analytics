## 0. Make the catalog tell the truth about nullability

- [x] 0.1 Add a Flyway migration setting `column_mapping.nullable = TRUE` for the eleven `dial_usage_log`
      columns whose ClickHouse type is `Nullable(...)`, per `V1__create_dial_usage_log.sql`. Without this
      the rule below is inert on the exact table that produced the finding: the flag defaults to `FALSE`
      and that table's seed predates it
- [x] 0.2 Note in the migration that this is a `column_mapping` update only — nullability is fixed at DDL,
      so nothing materializes as an `ALTER` — and that the physical type is the source of truth the
      catalog is being aligned to
- [x] 0.3 Account for the visible consequence: schema discovery publishes the flag, so those columns start
      reporting `nullable: true`. That is the value they should always have reported

## 1. The rule

- [x] 1.1 Add a nullable-measure check to `AggregateSpecValidator`, sited next to the existing
      fractional-measure rule it is a sibling of, using the input binding and target column the validator
      already resolves. `ColumnMappingEntity` carries `nullable`, but the validator's `Columns` view
      exposes only `typeOf`/`isSensitive` — add the matching accessor rather than reaching past it
- [x] 1.2 Derive nullability from the input column, exempting `count` explicitly (it may have no input
      column at all) rather than letting it fall through the input rule
- [x] 1.3 Word the 422 message to name the target column, the nullable input column, and the fact that the
      remedy is a table change — the target column's nullability is fixed at DDL, so an operator who reads
      it as "edit the pipeline" will go round in circles

## 2. Execution-path enforcement

- [x] 2.1 Confirm the rule is re-applied on the refresh path (it should come for free via the shared
      validator call in `AggregateRefreshService`), and add a test proving it — this is what catches a
      column that turns nullable after registration
- [x] 2.2 Confirm a refusal on that path records `last_error` and leaves the cursor unmoved, like every
      other refresh failure

## 3. Tests

- [x] 3.1 Unit tests in `AggregateSpecValidatorTest` for the matrix: nullable input → non-nullable target
      (rejected); nullable input → nullable target (accepted); non-nullable input → non-nullable target
      (accepted); `count` → non-nullable target (accepted)
- [x] 3.2 Integration test reproducing the original finding: a group whose every input value is NULL must
      not silently store `0`. Assert the declaration is refused; then, with a nullable target column,
      assert the bucket stores NULL
- [x] 3.3 Unit test for the execution-path re-application (input column becomes nullable after
      registration → refresh refused)

- [x] 3.4 Fix the existing `AggregatePipelineIntegrationTest` rollup, whose `tokens` column sums the
      now-correctly-nullable `total_tokens` into a non-nullable target — the rule refuses it, which is the
      rule working. Declare that target column nullable

## 4. Spec and close-out

- [x] 4.1 Confirm the shipped behaviour matches `specs/aggregate-pipeline/spec.md` and
      `specs/table-management/spec.md` in this change
- [x] 4.2 Run `./gradlew clean build` (Docker required for integration tests) and confirm it is green
