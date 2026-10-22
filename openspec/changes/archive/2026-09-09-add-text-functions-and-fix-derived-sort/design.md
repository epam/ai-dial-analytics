## Context

See proposal.md — Why. Three design-relevant facts about the current code:

- **The dropped-sort-key defect is a fold-order bug, not an executor limit.** `RelToStructuredQueryTranslator`
  folds a plan bottom-up into the linear block `Source → [Filter] → [Project] → [Aggregate] →
  [Filter(having)] → [Project] → [Sort] → [Project(trim)]`, carrying a DSL `Expr` per field ordinal.
  `sortCtx` captures each sort key as the `Expr` the field held **at that point**; a `Project` above the
  `Sort` then replaces `ctx.outputs` wholesale. `sortItems` resolves a key by `ctx.outputs.indexOf(expr)`,
  falling back to a bare `FieldExpr`'s name — so a *computed* key that the new projection does not carry
  resolves to nothing and the block is refused. `filterCtx`, `aggregateCtx` and `sortCtx` already **seal**
  the block into a derived table when they sit above a `Sort`; `projectCtx` is the one that does not.
- **The DSL cannot express such a statement in one block.** `SortItem` names an output or a source
  column; there is no sort-by-expression and no hidden select entry. So the fix has to change the block
  *shape*, not the sort resolution.
- **Two classes already hold the array rules that both surfaces read.** `ArrayValuedFunctions` owns the
  array-returning name set (`NAMES`) and the array-operand position (`ARRAY_OPERAND_FUNCTIONS` +
  `acceptsArrayOperand`), and `SqlProjectionOnlyTypes` delegates the array position to it. Adding a
  function to those two sets covers the DSL translator, the DSL→SQL renderer, the SQL→DSL translator and
  the aggregate pipeline at once.

Measured on the default branch (ee0b614) with the `dial_usage_log` fixture, `SqlQueryFrontEnd.plan` refuses:
`SELECT event_kind FROM dial_usage_log GROUP BY event_kind ORDER BY count(*) DESC LIMIT 5`,
the same shape inside `IN (…)`, `SELECT d FROM (… ORDER BY n DESC LIMIT 5) t`, and
`SELECT event_kind FROM dial_usage_log ORDER BY length(event_kind) LIMIT 5` — while
`ORDER BY total_tokens` (a plain column not projected) and
`SELECT count(*) … GROUP BY event_kind ORDER BY event_kind` both succeed.

## Goals / Non-Goals

**Goals**

- Accept every statement whose `ORDER BY` names an expression the `SELECT` list does not carry, without
  changing the translated shape of any statement accepted today.
- One rendering rule for "this value as text", reached by every new function, so the four functions
  cannot disagree about a null, a decimal's scale or an enum's label.
- Keep the DSL and SQL surfaces symmetric: every new function has one catalog name, one DSL rendering,
  and one SQL spelling that reads back to the same document.

**Non-Goals**

- No new DSL grammar. No sort-by-expression, no `CAST` node, no array literal.
- No general `CAST` support: only to a character type, and only as `to_string` — or, over an operand that
  is already text on this surface, as nothing at all (the identity cast it has always been).
- No `least`/`greatest`, no `ARRAY JOIN` clause, no change to the row-limit governance.
- No formatting control over `to_string` (no format string, no timezone argument).

## Decisions

### D1 — Seal the block when a projection drops a *computed* sort key

`projectCtx` translates the projection first, then — if the block is `sorted` and any sort key is
neither present in the new outputs nor a bare `FieldExpr` — seals the block folded so far into a
derived table and re-translates the projection against the sealed context. Re-translation is safe and
cheap: the `Project`'s `RexInputRef` ordinals index the sealed block's columns one-for-one, so the
second pass reads the same ordinals off `identity(columns)`.

The condition is deliberately **narrow on both counts**:

- *Only when the key is missing.* A projection that carries its sort key (`SELECT d, n FROM (… ORDER BY
  n …)`) still folds into one block, so nothing that translates today gains a derived level.
- *Only for a computed key.* A bare `FieldExpr` keeps using the existing fallback, which names the
  source column — that is how `SELECT event_id … ORDER BY request_time LIMIT 5` stays a single-block
  `SqlTranslation` today, and a seal there would turn a plain statement into a composite one and cost it
  its `/translate-sql` document for no reason.

