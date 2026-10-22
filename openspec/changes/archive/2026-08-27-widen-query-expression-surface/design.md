## Context

The closed function catalog has 28 entries and `abs` is the only numeric operation. `map` and `array`
columns are projection-only in every position. Both facts were measured against a running ADAS deployment
and each blocked a concrete dashboard query; the proposal carries the evidence.

Three constraints shape every decision below.

**The DSL is the canonical IR, not a peer surface.** SQL is a front end: Calcite parses and validates,
`RelToStructuredQueryTranslator` + `RexToExprTranslator` lower the `RelNode` into a `StructuredQuery`,
and `ExprTranslator` renders that through jOOQ to ClickHouse. Every error in the source request reads
"not supported by the query translator" because the lowering has no target, not because a name is
missing from an allowlist. Consequence: nothing here can be solved on the SQL side alone, and nothing
needs a new SQL dialect decision — this is not the Q-DES-8 subset-size question.

**The catalog is an allowlist and widening it is the sanctioned path.** `ExprSqlValidator` documents it
as such; it is what keeps `now()`/`rand()` out and what Class-1's exactly-once effect rests on.
`2026-08-26-add-derivation-expression-functions` added seven functions through these same files one day
earlier, and this change follows its shape deliberately rather than inventing one.

**One live-failure precedent governs the test strategy.** In that preceding change, the naive rendering
of `json_extract_string_ci` over an `object` column returned empty for *every row* on ClickHouse 26.3
**without raising anything**. Review cannot catch a silent-empty. Both new accessor families here read
container columns through rendering paths nothing currently exercises, so they inherit that risk.

## Goals / Non-Goals

**Goals.** Make a ratio expressible; make a `map` key readable, groupable and filterable; make an
`array` testable; accept the time-literal spelling generated SQL emits; give the shipped envelope
`distinct` flag a spec and the `distinct_supported` flag a stated rationale; keep the DSL↔SQL
round-trip exact for everything added.

**Non-Goals.** Exploding an array or a delimited string into rows (`array_join`/`unnest`) — deferred to
its own change, reasoning in the proposal. A `uniq` alias. Key-level
sensitivity for `map` columns. Any new `Expr` kind, operator node, or `StructuredQuery` section: if a
decision below required one, it would belong in the deferred change instead.

## Decisions

### 1. Arithmetic lands as named functions; operators are a front-end spelling

The DSL gains `plus`/`minus`/`multiply`/`div`/`modulo` as ordinary `fn` expressions. Infix `+ - * / %`
are handled
entirely in `RexToExprTranslator.callExpr`, which already lowers `LOWER`/`ABS`/`TRIM` from Calcite
`RexCall`s the same way — Calcite names these operators `"+"`, `"-"`, `"*"`, `"/"` and `"%"`, so each
is one `case` arm.

*Alternative rejected:* a binary-operator `Expr` kind. It would add a node to a sealed hierarchy the
whole translation layer switches over, force a wire-format decision, and buy nothing — a function call
expresses the same tree. The 2026-08-26 change made the same call for `if` over a `CASE` node, and the
`structured-query` spec's existing `round(div(...), 2)` scenario already assumes the function spelling.

