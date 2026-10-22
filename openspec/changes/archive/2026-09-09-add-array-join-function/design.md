## Context

See proposal.md — Why. What shapes the design is the shape of the existing translation layer:

- The DSL is a closed function catalog over a sealed `Expr` hierarchy. Every function is one branch
  of `ExprTranslator#toFunction`, one descriptor in `QueryFunctionCatalog`, and — for anything that
  must round-trip — one operator in `SqlQueryOperators`, one case in `RexToExprTranslator`, and one
  rendering in `StructuredQueryToSqlRenderer`.
- `array`/`object`/`map` fields are **projection-only**, with one positional carve-out: argument 0 of
  a named function set, stated once in `ArrayValuedFunctions.ARRAY_OPERAND_FUNCTIONS`, which
  `SqlProjectionOnlyTypes` delegates to so the declared-column rule and the computed-array rule
  cannot drift.
- The SQL surface is Calcite (Babel parser, 1.42) lowering to a `RelNode` with no optimizer rules.
  `RelToStructuredQueryTranslator` reads `LogicalProject`/`LogicalFilter`/`LogicalAggregate`/
  `LogicalJoin`/`LogicalSort`/`LogicalValues`. It does **not** read `Correlate` or `Uncollect`.
- `QueryFieldBinding` already carries an array column's `elementType`, published by discovery as
  `element_type`. That is what makes an element-typed result expressible without inventing a type
  system.

**Everything below that concerns ClickHouse behaviour was measured against the running ClickHouse
26.3, not read off documentation.** The measurements are recorded in D5 because they are what
replaced an earlier draft of this design that had invented four restrictions ClickHouse does not
have.

## Goals / Non-Goals

**Goals.** Expose ClickHouse's `arrayJoin` as a catalog function with the same operand count, the
same semantics and the same legal positions; an element-typed result; an exact DSL↔SQL round-trip.

**Non-Goals.** Everything in the proposal's "Deliberately out of scope". At design level: any new
`Expr` kind or `StructuredQuery` section; any behaviour this project would have to invent rather than
expose; any change to `QueryEntityModel`, the enrichment machinery, the executor or the aggregate
pipeline; any new configuration key, credential or migration — this change introduces **none** of the
three.

## Decisions

### D1. The expression spelling, not a clause section

`array_join` is an ordinary `FnExpr`, mirroring ClickHouse's `arrayJoin`. The `ARRAY JOIN` clause
would need a new `StructuredQuery` section, a new wire shape, alias bindings visible to
`filter`/`sort`/`group_by`, and a `Relation` variant — and would not round-trip, because Calcite has
no `ARRAY JOIN` and its `UNNEST` lowers to a `LogicalCorrelate` over an `Uncollect` that
`RelToStructuredQueryTranslator` cannot read. The expression spelling reaches the same rows through
machinery both surfaces already have.

The clause is not rejected as bad, only as out of scope: it is the genuine ClickHouse route to zip
(`ARRAY JOIN a, b` pairs elements positionally, where two function calls multiply them), to element
indices via `arrayEnumerate`, and to `Nested` expansion. Adding it later is additive.

### D2. One operand. No mode, no left variant

ClickHouse's function takes exactly one operand — measured:

```
arrayJoin(['a','b'], 'left')
Code: 42. Number of arguments for function arrayJoin doesn't match: passed 2, should be 1
```

An earlier draft of this design gave `array_join` a `mode` argument of `inner`/`left`, rendering
`left` as `arrayJoin(emptyArrayToSingle(arr))` — which is byte-for-byte identical to
`LEFT ARRAY JOIN`, verified on `Array(String)`, `Array(Int64)` and `Array(LowCardinality(String))`.
It was **dropped anyway**, on the principle of not inventing surface ClickHouse does not have.
The reasoning is worth keeping: a `mode` operand would have been this project's own invention wearing
ClickHouse's name, and a caller reading `array_join(x, 'left')` would reasonably expect
`arrayJoin(x, 'left')` to work against the same engine, where it is an error. If outer-join semantics
are wanted later, `LEFT ARRAY JOIN` is a real ClickHouse construct and D1's clause section is where it
belongs.

*Alternative rejected:* two names (`array_join` / `array_join_left`). Same objection — the second name
names nothing ClickHouse has.

