# Flight SQL row cap with truncation detection

## Why

`flight-sql-catalog-metadata` closed a real trap — a Flight client that omitted `LIMIT`
silently received 100 rows that looked like a whole table — by rejecting every `SELECT` without an
explicit `LIMIT`. Measured against Power BI Desktop with the Arrow Flight SQL ODBC driver, that rule is
too blunt in two ways:

1. **It rejects results that could never be truncated.** `SELECT count(*) FROM dial_usage_log` returns
   exactly one row and is refused for lacking a `LIMIT`.
2. **It makes DirectQuery structurally impossible.** Power BI's DirectQuery generator emits aggregate
   queries (`SELECT … GROUP BY …`) with no `LIMIT`. DirectQuery is the mode that actually suits ADAS —
   small aggregate results per visual, no data copied, governance applied per query — and the rule
   blocks every generated visual query.

The goal was never "make the client write `LIMIT`"; it was "never return a truncated page as if it were
complete". A limit+1 probe achieves that without demanding a clause the client cannot generate.

## What Changes

- **New property `flight.max-rows`** (`FLIGHT_MAX_ROWS`, default **10000**) — the Flight transport's own
  row ceiling, governing both directions: the implicit bound on a query with no `LIMIT`, and the maximum
  accepted **explicit** `LIMIT`. It replaces the shared 1000-row maximum that Flight currently inherits.
- **BREAKING (supersedes `flight-sql-catalog-metadata`)**: the explicit-`LIMIT` requirement is
  **replaced**, not amended. A query with no `LIMIT` is executed with an implicit bound instead of being
  rejected.
- **Truncation detection by limit+1**: a defaulted-limit query is executed requesting
  `flight.max-rows + 1` rows. If more than `flight.max-rows` come back the call fails with
  `INVALID_ARGUMENT` naming the cap and both remedies; otherwise the rows are returned. The extra probe
  row is never streamed to a client.
- An **explicit** `LIMIT n` with `n <= flight.max-rows` behaves exactly as before — up to n rows, no
  error, no probe. The caller asked for a bound, so stopping at it is not a surprise. `n` above the cap
  keeps failing, with the message now naming the configured value rather than a hardcoded 1000.
- `OFFSET` without `LIMIT` is now bounded by the cap rather than rejected.
- The translation carries whether the limit was **defaulted or explicit** as a first-class flag
  (`SqlTranslation.explicitLimit`) rather than being inferred from the limit's value.
- Flight's row ceiling now **exceeds REST's**. Deliberate, and justified in design D3.

**The REST endpoints do not change.** `POST /v1/queries/execute-sql` and `POST /v1/queries/execute`
keep the default limit of 100, the maximum of 1000, and their existing silent-truncation behaviour. A
test pins that.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `flight-sql-endpoint`: the explicit-`LIMIT` requirement is replaced by an implicit cap with
  truncation detection; the row-limit governance requirement now states that Flight uses its own
  configured cap (`flight.max-rows`) rather than the shared 1000-row maximum; a new configuration
  property is added to the lifecycle/configuration requirement.

`rest-sql-endpoint` and `structured-query` are deliberately **not** listed: their behaviour and specs
are untouched.

## Impact

- `web/flight/FlightProperties` — new `maxRows` component; `application.yml` gains one bare line.
- `web/flight/AdasFlightSqlProducer` — `requireExplicitLimit` is replaced by explicit-limit validation
  against the cap plus the limit+1 probe and its truncation check.
- `service/query/sql/SqlTranslation` + `RelToStructuredQueryTranslator` — carry `explicitLimit`.
- `service/query/translate/StructuredQueryBuilder`, `repository/StructuredQueryExecutor`,
  `StructuredQueryService` — gain an overload taking the caller's maximum row limit, so Flight can
  authorise a page above 1000. The existing no-argument entry points keep the shared 100/1000
  governance byte-for-byte, which is what leaves REST untouched.
- `docs/configuration.md` (Flight table + prose) and `README.md` prose.
- Tests: `integration/FlightSqlIntegrationTest` (cap behaviour in both directions, the aggregate case,
  no partial page, no probe-row leak, REST unaffected) and a non-default `FLIGHT_MAX_ROWS` case.

## Trade-off accepted

A query's success now depends on the **data**, not only on the query: a report that works against a
9 000-row result starts failing at 10 001 with no change to the report. That is the price of the
convenience, and it is why the blunt rule was chosen first. Any client that supplies an explicit `LIMIT`
is immune. Recorded in design D4.