*Alternatives rejected.* (a) Resolve the sort key by adding a synthetic select entry — changes the
statement's output arity, so it answers a different question. (b) Widen `SortItem` to carry an
expression — a wire-contract change to the DSL for a SQL-side bug, and it would need matching
group-key/`having` resolution. (c) Seal unconditionally above every `Sort` — makes ordinary statements
composite and drops their DSL document.

### D2 — The seal carries the statement's row bound **and its ordering** outward

A sealed block keeps its `ORDER BY`/`LIMIT`/`OFFSET`, and only the **outermost** block is governed
(`CompositeQueryBuilder` renders nested blocks under `LimitPolicy.NESTED`). Left alone, sealing would
move the caller's `LIMIT 500` inside and leave the outer block unbounded, so governance would apply
`DEFAULT_LIMIT` (100) and silently return 100 rows for a statement that asked for 500 — a wrong answer,
which is worse than today's 400.

So the fresh context gets `fetch` = the sealed block's `fetch` and `sorted = true`, with `offset`
deliberately **not** carried:

- Re-applying `LIMIT n` over a block that already yielded at most `n` rows is a no-op on content, and it
  is what makes `explicitLimit` true — no default clamp, no misleading `default_limit_applied` warning,
  and a `LIMIT` above `MAX_LIMIT` still rejected exactly as it is for a statement with no computed sort
  key.
- Re-applying the `OFFSET` would skip rows a second time. The offset belongs where the ordering it skips
  into is, which is inside.
- `sorted = true` is the honest flag for "this block carries a row bound": it makes any further filter,
  aggregate or sort above seal again, so nothing is ever applied *before* a bound that the statement
  wrote *after* it.

**And the collation is re-expressed on the fresh block.** Carrying only the bound would leave the
statement's `ORDER BY` inside a derived table, where its effect on the *returned* order is not
contractual: row order out of a subquery is unspecified, and ClickHouse's default-on
`query_plan_remove_redundant_sorting` strips an inner `ORDER BY` outright when the enclosing block has
none — which the no-`LIMIT` `HAVING` form (`… GROUP BY p HAVING count(*) > 1 ORDER BY count(*) DESC`)
is exactly. So `sealCarryingRowBound` also rebuilds each `SortEntry` as a `FieldExpr` naming the
sealed block's column for that key: every key came out of `ctx.outputs` (`sortCtx` reads them by
ordinal), so every one of them *is* a column of the sealed relation, named by the same `outputNames`
list the seal uses — which is why the names must be computed once and handed to `seal`, not twice.
Direction and null ordering travel unchanged; the inner `ORDER BY` stays where it is because it is what
the inner `LIMIT` selects the top rows with.

One hazard comes with naming a sealed column: the DSL resolves a `sort` name against the select
outputs before the source's columns, so an outer projection aliasing a *different* expression to that
name would order by the alias instead. `sortFieldName` therefore refuses that collision the way
`rejectAliasShadowingGroupColumn` refuses it for `group_by` — a wrong ordering is worse than a 400 —
and the guard covers the pre-existing plain-column case (`SELECT length(x) AS request_time … ORDER BY
t.request_time`) with it.

### D3 — One coercing `concat` **and** a `to_string`, not one or the other

The alternatives were a text-only variadic `concat` plus `to_string`, or a single coercing `concat`
with no `to_string`. Both were rejected in favour of taking `concat`'s coercion **and** naming it:

- A text-only `concat` cannot carry `'depth ' || 4`, and every label mixing a number with text would
  need an explicit wrap — a papercut on the most common labelling task, and it would make the `||`
  lowering (D4) fail on operand types for exactly the statements BI tools emit.
- A coercing `concat` alone leaves no way to render a *single* value as text. `concat(x)` would have to
  mean `to_string(x)`, which makes the arity rule ("two or more") arbitrary and gives one operation two
  spellings.

So `to_string(value)` is the coercion, `concat` renders each operand **by delegating to the same
helper**, and `array_to_string` renders each element with it too. The rule is written once in
`ExprTranslator`, which is what lets the specs state one table of renderings for all three.

