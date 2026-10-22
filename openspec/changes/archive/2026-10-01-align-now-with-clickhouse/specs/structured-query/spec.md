## MODIFIED Requirements

### Requirement: Relative time expressions

The function catalog SHALL additionally contain three scalar functions that together express a window
relative to the moment a query runs, so a **stored** query can mean "the last two hours" rather than
two particular instants:

| Function | Group | Arity / signature | Returns |
|---|---|---|---|
| `now` | scalar | `now()` (0 args) | timestamp |
| `date_add` | scalar | `date_add(unit, amount, timestamp)` (3 args) | timestamp |
| `date_sub` | scalar | `date_sub(unit, amount, timestamp)` (3 args) | timestamp |

**`now()`.** The function SHALL take no arguments; a call carrying any argument SHALL be rejected with
HTTP 400 and `error` `bad_request`. Its value SHALL be the current instant **in UTC at millisecond
precision**, and SHALL be read from the **data store's** clock at execution rather than from this
service's, because the comparison it takes part in is evaluated there — a caller reconciling a result
against the store's own clock must not be shown a third one. Its declared return type is `timestamp`,
so it is comparable with a `timestamp` column without a cast.

**What one statement observes is what ClickHouse observes.** Each `now()` **call site** SHALL yield
one value for the whole executed statement — the same value in every row it returns and every row it
filters — and SHALL NOT be re-read per row. Separate call sites SHALL be evaluated independently, as
ClickHouse evaluates them: two `now()` calls in one statement MAY yield values one or more
milliseconds apart when they straddle a clock tick. This service SHALL NOT coordinate them — it SHALL
NOT substitute a literal, an injected clock or a bound parameter for the call — because a ClickHouse
query must behave here exactly as it does in ClickHouse.

The consequence for callers is the one ClickHouse already has. A window written with two calls —
`request_time >= date_sub('hour', 1, now()) AND request_time < now()`, or in SQL
`request_time >= now() - INTERVAL 1 HOUR AND request_time < now()` — MAY see its two bounds a
millisecond apart, so a row stamped exactly at an edge can fall in or out of the window by that margin,
and a query filtering on one call and projecting another MAY report a projected instant that differs
from the one its filter bound was computed from by the same margin. A caller that needs both bounds
from one instant has to supply that instant itself, as a literal; two calls are not guaranteed to
agree. An `include_total` count is a
**separate** statement (see "Response envelope") and SHALL evaluate its own `now()`, so its total MAY
be taken against a window that ended a moment later than the page's.

**`date_add` / `date_sub`.** Both SHALL take exactly three arguments — a `unit` string literal, an
`amount` integer literal, and a `timestamp` expression — in that order. The name and order are borrowed
from the underlying engine's own function of the same name, which takes the same three in the same
order; the borrowing is deliberately inexact, because the engine spells the unit as a bare interval
keyword while this DSL spells it as a **string literal**, as every other unit-taking function here does
(`date_trunc('day', …)`, `date_bin(5, 'minute', …)`). A call written for this DSL is therefore not
itself valid engine SQL, and nothing depends on it being so: the rendered statement carries the infix
interval form, not the function. What the shared order buys is one less thing to re-learn when moving
between the two. Any other arity, a non-literal `unit` or
`amount`, an `amount` that is not a positive integer, or a `unit` outside the set below SHALL be
rejected with HTTP 400 and `error` `bad_request` naming what was expected. `date_add` SHALL return the
operand advanced by `amount × unit` and `date_sub` the operand moved back by it; the direction is
carried by the function name, which is why a negative `amount` is refused rather than silently
inverting the call.

The accepted `unit` values SHALL be exactly `second`, `minute`, `hour`, `day`, `month`, `year`. This is
**narrower than `date_trunc`'s** unit set, deliberately: `week` and `quarter` name a *calendar
alignment* that no other unit can express, which is why `date_trunc` needs them, but as an *offset* one
week is exactly seven days and one quarter exactly three months, so admitting them would give one
operation two spellings and cost a stable round trip for nothing.

