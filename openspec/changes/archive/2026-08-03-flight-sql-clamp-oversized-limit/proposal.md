## Why

With `INFORMATION_SCHEMA` shipped, Power BI's Dremio connector reaches ADAS in **DirectQuery** mode and
every SQL construct its generator emits translates — derived tables, doubly-nested subqueries,
`GROUP BY` inside a subquery, a filter on an aggregate alias, `LIMIT … OFFSET`. Exactly one thing
fails. A visual combining a dimension with a measure generates:

```sql
select "deployment", "C1"
from (
    select "deployment", SUM("total_price") as "C1"
    from "adas"."dial_usage_log"
    group by "deployment"
) as "ITBL"
where not "C1" is null
LIMIT 1000001 OFFSET 0
```

and ADAS answers `LIMIT 1000001 exceeds the maximum of 10000 rows this endpoint returns`.

`1000001` is **Power BI's DirectQuery guard rail** — one million rows plus one, so it can detect
overflow. It is not a claim that a million rows are expected: the real result here is **78 rows**. The
statement above was captured from a TCP tee in front of the Flight listener, so it is literal.

Nothing else is wrong with it. The byte-identical query with `LIMIT 1000` returns 78 rows, and the same
nested shape without the outer filter returns 118. Single-field visuals already work only because they
happen to generate `… LIMIT 501`, under the cap.

So the endpoint currently rejects a query it can answer, because of a number the client wrote to
protect itself.

## What Changes

An explicit `LIMIT n` where `n > flight.max-rows` SHALL be treated **exactly like a statement with no
`LIMIT`** rather than rejected:

- the effective limit is clamped to `flight.max-rows`;
- execution requests `flight.max-rows + 1` rows;
- if more than `flight.max-rows` come back, the call fails with the existing truncation error and
  streams no partial page;
- otherwise the rows are streamed and the probe row never reaches the client.

An explicit `LIMIT n` where `n <= flight.max-rows` is **unchanged**: up to `n` rows, no probe, no
truncation error. `OFFSET` combines with all of the above as it does today. `requireLimitWithinCap`
disappears, so no statement is rejected for the size of its `LIMIT` literal.

The guarantee that motivated the cap is preserved intact: a genuinely oversized result still fails
loudly. Only the client's guard rail stops being read as a request for a million rows.

**Scope is the Flight SQL transport only.** `POST /v1/queries/execute-sql`, `POST /v1/queries/execute`
and the translation endpoints keep the shared governance unchanged, including
`page limit <n> exceeds the maximum of 1000 rows`.

## Impact

- Affected specs: `flight-sql-endpoint` only — the requirement "Execution reuses structured-query
  governance" is modified. `rest-sql-endpoint` and `structured-query` are deliberately untouched.
- Affected code: `AdasFlightSqlProducer` — `requireLimitWithinCap` removed, `effectiveQuery` and
  `rejectIfTruncated` re-keyed from "did the client state a limit" to "did the client state a limit
  ADAS can honour".
- No configuration change, no migration, no API-shape change.
- **Behaviour change, and a loosening one**: a statement that previously failed at `getFlightInfo` now
  runs. Nothing that previously succeeded changes, and no result that was previously returned changes.
  The one guarantee that weakens is the *timing* of failure for an oversized result: it moves from
  `getFlightInfo` to `doGet`, because the row count is only knowable after execution — exactly where
  the unbounded case already fails.