**The two functions have deliberately different null rules, and that split was forced by D4.** The first
draft normalized null to `''` in the shared helper, so both functions were never-null. That is wrong for
`to_string`: it is the function a caller-written `CAST(x AS VARCHAR)` becomes, and a null-normalizing one
would have made `CAST(<nullable column> AS VARCHAR)` — which BI clients emit constantly — report `''`
where the column is absent, turning a cast into a value-inventing call. Making `to_string` preserve null
fixes that at the root and is the better contract anyway: a cast of nothing is nothing. (The narrower
text-to-text case that first motivated this is now unwrapped entirely, per D4, but the rule stands on the
nullable-non-text case on its own.)

So the never-null contract lives on the two functions that *compose* text, where one absent operand would
otherwise destroy a whole label: `concat` and `array_to_string` each wrap the shared rendering in
`ifNull(…, '')` themselves. `concat`'s rule is then exactly PostgreSQL's `concat()`, and its divergence
from `||`'s null propagation is stated in the spec rather than left to be discovered. It also keeps
`concat`'s column non-nullable, which keeps `INFORMATION_SCHEMA.IS_NULLABLE` honest for the BI generators
that wrap a nullable sort key in a null-ordering `CASE`.

**Rendering per type.** The shared helper emits `toString(<x>)` for every scalar except `boolean`, which
emits `if(<x>, 'true', 'false')`. The boolean branch exists because the
physical column may be `Bool` or `UInt8` and `toString` would then answer `true` or `1` for the same
DSL type — the same `UInt8`-vs-boolean divergence the catalog already fights with its `toBool` rule.
An explicit branch makes the advertised rendering independent of the physical type.

### D4 — Both inbound spellings are re-bound **before validation**, in one class

`||` parses as `SqlStdOperatorTable.CONCAT`, whose operand checker is string-only, so a mixed-type
concatenation fails in validation before any translator sees it — an operator-table entry cannot
change that, exactly as it cannot for `DATE_TRUNC`. A new `SqlTextRebinder`, run beside
`SqlDateTruncRebinder` in `SqlQueryFrontEnd`, swaps the operator on every parsed `||` call for the
ADAS `CONCAT`, whose signature is `(ANY, ANY[, ANY…])`. A left-associative chain becomes nested
`concat` calls, which is the same expression.

The same class rewrites a **caller-written** `CAST(<expr> AS <character type>)` into a
`TO_STRING_CAST(<expr>)` call. **Doing this on the `Rex` tree instead was implemented first and is
wrong** — worth recording,
because the failure is silent and the code there looks like the natural home for it. Calcite's implicit
type coercion *injects* a cast to a character type whenever a text-family operand is handed a non-text
argument, so lowering every `Rex` cast-to-character onto `to_string` made `not_empty(total_tokens)`
translate to `notEmpty(toString(total_tokens))` and succeed — silently widening every text function to
accept any type, against a shipped requirement that a non-text operand is a 400 naming its type. The only
tree that distinguishes the two is the pre-validation one, which contains exactly what the caller wrote.
An existing test caught it, which is the argument for keeping that test's scope as broad as it is.

The consequence is that the rewrite cannot see operand *types* (nothing is validated yet), so it cannot
tell a cast that needs rendering from a cast of text to text that needs nothing. **That is why it emits
`TO_STRING_CAST` — an internal marker operator — rather than `TO_STRING` itself.** The marker carries one
fact ("the caller wrote a cast to text here") to the one place that has the other fact (the operand's
type): `RexToExprTranslator` reads it as `to_string` over a non-text operand and as the operand alone
over a text one, which is the translation that statement always had. Lowering straight onto `TO_STRING`
was tried and rejected on measurement: it also rewrote `WHERE CAST(project_id AS VARCHAR) = 'p1'` into a
`toString(project_id)` comparison and `GROUP BY CAST(deployment AS VARCHAR)` into a group on `String`,
changing the document `/translate-sql` returns for statements that were already accepted, and putting a
computed expression on two `LowCardinality(String)` primary-key columns for no gain.