**The name collides with another dialect's function, deliberately.** Calcite ships
`SqlLibraryOperators.ARRAY_JOIN` — Spark's `array_join(array, delimiter)`, which *joins* an array into
a string. It is a library operator, absent from `SqlStdOperatorTable`, so it is not in this project's
chained table and cannot resolve by accident; our entry is registered first regardless. The residual
hazard is a SQL generator emitting the Spark form, and with one operand it **fails closed** on arity
rather than being reinterpreted. `array_join` is the name that reads as ClickHouse's `arrayJoin`,
which is the function being exposed.

### D3. The result type is a new `returns` token, `element_of_argument`

Neither existing token fits: `array` says the result is an array (it is not) and `same_as_argument`
would report the operand's own type, i.e. `array` again. `ExprFieldType` resolves it from argument 0 —
the binding's `elementType` for an `array` field, and for a computed array from the producing call.

**The four array-returning calls do not all answer the same way**, which review caught: `map_keys`,
`split_string` and `json_extract_array` read or split text whatever their operand holds and are fixed
at `string`, but `group_uniq_array` collects *its own* operand's values, so an expansion of it carries
that operand's type. A blanket `string` typed `array_join(group_uniq_array(total_tokens))` as text —
and since the aggregate-mode alias binding takes its type from here, a `having` or `sort` over that
alias was then validated as text too. `RelToStructuredQueryTranslator#arrayJoinKind` makes the same
distinction, because the two surfaces must not type one document differently.

The token describes what ClickHouse's function returns; it is not new behaviour, only the vocabulary
discovery needs to state it.

*Alternative rejected:* declaring `string`. Right for every array column catalogued today, wrong the
first time an `Array(Int64)` column is, and it would make `if(array_join(nums), …)` unify against the
wrong type — the class of bug `ExprFieldType`'s javadoc exists to prevent.

### D4. ClickHouse rendering

`arrayJoin(<arg>)`. That is the whole of it — one jOOQ plain-SQL template on the CLICKHOUSE
`DSLContext`, no fallback, no conditional. The earlier draft's empty-array construction
(`if(empty(a), [NULL], a)`) and the LowCardinality risk it carried both disappear with D2.

### D5. No positional restrictions beyond ClickHouse's own

An earlier draft rejected `array_join` in `filter`/`having`, inside an aggregate's arguments, inside a
window expression, and nested in another `array_join`, and justified the first as "ClickHouse forbids
it in WHERE". **Measured against ClickHouse 26.3, all four are accepted:**

| Probe | Result |
|---|---|
| `SELECT id FROM … WHERE arrayJoin(p) = 'x'` | accepted, 1 row |
| `SELECT count(arrayJoin(p)) FROM …` | accepted, counts expanded elements |
| `row_number() OVER (ORDER BY arrayJoin(p))` | accepted |
| `… ORDER BY arrayJoin(p)` | accepted |
| `arrayJoin(arrayJoin([['a','b'],['c']]))` | accepted, fully flattened |

So none of them is enforced. The DSL is allowed to be narrower than the engine, but only for a reason
of its own, and there was none — the restrictions were a guess about ClickHouse that turned out to be
wrong. What remains rejected is rejected by something real:

- **a non-array operand** — ClickHouse's own `ILLEGAL_TYPE_OF_ARGUMENT`, mirrored so the message
  arrives at translation time instead of from the engine;
- **an array-of-arrays operand** — unreachable rather than forbidden: the catalog's `element_type`
  names a scalar type, so such a column cannot be catalogued;
- **a `Map` operand** — ClickHouse accepts it and yields `Tuple(key, value)`; the DSL has no tuple
  type, which is a gap in our type vocabulary and is stated as one.

### D6. Multiple expansions inherit ClickHouse's behaviour

Two select entries expanding *different* expressions produce a cartesian product; two expanding the
*same* expression are deduplicated and advance together. Both measured:

```
SELECT arrayJoin(p), arrayJoin(q) FROM (… p=['x','y'], q=[10,20])   -> 4 rows (cartesian)
SELECT arrayJoin(p) AS a, arrayJoin(p) AS b FROM (… p=['x','y'])    -> 2 rows (a and b move together)
```

Neither is refused and neither is re-implemented. A cartesian product of two arrays is a real
ClickHouse answer to a real ClickHouse query; refusing it would be this surface deciding the caller
did not mean it. The spec states the behaviour so it is not discovered by surprise, and notes that
element-wise pairing is the clause form's job (D1).

### D7. `include_total` is refused in row mode — the one guard that is ours

