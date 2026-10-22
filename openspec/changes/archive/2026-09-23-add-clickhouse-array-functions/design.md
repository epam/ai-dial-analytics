## Context

See proposal.md — Why. What shapes the approach is the pipeline every query already takes:

```
SQL text → Calcite parse → validate → RelNode → StructuredQuery (the DSL) → jOOQ → ClickHouse SQL
```

The DSL is not a sibling of the SQL surface, it is the **waist** of it: `/v1/queries/execute-sql`
executes by being translated into a `StructuredQuery` first. So a ClickHouse function reaches
ClickHouse only if the DSL can carry it, and `StructuredQueryToSqlRenderer` must be able to render
it back — the round-trip the codebase protects in `ArrayValuedFunctions` and `SqlProjectionOnlyTypes`.
A construct legal on one surface and refused on the other is the defect class those two classes
exist to prevent.

Two existing rules are load-bearing here and both are **positional**: an array may stand at
*argument 0* of a named set of array-operand functions, and nowhere else
(`ArrayValuedFunctions.acceptsArrayOperand`, mirrored for declared `array` columns by
`SqlProjectionOnlyTypes.isAllowedFunctionArgument`). ClickHouse's higher-order functions take the
array at argument **1**, so this change generalizes that rule rather than adding a second one.

### Measured, not assumed

Three unknowns decided this design; all three were measured against Calcite 1.42 with the project's
own parser config and catalog fixture, not reasoned about:

| Question | Result |
|---|---|
| Does the Babel grammar parse `x -> expr` at all? | **Yes** — including `(x, y) -> …` |
| Does ClickHouse operand order (lambda **first**) validate and lower? | **Yes** — `arrayExists((x) -> STARTS_WITH(x, 'coder-'), $10)`, a `RexCall` over a `RexLambda`. What ships lowers the same statement without a `RexLambda` at all (Decision 2) |
| Can a lambda parameter be typed from the array operand's element type? | **Yes** — `SqlValidator.getLambdaScope(lambda).getParameterTypes().put(name, elementType)` from inside the operand type checker; measured `element=VARCHAR body=BOOLEAN` |
| Does Calcite's own `exists(arr, x -> …)` work? | **No** — `EXISTS` is a reserved keyword in the Babel grammar; the parse fails at `->` |
| Do nesting, `groupUniqArrayArray(<array column>)`, and `HAVING arrayExists(…, groupUniqArrayArray(…))` validate and lower? | **Yes**, all three |
| Can a lambda body reference a column of the enclosing row? | **Not through Calcite's lambda scope** — `Param 'deployment' not found in lambda expression`. It is supported through the rewrite in Decision 2; Decision 9 has that measurement table |

## Goals / Non-Goals

**Goals:**

- A ClickHouse user's array predicate executes **as written**: same function name, same operand
  order, same result.
- One canonical name per function in the DSL, which for everything new in this change is the
  ClickHouse name itself. No alias for the existing catalog (Decision 4).
- Both post-aggregation readings of "users holding a `coder-*` role" become expressible in one
  statement: ever-in-window (`groupUniqArrayArray` + `HAVING`) and current (`arg_max`).
- The lambda is a general expression form, so the next higher-order function is one catalog row and
  one translator branch, not a new mechanism.

**Non-Goals:**

- The `-Array`, `-If`, `-OrNull`, `-Merge` **combinator machinery**. Two `-Array` members are added
  as named functions because they are what the motivating query needs; a general combinator is a
  different (and much larger) feature.
- Multi-parameter lambdas (`arrayMap((x, y) -> …, a, b)`). The grammar parses them; the catalog
  admits exactly one parameter, because every function in this cohort is single-array. This is now
  the only way a lambda here is narrower than ClickHouse's.
- Index-carrying variants (`arrayFirstIndex`, `arrayEnumerate`), sorting (`arraySort`), and set
  algebra (`arrayIntersect`, `arrayConcat`).
- Renaming the existing catalog to ClickHouse spellings (see Decision 4).
- Widening the enrichment/aggregate **declaration** surface beyond what the measure allowlist
  inherits automatically (Decision 8).

## Decisions

### 1. ClickHouse operand order, our own operators — not Calcite's higher-order functions

`arrayExists(<lambda>, <array>)`, matching ClickHouse exactly.

*Alternative rejected:* Calcite's Spark-library `EXISTS(<array>, <lambda>)`. It is array-first (so
not what a ClickHouse user writes), and it is **unusable here regardless**: `EXISTS` is a reserved
keyword in the Babel grammar and the call fails to parse at the `->`. Measured.

