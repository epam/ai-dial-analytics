## Context

See `proposal.md` — Why. The constraints that shape the approach are all pre-existing:

- **One translator, two surfaces.** `ExprTranslator` renders the DSL to jOOQ/ClickHouse;
  `RexToExprTranslator` lowers Calcite's `Rex` tree onto the *same* DSL `FnExpr` vocabulary;
  `StructuredQueryToSqlRenderer` goes back the other way. A function therefore has to be added in
  three places or the surfaces diverge, and `QueryFunctionCatalogParityTest` already enforces that
  the discovery catalog and the translator agree.
- **The SQL front end is Calcite 1.42 with the Babel parser**, `SqlQueryOperators.TABLE` chained over
  `SqlStdOperatorTable.instance()`. Anything the std table validates but the Rex translator does not
  know becomes `SQL construct 'X' cannot be expressed in the structured query DSL` — a 400.
- **The enrichment and pipeline paths reuse the query front end** (`ExprSqlValidator`,
  `FilterSqlValidator`), which is why widening the query catalog widens them too, and why the
  determinism gate belongs inside those two classes rather than in each of their callers
  (`EnrichSpecValidator`, `AggregateSpecValidator`, `PipelineFilters`, `ScanService`,
  `GroupFetchService`, `MaterializeService`, `AggregateRefreshService`).

**What Calcite actually does was measured, not assumed.** Probing the existing front end established
the facts the design rests on:

| Probe | Result |
|---|---|
| `now()`, `today()` | `No match found for function signature` — not in the operator table |
| `CURRENT_TIMESTAMP`, `LOCALTIMESTAMP` | validate, then reach the Rex translator as an unhandled construct |
| `ts - INTERVAL '2' HOUR` (standard spelling) | arrives as the `-` operator with `(ts, <interval literal>)` — a plain `RexLiteral` of 7200000 |
| `ts - INTERVAL 2 HOUR` (unquoted, ClickHouse spelling) | **parses** under Babel, but arrives as `-(ts, *(2, INTERVAL 1 HOUR))` — the bare count is a *multiplier*, and nothing folds it before the Rex tree exists |
| `ts + INTERVAL 2 HOUR` | arrives as `+` with the datetime **first**, whatever the written order |
| `INTERVAL 1 WEEK` / `1 QUARTER` | already lowered by Calcite to `INTERVAL_DAY` / `INTERVAL_MONTH` |
| `INTERVAL '1-2' YEAR TO MONTH` | arrives as a compound `INTERVAL_YEAR_MONTH` literal |
| `INTERVAL 1000 DAY`, `INTERVAL 2.5 HOUR`, `INTERVAL -2 HOUR` | all parse — no precision or sign gate to inherit |
| `2 * INTERVAL 1 HOUR`, `TIMESTAMPADD(HOUR, -2, ts)` | the same multiplication shape as the unquoted spelling |

**The second row is the one that shaped the code.** The first implementation matched a plain interval
literal, which accepted the *standard* spelling and refused the *ClickHouse* one — backwards for a
surface whose callers write ClickHouse. `intervalMagnitude` therefore resolves multiplication
recursively rather than pattern-matching at one depth, which is also what makes the two folded forms in
the last row work as a consequence rather than by intent.
| `ts1 - ts2` | rejected by type checking |
| `(ts1 - ts2) HOUR` | **accepted today**, silently lowering to numeric `minus` — the defect the proposal fixes in passing |

## Goals / Non-Goals

**Goals**

- One document per meaning, on both surfaces, with a stable DSL → SQL → DSL round trip.
- The determinism guarantee the enrichment path rests on stays derived from the catalog, so adding a
  future non-deterministic function needs no second edit to keep the gate closed.
- No new rejection of anything that works today, except the mis-scaled `(ts1 - ts2) HOUR`.

**Non-Goals**

- A caller-chosen time zone. Everything here is UTC, for the reasons `dateBin`'s javadoc already
  gives at length; a zone argument stays an additive change.
- Server-side resolution of a saved query's relative `period` token, or any new saved-query field.
- An interval *type* in the DSL. An interval is consumed where it is written and never carried as a
  value — which is why `date_add`/`date_sub` take `(unit, amount)` rather than an interval operand.

## Decisions

### 1. Two offset functions with a positive amount, not one with a signed amount

`date_add(unit, amount, ts)` and `date_sub(unit, amount, ts)`, `amount` a **positive** integer
literal, direction in the name. The name and argument order are borrowed from ClickHouse's own
`dateAdd(unit, value, date)`, and it keeps the round trip single-valued: SQL `+ INTERVAL -2 HOUR` and
`- INTERVAL 2 HOUR` normalize to one document, which renders back to one statement.

