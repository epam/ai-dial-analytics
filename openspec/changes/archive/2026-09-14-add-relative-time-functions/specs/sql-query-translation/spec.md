## ADDED Requirements

### Requirement: The clock has one DSL name and three SQL spellings

The SQL subset SHALL accept `now()`, `CURRENT_TIMESTAMP` and `LOCALTIMESTAMP` — the last two with or
without empty parentheses, as the grammar spells them — and SHALL lower all three to the single closed
catalog function `now` (see [structured-query](../structured-query/spec.md), "Relative time
expressions") in the resulting `StructuredQuery`. No new expression kind SHALL be introduced: the DSL
carries the clock as an ordinary zero-argument `fn` expression.

Three inbound spellings exist because three populations write them and none should have to learn the
others': `now()` is what ClickHouse and PostgreSQL users write, `CURRENT_TIMESTAMP` is what standard
SQL and most BI clients emit, and `LOCALTIMESTAMP` is what a client emits when it means "without a
zone" — which, on a surface whose one zone is UTC, is the same instant. They SHALL therefore produce
the **same** document, not three documents that behave alike.

`CURRENT_DATE` SHALL remain rejected. It is not a spelling of the clock but a truncation of it, and
lowering it silently would answer a date-typed request with a timestamp; the subset's own
`date_trunc('day', now())` says it exactly.

The DSL → SQL renderer SHALL emit `now()` and never a second spelling, so a rendered statement reads
back to the same document it was rendered from.

#### Scenario: The three clock spellings produce one document

- **WHEN** a client submits `SELECT event_id FROM dial_usage_log WHERE request_time >= now()`, the
  same statement with `CURRENT_TIMESTAMP`, and the same with `LOCALTIMESTAMP`
- **THEN** all three translate successfully to the same `StructuredQuery`, whose filter compares
  `request_time` against a zero-argument `now` function expression

#### Scenario: The clock round-trips through the renderer

- **WHEN** a DSL document whose filter calls `now()` is rendered to SQL and the rendered statement is
  translated back
- **THEN** the rendered statement spells the call `now()` and the resulting document equals the
  original

#### Scenario: CURRENT_DATE stays rejected

- **WHEN** a client submits a statement using `CURRENT_DATE`
- **THEN** translation fails with HTTP 400 and `error` `bad_request`

### Requirement: Interval arithmetic translates to the offset functions

The SQL subset SHALL accept `<timestamp expression> + INTERVAL <amount> <unit>` and
`<timestamp expression> - INTERVAL <amount> <unit>`, and SHALL lower them to the closed-catalog
functions `date_add` and `date_sub` respectively. It SHALL additionally accept `date_add(unit, amount,
timestamp)` and `date_sub(unit, amount, timestamp)` as ordinary function calls, whose `unit` is a
string literal — so the two spellings a ClickHouse user already has both work and land on the same
document.

Recognition of the interval form SHALL take precedence over the numeric lowering of `+` and `-` (see
"Arithmetic operators translate to catalog functions"): those operators lower to `plus`/`minus` when
both operands are numeric, and to `date_add`/`date_sub` when the right operand is an interval.

**Both interval spellings SHALL be accepted.** The engine-native `INTERVAL 2 HOUR` and the standard
`INTERVAL '2' HOUR` SHALL produce the same document. An interval written with a leading sign SHALL be
normalized by flipping the operator, so `+ INTERVAL -2 HOUR` and `- INTERVAL 2 HOUR` both yield
`date_sub('hour', 2, …)`: the DSL carries direction in the function name, so there is exactly one
document for one meaning and the round trip is single-valued.

**The unit's case SHALL NOT distinguish two documents.** The operator form carries the unit as a SQL
keyword and the function form as a string literal, so `date_add('HOUR', 2, now())` and
`now() + INTERVAL 2 HOUR` are the same query written two ways; the function form's unit literal SHALL
therefore be canonicalised to the catalog's lower-case spelling. Without it the DSL → SQL → DSL cycle
would not settle, because the renderer emits the keyword upper case while the reader lowers it.

**Units SHALL normalize onto the catalog's six.** `SECOND`, `MINUTE`, `HOUR`, `DAY`, `MONTH` and
`YEAR` map to the identically named unit. `WEEK` SHALL normalize to `7 day` and `QUARTER` to
`3 month` — both are exact identities for an offset, so nothing is lost, and the DSL is spared two
units that would each have a second spelling. A client writing `INTERVAL 1 WEEK` SHALL therefore read
back `date_sub('day', 7, …)`, which is the same query; the normalization is stated here so that
result is not read as a translation defect.

**What SHALL be rejected**, each with HTTP 400 and `error` `bad_request`:

- a **multi-field** interval qualifier (`INTERVAL '1-2' YEAR TO MONTH`, `INTERVAL '2:30' HOUR TO
  MINUTE`) — it names two offsets at once, which no single catalog call carries, and silently keeping
  one field would answer a different question than was asked;
- an interval whose amount is **not a whole number** of its own unit (`INTERVAL 2.5 HOUR`) or is
  **zero**;
- an interval operand that is not a literal, including one computed from a column;
- an interval as an operand of anything else — `*`, `/`, a comparison, an aggregate, or a projected
  select item. An interval is not a value this surface has a type for; only the two datetime
  operators consume one.

**Subtracting one timestamp from another SHALL be rejected**, in every spelling. A bare
`ts1 - ts2` is already refused by type checking, but the standard datetime-difference form
`(ts1 - ts2) HOUR` is not: it currently translates to the numeric `minus` of the two operands, which
renders as a difference in **seconds** and so answers a question about hours with a number of
seconds. The interval qualifier SHALL therefore be honoured by rejecting the form, since the subset
has no interval result type to carry it — a caller wanting an elapsed count gets a correct refusal
instead of a silently mis-scaled number.

The DSL → SQL renderer SHALL emit the **infix interval form** for `date_add`/`date_sub` (for example
`(request_time - INTERVAL 2 HOUR)`), not the function form: that is the portable spelling for the
external BI clients the rendered SQL is written for, and it reads back to the same document because
this requirement lowers it onto the same two functions.

#### Scenario: The last two hours, written the engine's way

- **WHEN** a client submits
  `SELECT event_id FROM dial_usage_log WHERE request_time >= now() - INTERVAL 2 HOUR`
- **THEN** translation succeeds, producing a filter comparing `request_time` against
  `date_sub('hour', 2, now())`

#### Scenario: Quoted and unquoted intervals agree

- **WHEN** a client submits the previous statement with `INTERVAL '2' HOUR`
- **THEN** it produces the identical `StructuredQuery`

#### Scenario: The function spelling agrees with the operator spelling

- **WHEN** a client submits
  `SELECT event_id FROM dial_usage_log WHERE request_time >= date_sub('hour', 2, now())`
- **THEN** it produces the same `StructuredQuery` as the `- INTERVAL 2 HOUR` form

#### Scenario: An upper-case unit literal is the same document, and round-trips

- **WHEN** a client submits
  `SELECT event_id FROM dial_usage_log WHERE request_time >= date_add('HOUR', 2, now())`
- **THEN** the document carries `date_add('hour', 2, now())`, and rendering it back to SQL
  (`(now() + INTERVAL 2 HOUR)`) and translating that again yields the identical document

#### Scenario: A signed interval normalizes to one document

- **WHEN** a client submits `... WHERE request_time >= now() + INTERVAL -2 HOUR`
- **THEN** translation succeeds and the document carries `date_sub('hour', 2, now())`, identical to the
  subtraction form's

#### Scenario: Week and quarter normalize to exact equivalents

- **WHEN** a client submits `... WHERE request_time >= now() - INTERVAL 1 WEEK` and
  `... WHERE request_time >= now() - INTERVAL 1 QUARTER`
- **THEN** the documents carry `date_sub('day', 7, now())` and `date_sub('month', 3, now())`
  respectively

#### Scenario: Interval arithmetic over a column and in projection

- **WHEN** a client submits `SELECT request_time + INTERVAL 1 DAY AS due FROM dial_usage_log`
- **THEN** translation succeeds, producing `date_add('day', 1, request_time)` aliased `due`

#### Scenario: A compound interval qualifier is rejected

- **WHEN** a client submits `... WHERE request_time >= now() - INTERVAL '1-2' YEAR TO MONTH`
- **THEN** translation fails with HTTP 400 and `error` `bad_request`, naming the multi-field qualifier

#### Scenario: A fractional or zero interval is rejected

- **WHEN** a client submits `... - INTERVAL 2.5 HOUR` or `... - INTERVAL 0 HOUR`
- **THEN** translation fails with HTTP 400 and `error` `bad_request`

#### Scenario: A computed interval is rejected

- **WHEN** a client submits a statement whose interval amount is a column reference
- **THEN** translation fails with HTTP 400 and `error` `bad_request`

#### Scenario: An interval reaching a non-datetime operator is rejected

- **WHEN** a client submits `SELECT INTERVAL 2 HOUR FROM dial_usage_log`, or a statement multiplying
  an interval by a number
- **THEN** translation fails with HTTP 400 and `error` `bad_request`

#### Scenario: Interval arithmetic round-trips through the renderer

- **WHEN** a DSL document carrying `date_sub('hour', 2, now())` is rendered to SQL and translated back
- **THEN** the rendered statement spells the offset as `- INTERVAL 2 HOUR` and the resulting document
  equals the original

#### Scenario: A timestamp difference stays out of the subset

- **WHEN** a client submits `SELECT request_time - _ingested_at AS d FROM dial_usage_log`
- **THEN** translation fails with HTTP 400 and `error` `bad_request`

#### Scenario: A qualified timestamp difference is rejected rather than mis-scaled

- **WHEN** a client submits `SELECT (request_time - _ingested_at) HOUR AS d FROM dial_usage_log`
- **THEN** translation fails with HTTP 400 and `error` `bad_request`, rather than succeeding with a
  difference expressed in seconds
