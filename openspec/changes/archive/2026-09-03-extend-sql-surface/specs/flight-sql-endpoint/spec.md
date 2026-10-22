## MODIFIED Requirements

### Requirement: Execution reuses structured-query governance

Every Flight query SHALL execute through the same execution path as `POST /v1/queries/execute`: a
single-block statement by constructing a `StructuredQuery`, a composite statement (one that joins, reads a
derived table or CTE, or carries a subquery — see the sql-query-translation contract) by executing the
composite plan the shared front end produces. All structured-query governance SHALL apply unchanged with
one exception, the row ceiling: entity and field validation in every block, the closed function catalog,
sensitive-column masking, `final` deduplication, the query budget and enrichment join rules apply as-is,
while the maximum number of rows a Flight query may return is the Flight transport's own configured cap
`flight.max-rows` (default 10000) rather than the shared 1000-row maximum. That the Flight ceiling exceeds
the REST ceiling is deliberate: Flight streams Arrow record batches with per-request Arrow memory bounded
by `flight.request-memory-limit-bytes` and concurrency bounded by `flight.max-concurrent-streams`, whereas
the REST surfaces materialize a JSON document. For a composite statement the ceiling, the clamp and the
truncation probe below apply to the **outermost** block; an inner block's `LIMIT`/`OFFSET` is executed as
written and an inner block without one is not bounded.

`flight.max-rows` is a **ceiling on the number of rows the endpoint returns**, not a constraint on the
`LIMIT` literal a client may write. No statement SHALL be rejected for the size of its `LIMIT`
(**BREAKING**: superseding the rule that a `LIMIT` above `flight.max-rows` is rejected with
`INVALID_ARGUMENT` naming the requested limit and the cap). A `LIMIT` far above the cap is how BI tools
express a self-protection bound — Power BI's DirectQuery generator emits `LIMIT 1000001` on results of
a few dozen rows — and refusing it blocks queries the endpoint can answer while protecting nothing.

The governing distinction is therefore whether the client stated a bound **that ADAS can honour**:

- A statement with an explicit `LIMIT n` where `n <= flight.max-rows` SHALL return up to `n` rows with
  no truncation error and no probe: the caller asked for a bound, so stopping at it is not a surprise.