> **Correction (post-merge, measured against clickhouse-server 26.3).** An earlier draft of this
> section claimed the DSL call is "literally valid ClickHouse". It is not. ClickHouse does accept the
> same three arguments in the same order — `date_add(HOUR, 2, now())` and `dateSub(HOUR, 2, now())`
> both evaluate — but it takes the unit as a **bare interval keyword**. With a *quoted* unit,
> `date_add('hour', 2, now())`, the engine resolves `date_add` to its two-argument `plus` alias and
> fails: `Number of arguments for function plus doesn't match: passed 3, should be 2`. The DSL uses a
> string literal because it has no keyword type, matching `date_trunc('day', …)` and
> `date_bin(5, 'minute', …)`. Nothing in the implementation depended on the stronger claim — the
> renderer emits the infix `INTERVAL` form, which *is* native and is runtime-verified — so only the
> rationale was wrong, not the design.

*Rejected:* a single `date_add` with a signed amount — it needs negative literals, renders as the
ugly and rarely-written `+ INTERVAL -2 HOUR`, and gives two documents for one meaning unless the sign
is normalized anyway. *Rejected:* an `interval` argument kind on the DSL side — it would add a value
type the DSL has nothing else to do with.

### 2. Six units, with `week` and `quarter` normalized away

Accepted units are `second`, `minute`, `hour`, `day`, `month`, `year` — exactly the base units
Calcite's interval `SqlTypeName`s carry. `WEEK` and `QUARTER` remain writable in SQL and normalize to
`7 day` / `3 month`, which is what Calcite has already done to them by the time the Rex tree exists.

The asymmetry with `date_trunc` (which does accept `week`/`quarter`) is the point: for *truncation*
those units name a calendar alignment nothing else expresses, while for an *offset* they are exact
identities. Admitting them would give one offset two spellings and cost round-trip stability —
`date_sub('week', 1, …)` → `- INTERVAL 1 WEEK` → `date_sub('day', 7, …)` is a round trip that
changes the document.

*Rejected:* accepting them in the DSL and rendering `INTERVAL 1 WEEK`. *Rejected:* normalizing every
day-time interval to its finest unit (2.5 hours → 150 minutes), which makes the returned document
depend on the magnitude rather than on the written unit.

### 3. Interval magnitude is resolved to a constant and divided back into its unit

A Calcite interval literal carries a `BigDecimal` in the qualifier's base unit — milliseconds for
day-time qualifiers, months for year-month ones. `INTERVAL '2' HOUR` is therefore `7200000` typed
`INTERVAL_HOUR`; `INTERVAL 1 WEEK` is `604800000` typed `INTERVAL_DAY`. The lowering divides the
magnitude by the unit's base-unit size and requires an exact, non-zero, integral quotient; anything
else (`INTERVAL 2.5 HOUR`, `INTERVAL 0 HOUR`) is a 400, and the quotient is additionally range-checked
against `int` so an oversized offset is refused as an offset rather than later as a malformed literal.
A compound qualifier (`INTERVAL_YEAR_MONTH`, `INTERVAL_HOUR_MINUTE`, …) is rejected by *enumerating
the six accepted `SqlTypeName`s* rather than by testing for compound-ness, so a qualifier nobody
anticipated fails closed.

**Two Rex shapes have to be read, not one** — the finding in the probe table's second row. The
standard spelling yields a plain literal; the unquoted ClickHouse spelling yields
`*(count, INTERVAL 1 unit)`, unfolded. So the magnitude is resolved *recursively* through
multiplication (either operand order, unwrapping the unary minus that `INTERVAL -2 HOUR` parses its
count as) rather than matched at one depth. `2 * INTERVAL 1 HOUR` written by hand and the interval
`TIMESTAMPADD` lowers to are the same shape, so they work as a consequence rather than by intent.
What bounds the whole thing is the requirement that the operand reduce to a **constant**: a genuinely
computed offset (`INTERVAL <column> HOUR`) resolves to nothing and is refused, which is the property
that matters, since the DSL declares a unit and amount as literals so they can be checked once before
the statement runs.

### 4. `now()` renders `now64(3, 'UTC')`

Millisecond precision and an explicit zone, matching `dial_usage_log.request_time`
(`DateTime64(3, 'UTC')`). The instant is zone-independent, but the resulting *type* is not, and the
type is what `toStartOfInterval` aligns by — so `date_trunc('day', now())` cuts the day at UTC
midnight rather than at whatever the ClickHouse server's configured zone happens to be. This is the
same reasoning `ExprTranslator.dateBin`'s javadoc sets out; `now()` joins it rather than introducing
a second convention. Second-precision `now('UTC')` would compare a coarser value against a
millisecond column, which is a silent half-second of skew at a window edge.

ClickHouse evaluates `now64` once per query, which is what makes the spec's "one execution observes
one instant" true without ADAS doing anything.

### 5. The clock has three inbound SQL spellings and one rendering

