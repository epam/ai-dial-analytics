# Proposal: reject-oversized-query-limit

## Why

A structured query (REST `POST /v1/queries/execute`) or a Flight SQL statement asking for more
rows than the 1000-row maximum is silently clamped: `LIMIT 50000` returns 1000 rows with no
signal that truncation happened. A client can easily mistake the truncated result for the full
dataset. Silent data loss is worse than a clear refusal — an out-of-range limit should be
rejected with an explicit error so the client can react (paginate, narrow the query, or ask for
a higher cap).

## What Changes

- `StructuredQueryBuilder` stops clamping: a page limit above the 1000-row maximum is rejected
  with `QueryValidationException` (REST: HTTP 400 `bad_request` `ErrorView`; Flight: gRPC
  `INVALID_ARGUMENT`), with a message naming the requested value and the maximum.
- A negative page limit is likewise rejected (mirroring the existing negative-offset rejection)
  instead of silently falling back to the default.
- A limit of `0` / an omitted limit keeps meaning "use the default of 100" — the SQL translator
  relies on `0` as the "OFFSET without LIMIT" sentinel, and SQL `LIMIT 0` itself is already
  rejected at translation.
- The maximum stays hardcoded at 1000; making it configurable is out of scope.
- Tests, docs (`docs/configuration.md` Flight prose), and the `local_env/pandas-client`
  verification checks are updated from clamp semantics to reject semantics.

## Capabilities

### Modified Capabilities

- `structured-query`: pagination governance in "Query validation and allowlist" — over-maximum
  and negative limits are rejected with HTTP 400 instead of being clamped/defaulted.
- `flight-sql-endpoint`: "Execution reuses structured-query governance" — a SQL `LIMIT` above
  the maximum is rejected with `INVALID_ARGUMENT` instead of clamped.
- `sql-query-translation`: the `LIMIT`/`OFFSET` bullet — downstream governance now rejects
  over-maximum limits rather than clamping.

## Impact

- **Code**: `service/query/translate/StructuredQueryBuilder.java` (validation replaces
  `clampLimit`); comment/javadoc touch-ups in `service/query/sql/RelToStructuredQueryTranslator.java`
  and `web/flight/FlightProperties.java`. No new config, no API shape changes.
- **Behavior**: breaking for clients that relied on silent clamping — they now get an explicit
  400 / `INVALID_ARGUMENT` and must request `limit <= 1000`.
- **Tests**: `QueryTranslatorValidationTest`, `FlightSqlIntegrationTest`, pandas verification
  client (`local_env/pandas-client`).
- **Docs**: `docs/configuration.md` Flight governance prose; `local_env/pandas-client/README.md`.