`StructuredQueryBuilder#buildCount` counts source rows under the query's filter. With an expansion in
the projection the paged result has more rows than that count, so the reported total would describe a
different question. This is a property of *our* response contract, not of ClickHouse, which has no
such option — so refusing it is not narrowing the engine. `include_total` already has precedent as a
refusable option (the SQL renderer refuses it outright).

*Scoped to row mode.* `StructuredQueryExecutor#totalCount` returns null the moment the query is
aggregate-mode: no count query is built and the flag is already inert there. Refusing it in aggregate
mode would buy no correctness — there is no number to be wrong — while rejecting this change's own
headline shape (`array_join(...)` aliased, `count()`, `group_by` on the alias) for callers that set
`include_total` uniformly on every page, and would explain the refusal with a count that is never
taken. So the guard reads `query.mode()` and returns early for `AGGREGATE`.

*Alternative rejected:* counting over the expanded relation (`SELECT count(*) FROM (<the row query
without paging>)`). Expressible in jOOQ, and a second full scan of an already-expanded relation for an
opt-in nobody has asked for on this shape. Additive later if anyone does.

### D8. `array_join` joins the shared array-operand set rather than getting its own

Adding `"array_join"` to `ArrayValuedFunctions.ARRAY_OPERAND_FUNCTIONS` grants, in one edit, both the
declared-`array`-column carve-out (`SqlProjectionOnlyTypes.isAllowedFunctionArgument` delegates to it)
and the computed-array carve-out (`acceptsArrayOperand`). A separate set would be a second copy of a
rule whose javadoc already argues against having two. Both classes say "array accessor" in prose; those
sentences become "array-operand function" in the same edit.

### D9. Grouping is by alias, and the array-alias rule does not extend to it

`SELECT arrayJoin(topics) AS topic … GROUP BY topic` is the ClickHouse idiom and it works — measured,
returning one row per distinct element with correct counts. The DSL already supports grouping by a
select output name (`StructuredQueryBuilder#resolveGroupKey` emits `GROUP BY "topic"` for a name that
is not a bound field), so no change is needed there.

The SQL surface has a rule that an array-returning call is refused as a group key **through its alias
too**, so a `map_keys` projection cannot be grouped by whole arrays. That rule is keyed on the
producing function being array-*returning*; `array_join` returns a scalar, so it is outside the rule by
construction rather than by an exception. The delta spec says so explicitly, because "grouping by the
alias of a computed array is refused" and "grouping by the alias of an expansion is the intended form"
read like a contradiction otherwise.

### D10. `StructuredQuery` gains no field

The record is constructed in 135 places (six in main, the rest in tests). Nothing here needs a new
field — a consequence of D1 worth stating, since the clause spelling would have forced all 135 plus a
decision about `CompositeQuery#withPage` and the SQL translators that rebuild the record positionally.

## Risks / Trade-offs

- **A caller writes the Spark `array_join(arr, ',')`** → fails closed on arity (D2). Not silently
  reinterpreted as a delimiter.
- **A row-multiplying function widens what a single request can return** → existing governance already
  bounds it: `page.limit` caps returned rows (default 100, max 1000) *after* expansion, and
  `query.budget.*` bounds the work. Expansion multiplies rows read into the limit, not rows returned.
- **An accidental cartesian product from two expansions** (D6) → bounded by the same limit and budget,
  and stated in the spec rather than discovered. The alternative, refusing it, would refuse a
  legitimate ClickHouse query.
- **`array_join` reaches a surface that did not ask for it** — saved queries store DSL documents and
  Flight SQL runs the same pipeline → both inherit the DSL's own validation. The aggregate pipeline
  resolves measures against the catalog's *aggregate* group only, so a scalar is already refused; a
  task asserts that rather than leaving it inferred.
- **The engine's behaviour changes under an upgrade** → the local stack runs 26.3 and a 26.8 compose
  file exists in the tree. Every behaviour this design leans on is asserted by a test that talks to a
  real ClickHouse, so an upgrade that changed one fails there rather than in a report.

## Migration Plan

None. No migration, no configuration key, no credential, no schema change. The function and the
`returns` token are new surface; the only pre-existing statement that changes is a spec sentence that
declared element-level grouping inexpressible. Rollback is reverting the commit — no persisted
artifact records that the function existed, except a saved query written against it, which would then
fail validation with "unknown function", as with any rolled-back catalog entry.