**Measured during implementation: the arithmetic catalog names are not usable as SQL functions.**
`SqlQueryOperators` initially registered all five so a client could write either spelling. `minus(a, b)`
does not parse — `MINUS` is a reserved set operator in the Babel grammar, the `EXCEPT` alias — and no
operator-table entry can change that. A probe over all five found `plus`, `multiply`, `div` and `modulo`
parse fine, so the choice was four-of-five or none. **None**: one rule ("SQL spells arithmetic with
operators, the catalog names are the DSL spelling") is a better surface than an exception a client
discovers by hitting it, and the round-trip never needed them because the renderer emits operators. This
is why `SqlSelectAliasInjectorTest`'s cross-surface naming invariant carries its first exemption.

**Unary minus needs arity disambiguation.** Calcite's `UNARY_MINUS` is also named `"-"`, so the `"-"`
arm must branch on operand count: one operand lowers to `minus(0, x)`, two to `minus(a, b)`. Matching on
name alone would silently mis-lower `-a` to a two-arg call with a missing operand.

### 2. `div` and `modulo` render with a `nullIf` guard on the right operand

The spec requires null — never an engine error — for a zero or null right operand, on both. ClickHouse
does neither by
default: `/` on floats yields `inf`/`nan`, integer division throws, and `modulo` throws on a zero
divisor regardless of operand types. So:

- `div(a, b)` → `divide(a, nullIf(b, 0))`
- `modulo(a, b)` → `modulo(a, nullIf(b, 0))`

`nullIf` turns the zero right operand into NULL and both ClickHouse functions propagate NULL, so one
guard covers every operand family. This is the load-bearing half of adding `modulo`: a bare `%` throws
on a zero divisor, and the throw is a whole-query failure, not a null cell — exactly the panel-breaking
behaviour the `div` contract exists to prevent.

`divide` also always returns `Float64` in ClickHouse (it is `intDiv` that truncates), which is what
makes the spec's "`div` always returns decimal" rule free rather than a second cast. `modulo`
deliberately does **not** get that treatment — it preserves the operand type, so an integer remainder
stays an integer, which is what a bucketing expression needs.

*Alternative rejected:* `if(equals(b, 0), NULL, a / b)`. The DSL has no null in expression position — the
2026-08-26 change established that and rejects a null branch in `if` explicitly — so this is not
expressible without adding one.

**`nan` must not leak.** A NULL divisor is now handled, but `divide(0.0, 0.0)` is not reachable through
this rendering and `inf` is only reachable from an overflow, not a zero denominator. Verify on live
ClickHouse that no `nan`/`inf` reaches the response envelope; if one does, wrap the result rather than
widening the guard.

**`modulo`'s sign convention is specified, not inherited.** ClickHouse takes the sign of the left
operand (`modulo(-7, 3) = -1`), which matches SQL and Java but not Python. It is written into the spec
so a future rendering change cannot silently flip it — a cyclical bucketing expression that changes
convention mis-buckets every negative value without erroring.

### 3. `round`/`floor`/`ceil`/`MOD` are Calcite standard operators — handle, do not register

`DATE_TRUNC`, `IF`, `CONTAINS` and the rest were registered as custom operators ahead of Calcite's
standard table because Calcite had no entry, or a conflicting one. `ROUND`, `FLOOR`, `CEIL` and `MOD`
are
different: Calcite's standard table already carries all four, so registering competing operators
invites the shadowing bug the precedent was avoiding. Lower Calcite's own `RexCall`s in
`RexToExprTranslator` instead.

**`%` and `MOD` are two spellings of one operation and both must land on `modulo`.** Calcite parses `%`
as `PERCENT_REMAINDER` (named `"%"`) and `MOD(a, b)` as the standard `MOD` function — different
operators, same meaning. Both lower to `modulo`; the renderer emits only `%`, so the round-trip stays
single-valued rather than depending on which spelling the client sent.

**`FLOOR`/`CEIL` carry a datetime form that must be rejected.** Calcite accepts
`FLOOR(<timestamp> TO <unit>)` as a two-operand call with a `TimeUnitRange` literal. That is
`date_trunc`'s job, it has no catalog equivalent under `floor`, and silently lowering it to `floor(x)`
would discard the unit. Detect the two-operand form and reject it with a message pointing at
`date_trunc`.

### 4. The projection-only carve-outs stay positional, in the one classifier

`SqlProjectionOnlyTypes.isAllowedFunctionArgument` already models exactly this shape for
`object`-as-`json_extract_*`-argument: argument index 0, a named function set, a specific type. `map`
for the three map accessors and `array` for the four array accessors are two more entries in that
method, not new branches at call sites. Both translation directions consult it, which is the property that keeps the
round-trip from drifting — the class's own javadoc says the rule lives there for that reason.

**`JSON_EXTRACT_*` over a `map` stays rejected.** The existing spec says so explicitly and the reason is
unchanged: a `Map(String,String)` is not a JSON document. The three map accessors are the map's own,
reading flat keys; they are not a widening of the JSON carve-out and must not be implemented as one.

### 5. The map accessors take a literal key, matched exactly — not a path

Renderings:

- `map_value(m, 'k')` → `m['k']`, whose miss returns the value type's default (empty string for
  `Map(String,String)`) — the spec's absent-key contract without extra code
- `map_contains(m, 'k')` → `mapContains(m, 'k')`
- `map_keys(m)` → `mapKeys(m)`

**`map_contains` is not redundant with `map_value`.** Because a miss and a key stored with an empty
value both render as the empty string, `map_value` alone cannot distinguish "absent" from "present but
empty" — a distinction that matters on producer-defined attribute maps, where an empty value is a real
observation. `not_empty(map_value(m,'k'))` answers the wrong question. This is the reason the presence
test earns its place rather than being `map_value` plus a comparison.

The load-bearing part is the **argument shape**. Every existing extractor uses `pathKey()` — a variadic
`string_literal` — so `json_extract_string(x,'user','email')` traverses nesting. Copying that arg
descriptor for `map_value` would be the natural move and would be wrong: the live keys on
`point_attributes` are `user.email`, `session.id`, `user.account_id`, and a `Map` has one flat key
namespace. Under path semantics `map_value(m, 'user.email')` returns empty for every row — silently,
which is precisely the `json_extract_string_ci` failure mode. Declare the key as a single
non-variadic `string_literal`, following `json_extract_string_ci`'s precedent, and assert the dotted-key
case against live data. The same applies to `map_contains`, where the failure is quieter still: a
dotted key read as a path returns *false* for every row, which looks like a legitimate empty result.