*Alternative rejected:* accepting the ClickHouse spelling and swapping operands into Calcite's shape
before validation (the `SqlDateTruncRebinder` trick). Unnecessary — lambda-first validates natively
— and it would put the DSL document in the opposite operand order from the statement, which the
renderer would then have to flip back.

### 2. The parameter is inlined as a typed marker before validation — Calcite's lambda scope is not used

**Superseded by what Decision 9 measured.** The first design bound the element type into
`SqlLambdaScope.getParameterTypes()` from a custom operand type checker. It works (measured), but it
inherits Calcite's lambda scope, and that scope is exactly what forbids capture.

What ships instead: a pre-validation rewrite turns `arrayExists(x -> f(x, col), arr)` into
`arrayExists(f(__lambdaParam(arr, 'x'), col), arr)`, where `__lambdaParam` is an internal operator
whose result type is **its array operand's component type**. The body is then an ordinary expression
validated in the **enclosing** scope, so:

- the parameter is typed as the array's element, exactly as before;
- a captured column resolves like any other column;
- the higher-order operators need only plain operand checkers (`sequence(BOOLEAN, ARRAY)`), with no
  dependence on `getLambdaScope` or on its parameter map being mutable — one fewer rarely-exercised
  Calcite API to be broken by an upgrade;
- the body is type-derived **once**, which removes the double-derivation rough edge the previous
  design had to carry: `x > 3` over a string array now coerces exactly as `deployment > 3` would.

The parameter's **name travels as the marker's second operand**, a string literal. It is not
decoration: `arrayExists(x -> arrayExists(y -> equals(y, x), roles), roles)` produces two markers
over the *same* array expression, and only the name tells them apart (measured:
`EQUALS(__lambdaParam($10, 'y'), __lambdaParam($10, 'x'))`). Carrying the name also means the
caller's own parameter names survive into the document and back out through the renderer, rather
than being canonicalized to `x`.

`__lambdaParam` is reserved: a statement that names it is rejected before the rewrite runs, so the
internal operator cannot be reached from a caller's SQL.

### 3. Two DSL expression kinds: `lambda`, and `lambda_param` for the reference

Wire form: `{"type": "lambda", "params": ["x"], "body": {…}}` with
`{"type": "lambda_param", "name": "x"}` inside the body. The translator resolves the body against
the statement's bindings **extended** with one binding per parameter, so a body holds captured
columns and parameter references side by side.

*Alternative rejected:* referencing the parameter as an ordinary `field`. That was the design while
capture was refused, because the parameter was then the *only* thing a body could name. With capture
supported, a body holds both kinds of reference, and a walker that collects the columns a statement
reads (`collectFieldNames`, the enrichment-join and partition-pruning inputs, the sensitive-column
scan) would have to subtract the names bound by each enclosing lambda — scope tracking in every
walker, with a wrong answer where it is forgotten. A distinct node kind makes it structural: a
walker that wants columns ignores `lambda_param`, and one that wants parameters ignores `field`.

*Alternative rejected:* an internal-only kind with no wire spelling, like `ScalarSubqueryExpr`.
`/v1/queries/execute` callers would be locked out of a function the SQL surface has, and
`/v1/queries/translate` must render a document form anyway.

### 4. One name per function: ClickHouse's, for everything new — and no aliases for the old

`GET /v1/queries/functions` keeps advertising exactly one name per function and the renderer emits
exactly that name. **New** functions take the ClickHouse name as their canonical one (`arrayExists`,
`groupUniqArrayArray`): there is no legacy to preserve, and the in-flight `add-ai-query-functions`
change sets the camelCase-in-catalog precedent.

**No ClickHouse alias is added for the existing catalog** (decided at proposal review). The consequence is worth stating plainly rather than discovering: a ClickHouse user
writing `startsWith`, `hasAny` or `argMax` still gets an unknown-function error, and the body of a
new higher-order call is written in the catalog's own spelling —
`arrayExists(x -> starts_with(x, 'coder-'), roles)`, not `startsWith`. So this change closes the
*expressive* gap (there was no way to say it at all) and leaves the *spelling* gap open.

*Alternative rejected (for this change):* registering `has`/`hasAny`/`startsWith`/… as inbound
aliases on the `now()`/`CURRENT_TIMESTAMP` pattern, or renaming the whole catalog to ClickHouse
spellings. Either is mechanical across 49 functions and touches `/v1/queries/functions`, every
rendered statement and every stored saved-query and pipeline-measure `fn`. Both stay open; nothing
here forecloses them.

