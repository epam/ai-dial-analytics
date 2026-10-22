## Why

A query's time window can only be written as literal instants today — the SQL reference says so in
as many words ("There is no `now()` / `CURRENT_TIMESTAMP`; time bounds are written as literals").
That is tolerable for a one-off run and wrong for a **saved** query, which is the one thing the
platform stores to be re-run later: `request_time >= TIMESTAMP '2026-09-14 08:00:00'` means
"the two hours before I saved this" and never means it again. The saved query's own `time` member
does not close the gap — it is the Query Builder's time-picker state, which the service explicitly
does not interpret (`saved-queries`: "The system SHALL NOT interpret a relative period's meaning"),
so the body still carries whatever instants were baked into it. There is no way to express
"the last 2 hours" as a query, on either surface.

The fix is the one ClickHouse and PostgreSQL already agree on: a clock function and interval
arithmetic, so the *body* carries the relative window and re-runs correctly for as long as it is
stored.

## What Changes

Three new closed-catalog functions, reachable identically from the structured-query DSL and from SQL:

- **`now()`** — the current instant, UTC, at millisecond precision. Nullary scalar returning
  `timestamp`, rendered as ClickHouse `now64(3, 'UTC')`.
- **`date_sub(unit, amount, timestamp)`** and **`date_add(unit, amount, timestamp)`** — a fixed
  offset from a timestamp. `unit` is a string literal in `second`, `minute`, `hour`, `day`, `month`,
  `year`; `amount` is a positive integer literal. Rendered as ClickHouse
  `<ts> - INTERVAL <amount> <UNIT>` / `+ INTERVAL …`. The function names and argument order are
  borrowed from ClickHouse's own `dateAdd(unit, value, date)`, so the two read alike — though not
  identically: ClickHouse takes the unit as a bare keyword where this DSL takes a string literal (see
  the correction in design.md, decision 1).

So "the last 2 hours" becomes `WHERE request_time >= now() - INTERVAL 2 HOUR` in SQL and
`{"op":"ge","args":[{"field":"request_time"},{"fn":"date_sub","args":["hour",2,{"fn":"now"}]}]}`
in the DSL — one saved body, correct at every later run.

On the **SQL surface** the following inbound spellings are additionally accepted and lowered onto
those three functions, so a statement written the ClickHouse way, the PostgreSQL way, or by a BI
client all arrive at the same document:

- `now()`, `CURRENT_TIMESTAMP`, `LOCALTIMESTAMP` → `now()`;
- `<timestamp> ± INTERVAL <amount> <unit>` → `date_add` / `date_sub`, in both the ClickHouse
  unquoted spelling (`INTERVAL 2 HOUR`) and the standard quoted one (`INTERVAL '2' HOUR`), with
  `WEEK` normalized to 7 `day` and `QUARTER` to 3 `month` (Calcite already lowers them that way)
  and a negative amount normalized by flipping the operator;
- `date_add(unit, amount, ts)` / `date_sub(unit, amount, ts)` as ordinary function calls.

A multi-field interval qualifier (`INTERVAL '1-2' YEAR TO MONTH`), a fractional or zero amount, and
a non-literal interval operand are rejected — `bad_request`, the ordinary translation failure.

**One pre-existing defect in the same operator is fixed in passing.** `(ts1 - ts2) HOUR` — the
standard datetime-difference form — is accepted today and silently drops the qualifier, translating
to the numeric `minus` of the two operands and rendering a difference in **seconds**: a caller asks
for hours and gets a number ~3600× too large, with nothing to flag it. Since this change is the one
that teaches the `-` operator about intervals, that form is now rejected (`bad_request`); the subset
has no interval result type to carry it, and a refusal is the only answer that is not wrong. Bare
`ts1 - ts2` was already rejected by type checking and stays so.