The marker is a *second* operator rather than a flag on `TO_STRING` because a caller-written
`TO_STRING(text_col)` must keep rendering `toString(...)`: the DSL→SQL renderer emits that spelling for
the DSL's own `to_string`, so unwrapping it would break the DSL→SQL→DSL round trip. It is registered in
`SqlQueryOperators.TABLE` because the validator resolves the swapped call by name like any other — which
also makes the name resolvable in a statement a *client* wrote, and `TO_STRING_CAST` is in neither the DSL
function catalog nor `GET /v1/queries/functions`. Leaving it spellable was first judged costless (it means
what `CAST(x AS VARCHAR)` means), but the subset's own rule is that a function outside the catalog is
rejected — the same rule that refuses `PLUS(a, b)` although `plus` *is* a catalog function — and an
undocumented third spelling that a text operand makes vanish into the bare column is exactly the kind of
surface that rule exists to keep out. So `SqlTextRebinder` refuses a caller-written marker: the check is
the first thing its walk does, before the rewrite introduces a marker of its own and before the shuttle
descends, so a marker call it sees there can only be the caller's, and no ordering with the rewrite can
make the guard fire on the rewrite's own output.

`SqlTextRebinder` is a **second class rather than a method on the existing re-binder**: that class's whole
javadoc is the `DATE_TRUNC` argument-order story, and folding an unrelated rule into it would make neither
readable. Both of its own rules are the same idea — a text spelling the grammar binds elsewhere — so they
share one walk. The cost is one extra tree walk over a parsed statement, not measurable next to
validation.

The renderer keeps emitting `concat(...)`/`to_string(...)`, so the round-trip stays single-valued and both
`||` and `CAST` are inbound spellings only — the same arrangement `%`/`MOD` already has.

### D5 — `array_to_string` and `array_slice`, and why not `array_join(array, delimiter)`

`array_join` is taken by row expansion, and its shipped spec refuses a second operand *on purpose* — the
arity is what makes Spark's same-named array-to-string function fail closed instead of being read as an
expansion with a stray literal. Overloading it now would retract that guarantee and give one name two
row-count behaviours. `array_to_string` is unambiguous, and is what Presto's spelling cannot be here.

`array_slice` joins **both** sets in `ArrayValuedFunctions`: `ARRAY_OPERAND_FUNCTIONS` (it consumes an
array at argument 0) and `NAMES` (it produces one). It is the first member of both, and that is what
makes `array_to_string(array_slice(group_uniq_array(x), 1, 6), ', ')` legal while
`concat(array_slice(...), 'x')` fails closed — no new guard, both rules already keyed on those sets.

`array_slice`'s element type follows its **operand**, unlike `map_keys`/`split_string`/`json_extract_array`
whose element is fixed at `string`. Both surfaces have a place that already makes this distinction for
`group_uniq_array` (`ExprFieldType.ofElementOfFirstArg` and
`RelToStructuredQueryTranslator.arrayJoinKind`), and both need one recursive case added, or
`array_join(array_slice(group_uniq_array(total_tokens), 1, 3))` types as text and the Arrow writer is
handed the wrong writer.

**Bounds are integer literals, and the delimiter a string literal** — the same choice `round`'s `digits`,
`split_string`'s delimiter and the `json_extract_*` path keys make. A computed bound would have to be
range-checked at runtime inside ClickHouse, where the error is the engine's; as a literal it is a clean
400 naming the argument. A `length` must be positive; an `offset` must be non-zero but **may be
negative** (counting from the end), because ClickHouse defines that and narrowing the engine for no
reason of ours is what the `array_join` design already argues against.

### D6 — Rendered SQL for each new function

| DSL call | Rendered ClickHouse |
|---|---|
| `to_string(x)` | `toString(x)`, or `if(x, 'true', 'false')` for a boolean — null-preserving (D3) |
| `concat(a, b, …)` | `concat(ifNull(<to_string(a)>, ''), ifNull(<to_string(b)>, ''), …)` |
| `array_to_string(a, d)` | `ifNull(arrayStringConcat(arrayMap(e -> ifNull(<to_string(e)>, ''), a), d), '')` |
| `array_slice(a, o, n)` | `arraySlice(a, o, n)` |