*Alternative rejected:* inventing `array_has_prefix(array, prefix)` — cheaper for us, one more thing
for a ClickHouse user to learn, and it closes only the prefix case. Ruled out at proposal
review.

One trap to record for whoever does the alias change later: `splitByChar` is **not** an alias of
`split_string`. ClickHouse takes `(separator, string)` and the catalog takes `(text, delimiter)`, so
registering it as one would silently swap the caller's operands.

### 5. The array-operand position becomes per-function, not "argument 0"

`ArrayValuedFunctions.acceptsArrayOperand(name, index)` currently answers `index == 0 && name ∈ set`.
The higher-order functions take their array at index 1, and `arg_max` takes it at index 0 as a
*value* while index 1 is the ordering key. So the set becomes a map from function name to the
argument position that admits an array, and both callers (the computed-array rule here, the
declared-`array`-column rule in `SqlProjectionOnlyTypes`) keep delegating to the single answer.

### 6. The aggregate carve-out is named, not general

`RelToStructuredQueryTranslator` refuses an array operand to *any* aggregate. That stays, except for
argument 0 of `groupUniqArrayArray`, `groupArrayArray` and `arg_max`.

All three carve-outs admit an array **value** — an `array` column or an array-valued *scalar* call —
and none admits an aggregate, because all three are themselves aggregates and ClickHouse refuses an
aggregate inside an aggregate. `arg_max` had that guard from the first pass; the two new positions
did not until review round 3, so `groupUniqArrayArray(group_uniq_array(x))` typed as an array,
rendered, and came back a 503 where the SQL surface answers 400 at Calcite validation.

*Alternative rejected:* lifting the restriction for aggregates generally. `sum(<array>)` and
`min(<array>)` have no meaning in ClickHouse either, and the current error is the single most useful
thing the surface says about arrays.

### 7. The lambda parameter name is the one unquoted caller token in the rendered SQL

ClickHouse's lambda parameter must be a bare identifier — a jOOQ-quoted `"x"` is not accepted there
— so this is the only place in the change where caller-controlled text reaches the statement
unquoted. Both entry points (SQL front end and DSL) therefore validate the name against
`[a-z_][a-z0-9_]{0,63}` and reject anything else before rendering.

**Shadowing is a rule, not an impossibility.** The sentence that stood here — "a parameter shadowing
nothing is impossible by construction because the body's scope contains only the parameters" —
belonged to the no-capture design that 2.7 superseded. A body now sees the enclosing row's columns,
so a parameter can collide with one, and the collision has to *mean* something. It means the
parameter, on both surfaces: `SqlLambdaRewriter.substitute` replaces every single-part identifier
equal to the parameter, and `LambdaScope` binds the parameter under the bare name as well as under
its prefixed key so a DSL `field` resolves the same way. The alternative — resolving a `field` to the
column while SQL resolved the identifier to the parameter — was live until review round 3 and was not
a defensible second reading: `StructuredQueryToSqlRenderer` emits a `field` as exactly that bare
identifier, so the rendered statement would have meant something else than the document it came from.

**A lambda body reads columns, and is governed like every other read.** Since capture ships
(Decision 9), a body is a column-reading position — so the standing invariant "route every new read
surface through `CatalogQueryService`" applies to it. It holds without new code: that model omits a
sensitive column altogether for a caller who may not see it, so a captured sensitive column is an
unknown-column error in a lambda body exactly as it is in a projection. Stated here, and tested,
rather than inferred.

### 8. The two new aggregates are refused as pipeline measures, with the real reason

`AggregateSpecValidator` derives its measure allowlist from the catalog's `aggregate` group, so a new
catalog entry *automatically* becomes a legal measure `fn`. Left alone, a
`groupUniqArrayArray` measure would be refused anyway — by the array-measure rule, whose reason is
"a set of arrays is not a declarable column type". That reason is true for `group_uniq_array` and
**false** for a function that flattens, so the author's next move would be to work around a
constraint that is not the real one. The refusal is therefore made explicit, naming the actual
constraint: a measure is one function over one *scalar* input column.

This is the treatment `arg_max` already receives (`requireSingleColumnArity`: in the catalog,
available at query time, refused as a measure) and the same argument.

