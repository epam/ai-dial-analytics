# Tasks

## 1. Re-key the row-cap decision

- [x] 1.1 In `AdasFlightSqlProducer`, replace the "did the client state a limit" predicate with
      "did the client state a limit ADAS can honour" — an explicit `LIMIT n` with `n <= flight.max-rows`.
      One helper, used by both `effectiveQuery` and `rejectIfTruncated`, so the two cannot drift.
- [x] 1.2 Delete `requireLimitWithinCap` and both its call sites (`getFlightInfoStatement`,
      `streamQuery`). No statement is rejected for the size of its `LIMIT` literal any more.
- [x] 1.3 `effectiveQuery`: clamp to `flight.max-rows + 1` for an over-cap explicit limit exactly as for
      a defaulted one, preserving any `OFFSET`.
- [x] 1.4 `rejectIfTruncated`: apply the truncation check to an over-cap explicit limit.
- [x] 1.5 Update the javadoc on all three methods — the rationale is now "a bound ADAS can honour",
      and the class comment's description of row governance needs to match.

## 2. Tests

- [x] 2.1 `FlightRowCapIntegrationTest` (cap = 5): replace `explicitLimitAboveTheConfiguredCapIsRejected`
      with the clamp behaviour — over-cap `LIMIT` with an under-cap result returns every row.
- [x] 2.2 Over-cap `LIMIT` with an over-cap result → truncation error, and assert **nothing** was
      delivered, not merely that it failed.
- [x] 2.3 Over-cap `LIMIT` combined with `OFFSET`.
- [x] 2.4 Probe row never leaks: an over-cap `LIMIT` whose result is exactly the cap returns exactly the
      cap.
- [x] 2.5 Explicit `LIMIT` at or below the cap unchanged (already covered — verify it still holds).
- [x] 2.6 `FlightSqlIntegrationTest`: at the default cap, the captured Power BI DirectQuery statement
      (`LIMIT 1000001 OFFSET 0` over a nested aggregate with a filter on the aggregate alias) returns
      its rows. One substitution from the capture — `SUM("total_tokens")` for `SUM("total_price")`,
      because the fixture cannot populate `total_price` and a sum over an all-null column is null,
      which the statement's own `not "C1" is null` would filter away. Structure is unchanged, and the
      unmodified statement is exercised against real local data in the runtime check (3.2).
- [x] 2.7 REST is unaffected: `LIMIT 50000` to `execute-sql` still fails with the shared page-limit
      error.

## 3. Close out

- [x] 3.1 `./gradlew clean build` green (compile + checkstyle + tests).
- [x] 3.2 Runtime check: `FLIGHT_ENABLED=true ./gradlew bootRun`, then run the captured DirectQuery
      statement over Flight and confirm it returns rows instead of the cap error.
