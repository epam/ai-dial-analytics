## 1. Spec housekeeping (verbatim relocation)

- [x] 1.1 In `openspec/specs/structured-query/spec.md`, move `Relative time expressions` and `The catalog declares which functions are non-deterministic` (text unchanged) to just before `## Implementation notes`
- [x] 1.2 In `openspec/specs/sql-query-translation/spec.md`, move `The clock has one DSL name and three SQL spellings` and `Interval arithmetic translates to the offset functions` (text unchanged) to just before `## Implementation notes`
- [x] 1.3 Confirm `openspec validate --specs` no longer reports those requirements outside `## Requirements`, and `git diff` shows only moved lines; commit the relocation on its own

## 2. Test

- [x] 2.1 Rewrite `StructuredQueryExecutionIntegrationTest.oneExecutionObservesOneInstant` as `eachNowCallSiteIsOneValueAcrossRows`: five rows, `now()` projected as `a` and `b`; assert all `a` equal, all `b` equal, non-null, and |a − b| ≤ 1 s with a comment saying the bound is a tolerance, not a contract
- [x] 2.2 Add a translation test: `request_time >= now() - INTERVAL 1 HOUR AND request_time < CURRENT_TIMESTAMP` yields two separate zero-argument `now` calls
- [x] 2.3 Add a rendering assertion: a two-bound `now()` window renders two `now64(3, 'UTC')` calls and no literal instant
- [x] 2.4 Audit remaining tests that use `now()` / `CURRENT_TIMESTAMP` / `LOCALTIMESTAMP` for an equality assumption between call sites; fix any found

## 3. Code prose

- [x] 3.1 Correct the `ExprTranslator.now` Javadoc: one value per call site per statement, separate call sites independent; keep the precision and `'UTC'` rationale
- [x] 3.2 Correct the `now` description in `QueryFunctionCatalog` (drop "every call in a statement yields the same value"); confirm no test pins the old text

## 4. Docs

- [x] 4.1 Fix `docs/sql-reference.md` "The clock and relative windows" and the `now()` row of the function table
- [x] 4.2 Grep `docs/`, README and MCP descriptions once more for single-instant wording

## 5. Verify

- [x] 5.1 `openspec validate align-now-with-clickhouse --strict` passes
- [x] 5.2 `./gradlew clean build` passes
