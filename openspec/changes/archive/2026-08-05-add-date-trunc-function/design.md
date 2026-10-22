## Context

`TruncUnit` (`service/pipeline/model/TruncUnit.java`) declares four units and supports three. `MONTH`
carries a null `intervalKeyword` and an empty `targetTypes`, and its javadoc says why: "a month has no
fixed width, so it would need a calendar-truncating function the catalog does not carry, and silently
treating it as 30 days would produce buckets that are wrong in a way nobody would notice." That is still
the right diagnosis; this change carries the function.

The catalog is closed and doubly enforced. `QueryFunctionCatalog` is the discovery source of truth served
by `GET /v1/queries/functions`; `ExprTranslator.toFunction` is what actually accepts and renders;
`QueryFunctionCatalogParityTest` binds them, instantiating every advertised descriptor and asserting the
translator accepts it. The SQL front end has its own operator table (`SqlQueryOperators.TABLE`), a reverse
mapping (`RexToExprTranslator.callExpr`) and a result-type map (`RelToStructuredQueryTranslator.fnKind`),
because SQL statements are lowered to the same DSL.

The pipeline compiler does not have its own truncation: `AggregateCompiler.groupExpr` builds a
`FnExpr("date_bin", [1, unit, column])` and hands it to `ExprTranslator`, deliberately, so a bucket is
whatever the shared catalog says it is.

## Goals / Non-Goals

**Goals:**

- Make calendar truncation expressible on all three read surfaces (DSL, REST SQL, Flight SQL).
- Make `month` a real aggregate bucket unit.
- Keep the catalog's single-source discipline: one function, advertised, translated, parity-tested, and
  in the spec's function table.

**Non-Goals:**

- `quarter` and `year` as *bucket* units. The function accepts them because calendar truncation naturally
  does; `TruncUnit` gains only `month`, because a bucket unit also has to justify itself against refresh
  cost and target-type rules, and nobody has asked for quarterly rollups.
- An `amount` multiplier on `date_trunc` (see Decisions).
- Approximate or non-additive measures (`uniq`, `quantile`, `count distinct`, percentiles as measures) —
  that is the separate measure-vocabulary change and needs the non-additivity decision first.
- A caller-chosen alignment zone.

## Decisions

**A separate function, not a wider `date_bin`.** `date_bin`'s contract is fixed-width and epoch-aligned,
stated that way in the `structured-query` spec and relied on by its "aligned to a fixed epoch origin"
promise. Admitting `month` would falsify the contract for every existing caller rather than extend it.
Two functions with distinct promises is the honest shape, and it matches PostgreSQL, where `date_bin` and
`date_trunc` coexist for exactly this reason.

**`date_trunc` takes no amount.** A period of *n* calendar months has no fixed width, so there is no
epoch grid for a multiplier to step along; `n = 3` would also silently mean something different from
`quarter` (which is calendar-quarter-aligned). Omitting the argument keeps every `date_trunc` call
unambiguous, and `date_bin` still serves anyone who wants "every 90 days".

**Unit set is the PostgreSQL-shaped union, not calendar-only.** `date_trunc` accepts `second` … `year`,
overlapping `date_bin` on the fixed-width units. The alternative — restricting it to `{month, quarter,
year}` so the two functions partition cleanly — makes `date_trunc('day', …)` an error, which is exactly
the call a Postgres-fluent caller or a BI tool writes first. The overlap is not ambiguous: both render
the same `toStartOfInterval(col, INTERVAL 1 <unit>, 'UTC')` and agree by construction.

**Render through `toStartOfInterval`, not `toStartOfMonth`.** One rendering shape for all units keeps the
translator to a single template and keeps the pinned `'UTC'` argument, which `toStartOfMonth` also takes
but with different arity per unit. It also keeps the result typed `DateTime('UTC')`, which is what makes
the compiler's `+ INTERVAL 1 MONTH` pruning step land in the right calendar.

**Register on the SQL surface, not DSL-only.** The plan doc scoped 1a as catalog + translator, but
`StructuredQueryToSqlRenderer.function` emits any catalog function generically as `name(args)`, so a DSL
query translated to SQL by `POST /v1/queries/translate` would render `date_trunc(...)` that the SQL front
end then refuses — a round-trip break introduced by adding the function. It also gives Grafana/Power BI
the spelling they already emit.