### 6. Array accessors map onto ClickHouse one-for-one

`array_has` → `has`, `array_has_any` → `hasAny`, `array_has_all` → `hasAll`, `array_length` → `length`.
All four are null-safe on an empty array natively; a NULL array column needs the false/zero contract
checked rather than assumed. `array_length` gets its own catalog name rather than overloading the
existing `length(text)`: ClickHouse's `length` accepts both, but two DSL functions with one name and
different operand types would need overload resolution the catalog has no concept of.

`split_string` → `arrayMap(x -> trimBoth(x), splitByString(<delim>, <text>))`. Note ClickHouse's
argument order is (delimiter, text), the reverse of the DSL signature.

**`map_keys` makes the array accessors compose, which is why it is worth more than it looks.**
`ArrayValuedFunctions` gains `map_keys` alongside `split_string` and `json_extract_array`, so its result
is projection-only — except in an array accessor's argument 0, where `array_length(map_keys(m))` becomes
a map's size and `array_has(map_keys(m), 'k')` becomes a second spelling of `map_contains`. The overlap
is accepted: `map_contains` is the direct form and the one a filter should use, while `map_keys` answers
the question nothing else can — *which* keys a producer-defined map actually carries. On a feed whose
attributes vary by `metric_name`, that is the difference between guessing a key and discovering it.

**Filtering only.** These make an array *testable*, not *groupable*. An `array` field stays rejected as a
`GROUP BY` key, and that is a deliberate stopping point, not an oversight — `array_has` + `if` covers a
closed value set in one query (`sum(if(array_has(roles,'admin'), price, 0))`), and an open vocabulary
wants a normalized enrichment rather than a row-multiplying query construct.

### 7. Always parenthesize rendered arithmetic

