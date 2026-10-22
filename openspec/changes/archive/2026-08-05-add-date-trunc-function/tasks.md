## 1. DSL translator and catalog

- [x] 1.1 `ExprTranslator`: add `DATE_TRUNC_UNITS` (second, minute, hour, day, week, month, quarter,
      year → SQL keyword), a `dateTrunc(fn, args, bindings)` branch rendering
      `toStartOfInterval({0}, INTERVAL 1 <KEYWORD>, 'UTC')`, and the `toFunction` dispatch case. Reuse the
      unit-resolution shape of `dateBinUnit` (allowlisted keyword, case-insensitive, message naming the
      accepted set); arity is exactly two.
- [x] 1.2 `QueryFunctionCatalog`: add `DATE_TRUNC_ALLOWED_UNITS` (alphabetical, for a stable wire order)
      and the `scalar("date_trunc", "date_trunc(unit, timestamp)", TIMESTAMP, …)` descriptor with a
      `STRING_LITERAL` unit constrained to that set plus an `EXPRESSION` timestamp. Insert it in
      alphabetical position, immediately after `date_bin`.
- [x] 1.3 `QueryFunctionCatalogParityTest`: add `advertisedDateTruncUnitsEqualTranslatorUnits`, mirroring
      the existing `date_bin` assertion. The generic descriptor tests need no change.

## 2. SQL surface

- [x] 2.1 `SqlQueryOperators`: add a `DATE_TRUNC` `SqlBasicFunction` over
      `(SqlTypeFamily.CHARACTER, SqlTypeFamily.TIMESTAMP)` returning ARG1 nullable, register it in
      `TABLE`, and extend the class javadoc's bullet list. Note in a comment that the chain order is
      load-bearing here — Calcite's own `DATE_TRUNC` takes its operands the other way round.
- [x] 2.2 `RexToExprTranslator.callExpr`: `case "DATE_TRUNC" -> fn("date_trunc", call, 2)`.
- [x] 2.3 `RelToStructuredQueryTranslator.fnKind`: fold `date_trunc` into the existing
      `case "date_bin" -> ResultColumnKind.TIMESTAMP`.
- [x] 2.4 Verify the DSL→SQL round trip: `StructuredQueryToSqlRenderer` emits `date_trunc('month', "col")`
      generically already, so add a renderer test plus a front-end test that the rendered text re-parses.

## 3. Month buckets in the aggregate pipeline

- [x] 3.1 `TruncUnit`: `MONTH("month", "MONTH", EnumSet.of(QueryFieldType.DATE, QueryFieldType.TIMESTAMP))`.
      Rewrite the class javadoc — drop the "declared but not supported" section, state that buckets compile
      through the catalog's `date_trunc` and that `month` is a calendar month whose `+ INTERVAL 1 MONTH`
      pruning step is done by the engine. Keep `isSupported()` and `supportedCodes()`.
- [x] 3.2 `AggregateCompiler.groupExpr`: emit `FnExpr("date_trunc", [unit, column])` for every bucket
      instead of `date_bin(1, unit, column)`. Update the class javadoc's worked example (`:53-57`) and the
      `bucketBound` javadoc where it names `date_bin`.
- [x] 3.3 Confirm no validator change is needed: `AggregateSpecValidator:350` already gates on
      `isSupported()` and `:548` on `targetTypes()`, both of which `MONTH` now satisfies.

## 4. Specs

- [x] 4.1 Apply the `structured-query` delta: `date_trunc` row in the function table, its own paragraph
      beside `date_bin`'s (unit set, no amount, rendering, why the two overlap), the shared UTC paragraph
      widened to both, the Flight SQL live-rendering sentence extended, and three new scenarios.
- [x] 4.2 Apply the `aggregate-pipeline` delta: bucket unit set becomes `{hour, day, week, month}` with the
      calendar-month and target-type paragraphs and two new scenarios; the errors requirement's
      declared-but-unimplemented example moves off `month`.

## 5. Tests and verification

- [x] 5.1 `QuerySchemaControllerTest:105,135-137` — add `date_trunc` to the expected name list and fix the
      positional index assertions (`date_trunc` lands at index 4).
- [x] 5.2 Update pipeline compiler tests that assert `date_bin(` in rendered SQL; assert the hour/day/week
      rendering is unchanged (`toStartOfInterval(…, INTERVAL 1 DAY, 'UTC')`) so the refactor is proven
      output-neutral.
- [x] 5.3 Add a month-bucket pipeline test asserting the recompute bound renders `+ INTERVAL 1 MONTH`.
- [x] 5.4 Extend `FlightSqlIntegrationTest.provisionalRenderingsExecuteOnRealClickHouse` with `date_trunc`
      over `month` (and `quarter`, the unit nothing else exercises) against live ClickHouse.
- [x] 5.5 Add a front-end test that `date_trunc(request_time, 'month')` — Calcite's BigQuery operand order
      — is rejected rather than silently accepted.
- [x] 5.6 `./gradlew clean build` (with a reachable Docker daemon for Testcontainers)
      is green, and `openspec validate add-date-trunc-function` passes.
- [x] 5.7 Smoke against `./gradlew bootRun`: `GET /v1/queries/functions` lists `date_trunc`; the same month
      rollup via `/v1/queries/execute` and `/v1/queries/execute-sql` agree; a `unit: "month"` pipeline
      registers, previews, and its rendered refresh statement carries the calendar bound.