The `arrayMap` in `array_to_string` is not optional: `arrayStringConcat` is defined over
`Array(String)`, and a numeric or `Nullable` element would otherwise fail inside the server. The outer
`ifNull` gives the empty-string contract for a null array, matching `array_length`'s `ifNull(length(…), 0)`.
`<to_string(e)>` inside the lambda is the **same** rendering as the row above, boolean branch included
(one helper, called with the lambda variable in place of the operand template) — a bare `toString(e)`
would print `1`/`0` for the `Array(UInt8)` that `group_uniq_array` over any boolean-valued call
produces, which is the one place the two spellings of one value would disagree. The element type comes
from the same derivation that types `array_join`; an element whose type cannot be derived renders as
every non-boolean type does.
`array_slice` is rendered **bare**: a ClickHouse `Array(T)` column cannot be `Nullable`, so there is no
null to normalize, and there is no typed empty-array literal to normalize it to.

Every one of these is a plain-SQL jOOQ template on the CLICKHOUSE `DSLContext`, as the existing array
and JSON functions are. The DSL→SQL renderer needs no new case: it emits `name(args…)` for any function
without an arithmetic or ordered-set special case.

### D7 — Discovery and parity

Each function gets a `QueryFunctionCatalog` descriptor in alphabetical position (`array_slice`,
`array_to_string` after `array_length`; `concat` after `ceil`; `to_string` after `sum`).
`concat`'s second argument carries `variadic: true`, the shape `json_extract_*`'s path key already uses,
so "two or more" is discoverable rather than only documented. `QueryFunctionCatalogParityTest` then
holds the translator to every descriptor, which is the mechanism that keeps discovery from drifting.

## Risks / Trade-offs

- **A statement that translated to a DSL document now reports "no structured-query equivalent" on
  `/translate-sql`.** → Only for statements that returned **400** before, so no client loses a document
  it was getting. The refusal wording (`statements with joins, derived tables or subqueries execute but
  have no structured-query equivalent`) does not name this case, so it is widened to say that a
  statement nested to carry its `ORDER BY` expression is one of them — one constant, referenced by
  every test that asserts it.
- **The seal adds a derived level, and with it an alias and a rendering pass.** → Only for statements
  that were refused outright before; ClickHouse flattens such a subquery, and the runtime verification
  step measures the executed statement rather than assuming it.
- **`to_string` over a `decimal` carries the column's scale** (`1.500000`), which is not what a label
  author wants. → Documented in the spec table rather than papered over; `round(x, 2)` before
  `to_string` is the fix, and inventing a trimming rule would make one function's output depend on a
  format nobody declared.
- **`toString` over a `timestamp` yields the store's own text, not ISO-8601 with a `T`.** → Same
  treatment: stated in the spec and measured at runtime. A format argument is a non-goal.
- **`concat`'s null-as-empty diverges from standard `||`.** → Stated in the spec and justified by the
  catalog's existing text contracts. The divergence is visible in exactly two places (`concat`'s and
  `array_to_string`'s own `ifNull` wrappers) rather than hidden inside the shared rendering, where it
  would also have applied to `to_string` and broken the cast equivalence D4 depends on.
- **A `||` re-bind changes an operator the grammar produced.** → Scoped to calls whose operator name is
  `||`; array/multiset concatenation is not that operator, and a projection-only operand still fails
  closed on the existing per-argument rule.
- **A caller-written cast of text to text could have started rendering `toString(col)` where it used to
  render the bare column.** → It does not: the marker operator D4 describes lets the identity case be
  unwrapped again once the operand has a type, in every position. The first implementation *did* wrap it,
  and the values were indeed identical — but only the select-list case was measured, and in a `WHERE` or
  a `GROUP BY` the wrapper lands on `project_id`/`deployment`, the two leading columns of the table's
  `ORDER BY` and both `LowCardinality(String)`. Nothing about "the values are identical" covers a
  computed expression on the primary-key side of a comparison or a group key that abandons a
  low-cardinality dictionary, and casts in those positions are emitted by exactly the clients the
  rewrite exists for. Front-end tests now pin all three positions.
- **The spec's rendering table is a claim about ClickHouse.** → Every row of it is verified against the
  `local_env` ClickHouse in the runtime-verification task, and the spec is amended to what was measured
  rather than the reverse.

## Migration Plan

None. No migration, no config key, no credential, no catalog change; the change is additive to the
function catalog plus one fold-order fix. Rollback is the revert of the commit — a statement using a new
function then fails validation again, and nothing persisted refers to the new names except a saved query
a caller created in the meantime (which would fail validation on execution, the same as any saved query
naming a function the server no longer has).