- Every other statement — one whose `LIMIT` was not stated (no `LIMIT` at all, or an `OFFSET` with no
  `LIMIT`), **or one whose explicit `LIMIT` exceeds `flight.max-rows`** — SHALL be executed under an
  implicit bound of `flight.max-rows`, with its effective limit clamped to the cap. Such a statement
  SHALL NOT be rejected for lacking a `LIMIT` or for stating an oversized one (**BREAKING**:
  superseding `flight-sql-catalog-metadata`, which rejected every statement without an explicit
  `LIMIT`; that rule blocked results that cannot truncate, such as `SELECT count(*)`, and every query a
  BI tool's DirectQuery generator emits).

Whether the limit was stated SHALL be carried explicitly by the translation and SHALL NOT be inferred
from the limit's value; whether a stated limit can be honoured SHALL be decided by comparing it to the
configured cap, so a non-default `flight.max-rows` governs both the clamp and the ceiling.

A statement executed under the implicit bound SHALL be executed requesting one row more than the cap,
and:

- if more than `flight.max-rows` rows are returned, the call SHALL fail with `INVALID_ARGUMENT` naming
  the configured cap and both remedies (add an explicit `LIMIT`, or aggregate), and SHALL NOT stream a
  partial page;
- otherwise the rows SHALL be streamed, and the extra probe row SHALL NEVER be visible to the client.

Any `OFFSET` the statement carried SHALL be preserved when the limit is clamped.

Because `getFlightInfo` validates without executing, a truncation failure can only surface at `doGet`:
`getFlightInfo` SHALL succeed for such a statement and the stream SHALL then fail. Consequently a
query's success depends on the data as well as the statement — a result that fits the cap today may
exceed it later — and a client that supplies an explicit `LIMIT` within the cap is not subject to it.
The clamp SHALL be applied on the `doGet` ticket path as well as at `getFlightInfo`, because the ticket
carries client-supplied SQL and a hand-forged ticket must not reach execution unbounded.

The REST surfaces are unaffected: `POST /v1/queries/execute-sql`, `POST /v1/queries/execute`, and the
translation endpoints SHALL keep the shared default limit of 100 and maximum of 1000, including their
existing silent truncation of an unbounded query to the default and their rejection of an explicit
`limit` above the shared maximum. The clamp specified here SHALL NOT be extended to them: it is a
property of a streaming transport whose clients write guard rails, not of a caller hand-writing a page
size.

#### Scenario: Limit above the configured cap is clamped, not rejected

- **WHEN** a client runs the Power BI DirectQuery statement
  `select "deployment", "C1" from (select "deployment", SUM("total_price") as "C1" from "adas"."dial_usage_log" group by "deployment") as "ITBL" where not "C1" is null LIMIT 1000001 OFFSET 0`
  via Flight with `flight.max-rows` at its default, and the aggregate produces fewer rows than the cap
- **THEN** the call succeeds and every result row is streamed — the `LIMIT` literal alone causes no
  error

#### Scenario: Clamped limit still fails on an oversized result

- **WHEN** a client runs a statement whose explicit `LIMIT` exceeds `flight.max-rows` and whose result
  exceeds `flight.max-rows`
- **THEN** `getFlightInfo` succeeds, the `doGet` stream fails with `INVALID_ARGUMENT` naming the cap and
  the remedies, and no rows are delivered to the client

#### Scenario: Unbounded query under the cap succeeds

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log` with no `LIMIT` and the entity holds
  fewer rows than `flight.max-rows`
- **THEN** every matching row is streamed and no error is raised

#### Scenario: Unbounded query over the cap fails without a partial page

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log` with no `LIMIT` and the entity holds
  more than `flight.max-rows` rows
- **THEN** `getFlightInfo` succeeds, the `doGet` stream fails with `INVALID_ARGUMENT` naming the cap and
  the remedies, and no rows are delivered to the client

#### Scenario: Unbounded aggregate succeeds

- **WHEN** a client runs `SELECT count(*) AS n FROM dial_usage_log` with no `LIMIT` over an entity of any
  size
- **THEN** the single aggregate row is returned and no error is raised, because one row cannot exceed the
  cap

#### Scenario: Probe row never reaches the client

- **WHEN** a query executed under the implicit bound — whether it stated no `LIMIT` or one above the cap
  — returns exactly `flight.max-rows` rows
- **THEN** exactly `flight.max-rows` rows are streamed; the extra row requested to detect truncation is
  never part of the result

#### Scenario: Explicit limit stops at the bound

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log LIMIT 7` where far more rows match
- **THEN** exactly 7 rows are streamed and no truncation error is raised

#### Scenario: Offset survives the clamp

- **WHEN** a client runs a statement with an explicit `LIMIT` above the cap and a non-zero `OFFSET`
- **THEN** the offset is applied and the result is bounded by the cap, exactly as it would be for the
  same statement with no `LIMIT`

#### Scenario: Offset without limit is bounded, not rejected

- **WHEN** a client runs `SELECT project_id FROM dial_usage_log ORDER BY total_tokens OFFSET 5` with no
  `LIMIT`
- **THEN** the query executes under the implicit cap instead of being rejected

#### Scenario: Configured cap governs the clamp

- **WHEN** `flight.max-rows` is configured to 5 and a client runs, over an entity holding 6 rows, an
  unbounded query and separately `SELECT … LIMIT 6`
- **THEN** both are executed under the bound of 5 and both fail naming 5, because the result exceeds the
  cap — the explicit `LIMIT 6` is not rejected for its literal but for the size of its result

#### Scenario: REST keeps the shared governance

- **WHEN** the same unbounded statement is sent to `POST /v1/queries/execute-sql` over an entity holding
  more than 100 rows
- **THEN** it succeeds and returns the shared default of 100 rows, unchanged by the Flight cap

#### Scenario: REST still rejects an oversized explicit limit

- **WHEN** a client sends `SELECT project_id FROM dial_usage_log LIMIT 50000` to
  `POST /v1/queries/execute-sql`
- **THEN** it fails with the shared page-limit error naming 50000 and 1000 — the Flight clamp does not
  apply to REST

#### Scenario: Composite statement streams under the same cap

- **WHEN** a client runs `SELECT a.project_id, b.rate FROM dial_usage_log a JOIN rate_analytics b ON a.response_id = b.response_id` via Flight with no `LIMIT`
- **THEN** `getFlightInfo` reports the two result columns with their Arrow types, the stream is bounded
  by `flight.max-rows` with the same truncation probe as a single-entity statement, and the rows are
  streamed

#### Scenario: Composite statement failures map like single-entity ones

- **WHEN** a client runs a join whose `ON` carries no equality, or a correlated subquery, via Flight
- **THEN** `getFlightInfo` fails with `INVALID_ARGUMENT` carrying the translator's message, and nothing
  is executed