`StructuredQueryToSqlRenderer` renders `div` and friends as infix operators (decision: the rendered
SQL's audience is BI clients, for which the operator form is portable). Rather than compute when
parentheses are needed from a precedence table, wrap every rendered binary arithmetic expression in
parentheses unconditionally. Slightly noisier SQL, no precedence bug, and the round-trip is exact
because Calcite discards redundant parentheses on the way back.

<!-- ponytail: unconditional parens over precedence analysis; add a precedence table only if the noise
     is a real complaint from a BI client, not on aesthetics -->

### 8. String→timestamp coercion is a cast-pair widening, not a parser change

`RexToExprTranslator.isSupportedCast` already whitelists tolerated cast pairs (`DATE`→`TIMESTAMP` among
them) and rejects the rest. Calcite's validator injects a `CAST(CHAR → TIMESTAMP)` when a string literal
meets a timestamp column, and today that lands in the reject branch. Add the pair, parse the three
ISO-8601 spellings in `castExpr`, and reject anything else with a message naming the accepted formats.

Scope it to a **literal** operand compared against a timestamp column. Admitting `CAST(<column> AS
TIMESTAMP)` would be a general cast capability with its own null-and-error semantics, and nothing asked
for it.

### 9. `distinct` and `distinct_supported`: documentation, not code

The envelope `distinct` flag already works — `StructuredQuery.distinct` → `StructuredQueryBuilder:117`,
covered by `queryLevelDistinct`, and verified against a running deployment including multi-column and expression
projections. The source request asked for it as a feature because the `structured-query` spec has no
requirement for it and does not list it in the envelope enumeration, and `adas_describe_schema` does not
serve the structured-query DTOs. The fix is the spec requirement, not an implementation.

`distinct_supported: false` is likewise deliberate everywhere it appears; only the rationale was
missing. It goes into the catalog `description` strings, where a client actually reads it.

### 10. `point_attributes` is flagged sensitive in code *and* patched on existing deployments

Enforcement needs no new code: `CatalogQueryService:90-95` drops sensitive columns from the entity model
for a caller who may not see them, so they are "invisible everywhere — hidden from discovery and
rejected as unknown". `map_value(point_attributes, …)` therefore fails for a non-`FULL_ADMIN` as an
unknown column, through the existing chokepoint, with no map-specific enforcement to write.

**Two actions are required, not one.** `OtelSinkService:109` applies a sink's schema once, at sink
creation; there is no startup reconciliation of column metadata. So editing `OtelSinkSchemas` protects
only sinks created afterwards, and an already-materialized `otel_claude_code_metrics` needs a
`PATCH /v1/tables/{name}` as well. Shipping only the code change would leave the data that motivated the
flag unprotected while every test passes.

`resource_attributes` and `scope_attributes` are verified to carry only `host.arch`, `os.type`,
`os.version`, `service.name`, `service.version` — resource and scope metadata, not end-user identity —
and stay unflagged. Flagging all four would hide the OS/version dimensions for no benefit.

## Risks / Trade-offs

**Three defects this change's own live-ClickHouse tests caught before review, recorded because they are
the shape of what else might be lurking**: `mapContains` returned the number `1` rather than `true` over
REST (ClickHouse yields `UInt8`; the fix is the `toBool` wrapper `json_extract_bool` already carries —
note the four boolean scalars added in the preceding change do *not* carry it, which is worth a separate
look); `GROUP BY` on the *alias* of a `map_keys` projection was accepted and grouped by whole arrays,
because the projection-only classifier only inspected declared column types and not computed ones; and
`WHERE array_has(...)` was refused because predicate position accepted only a bare boolean column, never
a boolean call. None would have been caught by a test asserting that the query runs.

**Silent-empty rendering over container columns, the `json_extract_string_ci` failure mode** → None of
the three map accessors nor the array accessors may be signed off on unit tests. Each needs a
live-ClickHouse
integration assertion on a **non-empty** result over real container data, including the dotted-key case.
A test asserting only "the query runs" would have passed for the broken predecessor. `map_contains` is
the worst of the set: a wrong rendering returns `false` for every row, which reads as a valid answer
rather than as a bug.

**Accepted: the demo this change enables now requires `FULL_ADMIN`** → Whole-column sensitivity cannot
separate `model`/`effort`/`query_source` from `user.email`, so flagging the column gates all of them.
This tradeoff was chosen explicitly over leaving the PII aggregatable. The lasting fix is to lift the
non-identifying keys into an enrichment via `map_value` — which decision 5 makes expressible and the
`class1-enrichment` delta specifies — giving each derived column its own `sensitive` flag. That is the
recommended follow-up and the reason key-level map sensitivity is not being built.

**A non-`FULL_ADMIN` reader gets "unknown column", not "forbidden"** → Correct and deliberate (a `403`
would leak the column's existence), but confusing to someone who was querying the column yesterday. Not
a code change; worth a line in the change's hand-off note.

**Calcite standard-table shadowing on `ROUND`/`FLOOR`/`CEIL`** → Decision 3 handles Calcite's operators
rather than registering rivals; the `FLOOR(x TO unit)` form is explicitly rejected. A test per spelling,
including the datetime form.

**`div`'s and `modulo`'s null contract silently regressing** → An integration assertion on a real
zero-right-operand group, on both integer and float operands, not a unit test over the rendered string.
The two fail differently and both need covering: `div` on floats degrades to `nan` (a wrong value in the
envelope), while `modulo` and integer `div` **throw** (a failed query). A guard that is dropped from
`modulo` alone would pass every `div` test.

**Widening the catalog widens Class-1 and the SQL subset at once** → Every function here is a scalar, so
the aggregate-pipeline measure vocabulary is untouched. That is why `uniq` was dropped: it would have
been the only new aggregate, changing pipeline surface as a side effect nobody requested.

**The feed is nearly empty (709 data points, one user in every sampled row)** → Not a risk to this
change, a risk to concluding anything from it. `map_value` makes the OTel feed queryable; whether it has
data is an ingest question this change does not touch, and cannot be answered before it ships because
the identity lives in the map.

## Migration Plan

Additive and deployable in one step; no data migration, no config keys, no new endpoints. Rollback is
a redeploy — the sixteen functions vanish from `/v1/queries/functions` and queries using them fail at
translation, which is the pre-change behaviour.

The one ordering constraint is operational, not code: apply the `point_attributes` `PATCH` on each
environment that already has an OTLP metrics sink, in the same window as the deploy. Before it,
`map_value` over that column is readable by any caller; after it, only by `FULL_ADMIN`. Deploying the
code without the patch is the failure mode to avoid — it is the state in which the PII is newly
aggregatable and the flag that was supposed to prevent that is present in the source but absent from the
catalog.

Rolling back the flag is a second `PATCH`, independent of the code rollback.

## Open Questions

- **Does `nan` or `inf` reach the response envelope from any surviving arithmetic path?** Decision 2
  closes the zero-denominator route; overflow is untested. Resolve with an `EXPLAIN`/live probe during
  implementation, not by review.
- **Does `map_value` over `point_attributes` need a cost note?** ClickHouse stores a `Map` as two
  parallel arrays, so key access is a per-row scan of the keys array rather than a subcolumn read.
  Cheap on 709 rows; unknown at `dial_usage_log` scale where a `map` column may later appear.
  `json_extract_string_ci` got an `EXPLAIN` before being advertised for exactly this reason — do the
  same before documenting `map_value` as cheap.
- **Should `array_length` return 0 or NULL for a NULL array column?** The spec says 0, matching
  `array_has*`'s false. Confirm ClickHouse `length(NULL)` does not return NULL through the rendering,
  and if it does, guard it rather than changing the spec.
