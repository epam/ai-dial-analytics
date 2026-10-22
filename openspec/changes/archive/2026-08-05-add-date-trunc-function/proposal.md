## Why

"Per calendar month" is one of the two most obvious rollup shapes, and it cannot be declared.
`date_bin(amount, unit, timestamp)` is fixed-width by contract — its accepted units are
second/minute/hour/day/week, aligned to a fixed epoch origin — and a month is not a fixed width.
`TruncUnit.MONTH` exists today *only* so that `unit: "month"` is refused with a 422 naming the units that
work, instead of a generic Jackson 400.

The gap is not specific to pipelines: the structured query DSL and the SQL surface have no calendar
truncation either, so "revenue by month" is unexpressible through `/v1/queries/execute`,
`/v1/queries/execute-sql`, and Flight SQL alike. BI tools (Grafana, Power BI) emit `date_trunc` as a
matter of course.

## What Changes

- **New catalog function `date_trunc(unit, timestamp)`** — a calendar-aware sibling to `date_bin`, not a
  widening of it. Accepted units: `second`, `minute`, `hour`, `day`, `week`, `month`, `quarter`, `year`
  (case-insensitive), the PostgreSQL-shaped set, so a caller does not have to learn which units live
  behind which function. `date_bin` keeps the `amount ×` multiplier case that `date_trunc` has no
  argument for.
- Renders to native ClickHouse `toStartOfInterval(timestamp, INTERVAL 1 <unit>, 'UTC')`. The explicit
  `'UTC'` is retained for the same reason `date_bin` pins it: without it, alignment resolves to the
  ClickHouse server's zone, making the same declaration cut months at a different instant on differently
  configured servers.
- **Exposed on the SQL surface too**, so a DSL query rendered to SQL by
  `POST /v1/queries/translate` re-parses, and so BI clients can use it directly. The custom operator
  table is already chained ahead of Calcite's standard one, which matters here: Calcite ships a
  BigQuery-shaped `DATE_TRUNC(date, unit)` with the operands the other way round.
- **`month` becomes a supported aggregate bucket unit.** `TruncUnit.MONTH` gains its interval keyword and
  target types (`date` or `timestamp` — a month starts at midnight on the 1st, so either holds it
  losslessly), and the compiler's existing `+ INTERVAL 1 <unit>` pruning bound steps a real calendar
  month.
- **All aggregate buckets compile through `date_trunc`.** Hour/day/week currently compile to
  `date_bin(1, <unit>, col)`, which renders the identical `toStartOfInterval(…, INTERVAL 1 <unit>, 'UTC')`,
  so no existing rollup's SQL changes — but the synthetic `amount = 1` literal and the unit-dependent
  branch both go away.
- **`month` stops being an example of a declared-but-unimplemented value** in the error contract, since
  it is now implemented.

Not in scope: `quarter`/`year` as *bucket* units (`TruncUnit` gains only `month`; the DSL accepts them
because the function does), an `amount` multiplier for `date_trunc`, and any approximate or non-additive
measure work.

## Capabilities

### New Capabilities

None — `date_trunc` joins the existing closed function catalog.

### Modified Capabilities

- `structured-query`: the "Supported function catalog" requirement enumerates every accepted function and
  its signature; `date_trunc` is added, with its unit set and rendering stated the way `date_bin`'s is.
- `aggregate-pipeline`: "Aggregate spec vocabulary" names the bucket unit set as `{hour, day, week}`; it
  becomes `{hour, day, week, month}`. The errors requirement's worked example of a
  declared-but-unimplemented value currently uses `month` and must be re-pointed.

## Impact

- `service/query/translate/ExprTranslator` — new unit map and translation branch.
- `service/query/QueryFunctionCatalog` — new descriptor and advertised unit list; ordering is asserted.
- `service/query/sql/SqlQueryOperators`, `RexToExprTranslator`, `RelToStructuredQueryTranslator` — SQL
  surface registration, reverse mapping, result-type kind.
- `service/pipeline/model/TruncUnit` — `MONTH` becomes supported.
- `service/pipeline/AggregateCompiler` — buckets compile via `date_trunc`.
- Tests: `QueryFunctionCatalogParityTest` (new unit-parity assertion), `QuerySchemaControllerTest`
  (asserts the exact catalog list and index positions), pipeline compiler tests asserting `date_bin(` in
  rendered SQL, and `FlightSqlIntegrationTest`'s live-rendering coverage.
- No API shape change, no migration, no configuration, no dependency change. Existing rollups are
  byte-for-byte unaffected.