**The determinism guarantee the enrichment path rests on is made explicit rather than lost.**
Class-1 enrichment and the aggregate pipeline currently get determinism for free: `now()` is absent
from the closed catalog, so nothing has to reject it (`class1-enrichment`: "Determinism is
guaranteed by construction rather than by a denylist"). Adding `now()` to that catalog removes that
floor, so the catalog gains a declared **`deterministic`** property per function, and the two
pipeline-side validators (`ExprSqlValidator`, `FilterSqlValidator`) reject any function declaring
`deterministic: false` with the `422 pipeline_validation_failed` they already raise. The guarantee
stays derived from the catalog — a declared property, not a hand-maintained denylist — and clients
learn it from discovery.

**`saved-queries` needs no new field**, and this answers the open question in the request: the
`time` member stays exactly what it is, the time-picker's state, uninterpreted. What changes is that
a body no longer has to disagree with it — the DSL needs this as much as SQL does, because a DSL
body's time filter is baked-in literals for the same reason a SQL body's is.

Not included, and expressible without it: `today()` (`date_trunc('day', now())`), `yesterday()`,
`week`/`quarter` as `date_add`/`date_sub` units (1 week is exactly 7 days, 1 quarter exactly
3 months — unlike `date_trunc`, where they name a calendar alignment no other unit can express), and
any server-side resolution of a saved query's relative `period` token.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `structured-query`: three new entries in the closed function catalog (`now`, `date_add`,
  `date_sub`) with their literal-argument and unit rules, their ClickHouse rendering, and the
  statement that `now()` is the catalog's first non-deterministic function.
- `sql-query-translation`: the accepted inbound spellings of the clock and of interval arithmetic,
  the normalization each undergoes on the way to a DSL document, the rejections, and the round-trip
  rendering (`now()` and the infix `INTERVAL` form) that keeps DSL → SQL → DSL stable.
- `query-schema-discovery`: `GET /v1/queries/functions` entries carry the new `deterministic`
  member.
- `enrichment-registry`: the single normative determinism rule — every governed SQL fragment a
  pipeline carries (`filter`, `ready_when.signal`, `member_select.prefer_sql`, a `sql` evaluator's
  output expressions, an aggregate `filter` and measure `where`) is rejected with
  `422 pipeline_validation_failed` when it calls a function the catalog declares non-deterministic.
- `class1-enrichment`: determinism is enforced by that declared property instead of by the absence of
  such a function from the catalog — the "guaranteed by construction" paragraph is replaced.
- `aggregate-pipeline`: the same rule stated in this capability's terms, applied at registration and
  re-applied at refresh, because a moving `filter` would silently rewrite an already-published
  historical bucket.
- `saved-queries`: a stored body may express its own relative window; the `time` member remains
  uninterpreted Query Builder state, and no new field is introduced.

## Impact

**Code** — `service/query/QueryFunctionCatalog` (three descriptors, new `deterministic` column),
`web/dto/query/QueryFunctionDto` (new member), `service/query/translate/ExprTranslator` (three
render branches), `service/query/translate/ExprFieldType` (return type of the new calls),
`service/query/sql/SqlQueryOperators` (register `NOW`, `DATE_ADD`, `DATE_SUB`),
`service/query/sql/RexToExprTranslator` (clock spellings; interval operand of `+`/`-`),
`service/query/sql/StructuredQueryToSqlRenderer` (nullary `now()`, infix `INTERVAL` form),
`service/enrichment/validation/{ExprSqlValidator,FilterSqlValidator}` (determinism gate).

**APIs** — `POST /v1/queries/execute`, `/execute-sql`, `/translate`, `/translate-sql`,
`POST /v1/saved-queries` (a relative body now validates and stores) and Flight SQL all widen by the
same three functions, since they share one translator. `GET /v1/queries/functions` gains a member on
every entry. `POST /v1/pipelines` narrows by nothing that was previously accepted — the functions it
now rejects did not exist before this change.

**No** new configuration property, no migration, no dependency change, and no change to
`docs/configuration.md`. `docs/sql-reference.md` is updated (its "there is no `now()`" paragraph and
the scalar function table).