`now()` is registered as a niladic `SqlFunction` in `SqlQueryOperators`; `CURRENT_TIMESTAMP` and
`LOCALTIMESTAMP` are already validated by the std table and are mapped in `RexToExprTranslator` by
operator name. The renderer emits `now()` only.

`CURRENT_DATE` stays rejected — it is a truncation, not a clock, and quietly answering it with a
timestamp would be worse than refusing it.

### 6. The determinism gate is a catalog property checked in the two shared validators

`QueryFunctionDto` gains a `deterministic` member, emitted on every entry (a client cannot otherwise
distinguish "deterministic" from "an older service"). `QueryFunctionCatalog` exposes the set of
non-deterministic names derived *from the descriptors*, and `ExprSqlValidator` and
`FilterSqlValidator` walk the translated `Expr` / `FilterNode` tree for one, raising the
`PipelineValidationException` (422 `pipeline_validation_failed`) they already raise. Every pipeline
caller inherits it because every one funnels through those two classes.

*Rejected:* a hand-written denylist beside the catalog — the exact thing `ExprSqlValidator`'s javadoc
argues against, and it would rot silently. *Rejected:* gating at each caller — eight call sites, and
a ninth added later would miss it. *Rejected:* gating inside `SqlQueryFrontEnd` — that would refuse
`now()` on the query surfaces too, which is the opposite of the point.

The tree walk is a new traversal in each validator; `ExprColumnCollector` already walks an `Expr`
tree for column names, so the shape is established. The walk must also descend into a `CaseExpr`, a
`WindowExpr`, or `CASE WHEN x THEN now() END` slips through. It needs **no** subquery arm: both
validators embed their fragment through `SqlQueryFrontEnd.translate`, which refuses every composite
plan, and a `SubqueryNode`/`ScalarSubqueryExpr` is only ever built for one — so a subquery cannot be
present in a tree that reaches here.

### 7. The gate is total across fragments rather than per-position

Discussed in the `enrichment-registry` delta. Briefly: a narrower rule is arguable for a `filter` or
a readiness `signal`, but each needs its own account of what a moving predicate does to a cursor, to
a full recompute, and to the audit trail — so the whole family is refused until one is worked through
deliberately. It narrows nothing that works today, since no non-deterministic function existed in the
catalog to be stored in an existing declaration.

### 8. `(ts1 - ts2) HOUR` becomes a 400

The `-` branch of `RexToExprTranslator` is the code this change is editing anyway. Today a datetime
difference with an interval qualifier reaches it as numeric `minus` and renders a difference in
seconds — an answer ~3600× wrong for the question asked, with nothing to flag it. Rejecting it is a
behavior change to something already broken, not a narrowing of working surface, and the alternative
(implementing `dateDiff`) is a separate feature nobody asked for.

## Risks / Trade-offs

- **Calcite might constant-fold `CURRENT_TIMESTAMP` into a literal** under some planner
  configuration, turning a moving window into a frozen one with no error → the probe shows it
  arriving as a live `RexCall` on this pipeline, and a translation test asserts the produced document
  contains an `FnExpr` named `now`, so a future Calcite upgrade that starts folding fails the build
  rather than shipping a frozen window.
- **A niladic custom function may be treated as constant by a planner rule** and hoisted → same
  mitigation; additionally `now()` is never marked `dynamic` in a way that invites evaluation, since
  there is no implementation to evaluate.
- **`now()` in an aggregate query might be accepted by Calcite and refused by the DSL** (or the
  reverse), leaving one document valid on only one endpoint → a test asserts the two surfaces agree
  on a projected `now()` under `GROUP BY`, and whichever way they land, they land together.
- **A moving window defeats result caching or makes two "identical" runs differ**, which could
  surprise a caller comparing two exports → inherent to the feature and the reason it was asked for;
  the mitigation is the single-instant-per-execution contract, so a *single* result is internally
  consistent.
- **Clock skew between ClickHouse and anything else** — a row's `request_time` is written by the
  producer, while `now()` reads the store's clock, so a badly skewed producer can make "the last two
  hours" look empty or doubled → unchanged from writing a literal bound by hand, and reading the
  store's clock (rather than ADAS's) is the choice that keeps the comparison internally consistent.
- **`week`/`quarter` normalization looks like data loss** to a client that wrote `INTERVAL 1 WEEK`
  and reads back `date_sub('day', 7, …)` → stated in the spec as a normalization with an exactness
  argument, and surfaced in `docs/sql-reference.md`.

## Migration Plan

None. No migration, no configuration property, no credential. The change is additive on the query
surfaces; the one removal (`(ts1 - ts2) HOUR`) cannot be stored in a saved query without having been
accepted by the same front end, and a body relying on it was returning wrong numbers rather than
working. Rollback is a revert: stored bodies calling `now()` would then fail validation on execution
the way any unsupported function does, with the ordinary 400.
