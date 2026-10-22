# Tasks: reject-oversized-query-limit

## 1. Shared pipeline

- [x] 1.1 `StructuredQueryBuilder`: replace `clampLimit` with validation — `limit < 0` and
  `limit > 1000` throw `QueryValidationException` (message names the requested value and the
  maximum); `0` still falls back to the default 100; update the class javadoc
- [x] 1.2 `RelToStructuredQueryTranslator.intLiteral`: update the "clamped downstream" comment to
  reject semantics; `FlightProperties` javadoc likewise

## 2. Tests

- [x] 2.1 `QueryTranslatorValidationTest`: over-maximum limit now asserts the thrown
  `QueryValidationException`; keep the `limit 0 → default` render check; add a negative-limit
  rejection case
- [x] 2.2 `FlightSqlIntegrationTest.limitGovernanceMatchesRest`: `LIMIT 50000` expects
  `INVALID_ARGUMENT` instead of 1000 rows; sweep other tests for clamp assertions (none found
  elsewhere); also asserts `LIMIT 1000` (at the maximum) is honored

## 3. Docs and verification client

- [x] 3.1 `docs/configuration.md`: reword the Flight governance prose (1000-row clamp → maximum,
  over-limit rejected)
- [x] 3.2 `local_env/pandas-client`: `check_limit_clamp` → `check_limit_rejected` (expects an
  INVALID_ARGUMENT error), update README item

## 4. Finalize

- [x] 4.1 `./gradlew clean build` passes; `openspec validate reject-oversized-query-limit`