**`date_trunc` needs a parse-time rebind, not just an operator-table entry — this is the one genuinely
surprising part of the change.** The initial design assumed the operator table would carry it the way it
carries `date_bin`, on the strength of `SqlQueryOperators.TABLE` being chained ahead of the standard
table. That is wrong, and the code comment asserting it has been corrected. `DATE_TRUNC` never reaches
the operator table at parse time: the Babel grammar has a dedicated `DateTruncFunctionCall()` production
that hard-binds every occurrence to `SqlLibraryOperators.DATE_TRUNC` — BigQuery-shaped `(date, unit)` —
before any table is consulted. Chaining cannot beat the grammar.

What makes the fix cheap is that the production is *more permissive than the operator it builds*: its
second argument is `TimeUnit() | Expression`, so `date_trunc('month', request_time)` parses cleanly and
yields the operands in the caller's order. Only the operand type-checker then rejects it — and it does so
via `handleUnresolvedFunction`, whose fallback message ("Invalid number of arguments … Was expecting 2
arguments") names an arity that is not the problem, which is what made this expensive to diagnose. The
tree is already correct; only the bound operator is wrong. `SqlDateTruncRebinder` swaps it, in the same
pre-validation slot as `SqlStarExpander` and `SqlSelectAliasInjector`.

**Both halves are required.** The rebind fixes parse-time binding; the operator-table entry is still
needed because the validator re-resolves a function by name against the table when deriving its type.
With the swap and no entry, validation fails with "No match found for function signature". This is the
only operator in the front end needing both, and both files say so.

**The rebind fires only on a character-literal first operand** — the DSL's shape, which cannot be
Calcite's (whose first operand is the date). A statement written in Calcite's own order is left as
parsed rather than silently reinterpreted with its arguments transposed. That is the failure mode worth
ruling out: both orders are individually plausible and the wrong one returns a plausible timestamp.

**Compile every bucket through `date_trunc`, not just `month`.** The alternative is a branch on
`unit.isCalendar()`. Compiling all four uniformly removes the branch and the synthetic `amount = 1`
literal, and it is safe because the rendered ClickHouse is byte-identical for hour/day/week —
`date_bin(1, 'day', c)` and `date_trunc('day', c)` both emit
`toStartOfInterval(c, INTERVAL 1 DAY, 'UTC')`. No stored rollup, cursor, or bucket boundary moves. The
observable difference is the text of `preview`'s rendered statement, and tests that assert `date_bin(`
in compiled pipeline SQL.

**`isSupported()` stays even though every unit is now supported.** It is the seam the validator and the
error contract route through, and the next unsupported unit (should one be declared for the same
422-not-400 reason) needs it. Deleting it would trade a trivially-true method for a re-litigation later.

**The error contract's worked example moves off `month`.** "A declared-but-unimplemented value reports
under this capability's code" cites `month` as its example; `month` becomes implemented, so the scenario
would assert something false. It re-points at an unimplemented `kind` and `incremental` freshness, both
of which remain declared-but-unimplemented. The rule itself is unchanged — only its illustration.

## Risks / Trade-offs

- **Two functions that overlap on five units** → mitigated by them agreeing exactly, and by the spec
  stating the division of labour (multiplier vs calendar) in one sentence. The cost of the alternative
  (rejecting `date_trunc('day', …)`) is paid by every caller, every time.
- **Calcite's own `DATE_TRUNC` has reversed operands** → handled by the narrow rebind above, and covered
  by explicit tests that `date_trunc('month', request_time)` lowers to the DSL call and that
  `date_trunc(request_time, 'month')` does not silently succeed.
- **A Calcite upgrade could change the `DateTruncFunctionCall()` production or the operator's type
  checker** → the rebind would then either stop firing or become unnecessary. Both surface as a test
  failure (the lowering test and the reversed-operand test bracket the behaviour), not as silent
  misbehaviour.
- **`quarter`/`year` accepted by the DSL but not as bucket units** → an asymmetry a caller can hit. It is
  stated in both specs, and widening `TruncUnit` later is purely additive (add the constant; the catalog
  already translates it).
- **Test churn is wider than the code change** → `QuerySchemaControllerTest` asserts the exact catalog
  list and positional indices, and pipeline compiler tests assert `date_bin(` in rendered SQL. Expected,
  mechanical, and caught by `clean build` rather than at runtime.
- **`toStartOfInterval` with `INTERVAL 1 QUARTER`** → supported by ClickHouse, but it is the one unit
  nothing in the codebase exercises today; the Flight SQL live-rendering test is the place to prove it
  alongside `month`.