`second`, `minute`, `hour` and `day` SHALL be fixed-width offsets. `month` and `year` SHALL be
**calendar** steps evaluated in UTC — one month before 31 March is 28 February (or 29), not a fixed
number of days — matching the underlying engine and PostgreSQL, and differing from a day-count offset
in exactly the cases where a day count is the wrong answer.

**Legal position.** All three SHALL be legal wherever any other scalar call is legal — in `filter`, in
the select list, in `having`, inside an aggregate's arguments, inside a `window` expression, and as a
sort key through the select entry's alias.

In an aggregate query, a projected `now()` SHALL be treated as the **constant it is**: each call site
yields one value for the whole statement, so the call needs no `group_by` entry, exactly as a projected
literal needs none, and unlike a projected column — which is still rejected as ungrouped. An offset
over a *column* is not constant and is grouped like any other computed column. Both query surfaces
SHALL agree on this, since one document must not be accepted on one endpoint and refused on the other.

**Composition is the point.** No further function is added for "today", "yesterday" or "the start of
last month": `date_trunc('day', now())` is today's start, `date_sub('day', 1, date_trunc('day',
now()))` yesterday's, and the catalog stays closed over one clock and one offset rather than growing a
vocabulary of named periods.

#### Scenario: A relative window filters on the last two hours

- **WHEN** a query filters `request_time` `>=` `date_sub('hour', 2, now())`
- **THEN** the query is accepted and returns only rows whose `request_time` falls within the two hours
  preceding execution

#### Scenario: The same query run later covers a later window

- **WHEN** the identical stored body of the previous scenario is executed again at a later time
- **THEN** the window it covers has moved with the clock, and no part of the request had to be rewritten

#### Scenario: now() takes no arguments

- **WHEN** a query calls `now('UTC')` or `now(0)`
- **THEN** the response is HTTP 400 with `error` `bad_request`

#### Scenario: A non-positive or fractional amount is rejected

- **WHEN** a query calls `date_sub('hour', 0, now())`, `date_sub('hour', -2, now())`, or
  `date_sub('hour', 1.5, now())`
- **THEN** the response is HTTP 400 with `error` `bad_request` stating that `amount` must be a positive
  integer literal

#### Scenario: A non-literal unit or amount is rejected

- **WHEN** a query supplies `date_sub`'s `unit` or `amount` as a field reference or a nested call
  rather than as a literal
- **THEN** the response is HTTP 400 with `error` `bad_request`

#### Scenario: An unsupported unit is rejected and names the accepted set

- **WHEN** a query calls `date_sub('week', 1, now())` or `date_sub('fortnight', 1, now())`
- **THEN** the response is HTTP 400 with `error` `bad_request` listing the accepted units, which do not
  include `week` or `quarter`

#### Scenario: A month offset steps the calendar, not a day count

- **WHEN** a query evaluates `date_sub('month', 1, <a timestamp of 31 March>)`
- **THEN** the result is the last day of February of that year, not 3 March

#### Scenario: The offset composes with the truncation functions

- **WHEN** a query groups by `date_bin(5, 'minute', request_time)` and filters
  `request_time >= date_sub('day', 1, date_trunc('day', now()))`
- **THEN** the query is accepted, the filter bounds the scan from the start of yesterday, and the
  buckets are unaffected by the filter's own expression

#### Scenario: A projected clock needs no group-by entry, while a column still does

- **WHEN** an aggregate query projects `now()` alongside `count()` with no `group_by`
- **THEN** the query is accepted on both surfaces — the call is a constant within the statement — while
  the same query projecting a column instead of `now()` is still rejected as ungrouped

#### Scenario: Each now() call site is one value across every row

- **WHEN** a row query over several rows projects `now()` twice, as `a` and as `b`
- **THEN** every row carries the same `a` and every row carries the same `b`; `a` and `b` are each the
  execution-time instant and MAY differ from each other by a clock tick, exactly as the same statement
  does in ClickHouse

#### Scenario: Two-bound windows are not coordinated by this service

- **WHEN** a query filters `request_time >= date_sub('hour', 1, now()) AND request_time < now()`
- **THEN** the statement sent to the data store carries two independent `now64(3, 'UTC')` calls, with no
  literal or parameter substituted for either, so its bounds are whatever the data store's two
  evaluations return