*Alternative rejected:* admitting them as measures. It is a coherent feature — "the set of roles seen
per user per day" is a reasonable rollup — but it needs its own answers for the target's
`element_type` (the input's *element* type, not its declared type) and for whether a flattening
measure is re-aggregatable across buckets. That is a second change, not a rider on this one.

`arg_max`'s pipeline treatment is untouched: this change lifts the array restriction on the **query**
surface only.

### 9. Capture inside a lambda body is supported — the investigation landed

The gap was attacked rather than documented, and it closed. Every
item below is a measured run of the pre-validation rewrite from Decision 2, not an argument:

| Checklist item | Outcome |
|---|---|
| (a) a captured column resolves | **Yes** — `arrayExists(STARTS_WITH(__lambdaParam($10, 'x'), $12), $10)`, where `$12` is `deployment` |
| (b) a lambda nested in a lambda keeps its parameters distinct | **Yes**, including over the *same* array expression, which is what the name literal is for |
| (c) an aggregate inside a body stays rejected | **Yes** — but with Calcite's own confusing wording ("Expression 'execution\_path' is not being grouped"), so the rewrite rejects an aggregate or window call in a body itself, with a message that says which |
| (d) a captured column is governed like any other column read | **Yes, by construction** — `CatalogQueryService` omits a sensitive column from the model entirely, so capturing one fails as an unknown column exactly as projecting it does. Covered by a test rather than left to the argument |
| (e) both surfaces accept and refuse the same bodies | The DSL applies the same rules over `lambda_param`; the round-trip test is what holds it |

So ClickHouse's own semantics ship: `arrayExists(x -> starts_with(x, deployment), roles)` means what
it means there. The one remaining divergence is the parameter *count* — one, per Decision 4's scope —
and that is a catalog limit, not a scope limit.

*Alternative kept as the fallback and not needed:* Calcite's `SqlLambda`, which validates a body only
against the lambda's own parameters and refuses capture outright.

## Risks / Trade-offs

- **A lambda body is now a column-reading position** → Safe through the existing chokepoint (a
  sensitive column is absent from the model, so capture of it is an unknown-column error), and the
  captured column appears in the `Rex` tree as an ordinary input reference, so heavy-column
  detection, partition pruning and enrichment-join derivation see it without changes. This is the
  part of the change a reviewer should look at hardest, which is why it is a test and not a claim.
- **The rewrite duplicates the array expression into the body** (measured: the inner `arrayFilter`
  appears in both operands) → Invisible in what executes, because the rendered ClickHouse statement
  is built from the DSL's `lambda`, which carries the body once. It does mean a deeply nested chain
  grows the intermediate tree; the statement length limits already in force bound it.
- **`__lambdaParam` is an internal operator in a table shared with caller-facing names** → Reserved:
  a statement naming it is rejected before the rewrite, and the reservation has its own test.
- **`arrayExists(x -> startsWith(x, …), col)` cannot use a data-skipping index, while `hasAny` can**
  → Documented in the catalog description, the way `json_extract_string_ci`'s cost already is. The
  convenient function, not the fast one; the enumeration form stays available and stays faster on a
  large scan.
- **`groupUniqArrayArray` holds a set per group** → The existing `unbounded_group_by` advisory from
  `POST /v1/queries/validate` already fires for an unfiltered `GROUP BY`; the catalog description
  carries the accumulator note.
- **The design depends on two Calcite APIs that are public but rarely exercised** —
  `SqlValidator.getLambdaScope` and a mutable `SqlLambdaScope.getParameterTypes()` → A permanent
  front-end test asserts a lambda query validates and lowers, so a Calcite upgrade that changes
  either fails the build with a message that points at the cause rather than at a user's query.
- **A new catalog aggregate silently widens the pipeline measure vocabulary** → Decision 8: the two
  are refused at declaration with the real reason, and a test pins the message. This is the change's
  least obvious blast radius, so it is worth a reviewer's attention.
- **A pattern ClickHouse's RE2 cannot compile surfaces as `503 clickhouse_unavailable`** rather than
  a `400` → Pre-existing behaviour for anything that validates here and fails there, not a
  regression introduced by `match`; tightening the ClickHouse-failure mapping is its own change.
- **Two array-valued aggregates now exist alongside `group_uniq_array`, differing only in whether
  the input is an array** → The names are ClickHouse's own, and the catalog descriptions say which
  is which; an author who picks the wrong one gets a type error naming the operand, not a wrong
  answer.

## Migration Plan

None required: no configuration key, no migration, no dependency change, no contract version. The
change is purely additive to the query surface — every statement legal before stays legal and lowers
to the same document, so rollback is an ordinary redeploy after which statements using the new
functions fail with the pre-change error.
