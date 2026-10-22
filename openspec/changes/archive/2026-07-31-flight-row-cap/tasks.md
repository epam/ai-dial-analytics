# Tasks — Flight SQL row cap with truncation detection

## 1. Configuration

- [x] 1.1 Add `maxRows` to `FlightProperties` and update its Javadoc — row governance is no longer
      "inherited unchanged from structured-query"; Flight now has its own ceiling.
- [x] 1.2 Add `max-rows: ${FLIGHT_MAX_ROWS:10000}` to the `flight:` block of `application.yml` as a bare
      line, with no comment (project convention).
- [x] 1.3 Document the property in the Flight table of `docs/configuration.md`, and update the section's
      prose so the 1000-row claim becomes the Flight cap; state why Flight's ceiling exceeds REST's.
      Update the README prose that names the ceiling. No env-var table in README.

## 2. Carry the defaulted/explicit distinction

- [x] 2.1 Add `boolean explicitLimit` to `SqlTranslation` with Javadoc explaining that it is the parser's
      answer, not an inference from the limit value.
- [x] 2.2 Set it in `RelToStructuredQueryTranslator.translate` from `ctx.fetch != null` (the only
      construction site). Confirm `OFFSET`-without-`LIMIT` yields false.

## 3. Parameterize the maximum row limit

- [x] 3.1 `StructuredQueryBuilder`: add `build(dsl, model, query, int maxLimit)` and route
      `applyPage`/`validateLimit` through it; the existing 3-arg `build` delegates with `MAX_LIMIT`, so
      REST and DSL callers keep the shared 100/1000 governance unchanged. Same for `buildCount` if it
      shares the page path.
- [x] 3.2 `StructuredQueryExecutor.execute(model, query, maxLimit)` and
      `StructuredQueryService.execute(query, maxLimit)` overloads, existing forms delegating with the
      shared maximum. Javadoc the new parameter as "the caller's row ceiling".

## 4. Flight producer

- [x] 4.1 Delete `requireExplicitLimit`. Add validation that an **explicit** `LIMIT n` satisfies
      `n <= flight.max-rows`, failing `INVALID_ARGUMENT` naming both numbers; call it from
      `getFlightInfoStatement` (before ClickHouse) and from the ticket path in `streamQuery`.
- [x] 4.2 In `streamQuery`, when the limit was defaulted, execute a copy of the translated query whose
      page is `OffsetPage(offset, maxRows + 1, false)` — preserving any `OFFSET` — via the
      `maxLimit = maxRows + 1` execution overload.
- [x] 4.3 After execution, if the limit was defaulted and `rows.size() > maxRows`, fail
      `INVALID_ARGUMENT` naming the cap and both remedies, streaming nothing. Otherwise stream the rows.
      There must be no branch that trims and continues.
- [x] 4.4 Update the class Javadoc: the explicit-`LIMIT` rule is replaced by the cap plus limit+1
      detection, and the truncation error can only surface at `doGet`.

## 5. Tests

- [x] 5.1 Unbounded query under the cap → all rows, no error.
- [x] 5.2 Unbounded query over the cap → `INVALID_ARGUMENT` naming the cap, and **no rows delivered**
      (assert the stream yields nothing, not merely that it throws).
- [x] 5.3 Unbounded aggregate `SELECT count(*)` → succeeds with one row (the DirectQuery case).
- [x] 5.4 Explicit `LIMIT n` with more rows available → exactly n rows, no error.
- [x] 5.5 Explicit `LIMIT` above the cap → `INVALID_ARGUMENT` naming the configured value.
- [x] 5.6 `OFFSET` with no `LIMIT` → executes under the cap (previously rejected).
- [x] 5.7 Probe row never reaches the client: a result of exactly `max-rows` rows streams exactly
      `max-rows`.
- [x] 5.8 A non-default `FLIGHT_MAX_ROWS` is honoured in both directions. Use a small configured cap so
      the over-cap cases need few rows; a separate test class with its own property is acceptable, and is
      also what makes 5.7 cheap.
- [x] 5.9 REST unaffected: the same unbounded statement to `POST /v1/queries/execute-sql` still returns
      the default 100 rows.
- [x] 5.10 Update the tests that the superseded rule required: `limitGovernanceRequiresAnExplicitLimit`
      is replaced, and every statement that had a `LIMIT` added purely to satisfy the old rule may keep
      it (harmless) — but the no-`LIMIT` assertions must be re-pointed at the new behaviour.

## 6. Verification

- [x] 6.1 `./gradlew clean build` passes (compile, checkstyle, tests).
- [x] 6.2 Runtime: `./gradlew bootRun` with `FLIGHT_ENABLED=true` on a non-conflicting port, then probe
      with pyarrow — unbounded under/over a deliberately small cap, unbounded `count(*)`, explicit
      `LIMIT` above the cap, `OFFSET` with no `LIMIT` — recording request → expected → actual.
