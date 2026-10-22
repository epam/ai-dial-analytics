## Context

Class-1 (`type: sql`) enrichments derive values with a server-side `INSERT … SELECT`. The expression
is authored as SQL, parsed by the Calcite front end, lowered to the `Expr` DSL, and rendered back to
ClickHouse by jOOQ — the same path a read query takes. `ExprSqlValidator` wraps the expression as
`SELECT <expr> AS __expr FROM <source>` precisely so that the bounded grammar, the closed catalog and
unknown-column rejection apply identically.

That shared path is why the DSL's shape matters here. The DSL has two halves of very different
richness: `FilterNode` (a full boolean language — comparisons, `IS NULL`, `LIKE`, `AND`/`OR`/`NOT`,
sargs) reachable only from `WHERE`, and `Expr` (column, literal, `fn` over a closed catalog)
reachable from the projection. A derivation lives in the projection, so it gets the thin half: no
branch, no merge, no array producer.

This design adds seven catalog functions and one ingest transform. It deliberately does not change
the `Expr` model.

## Goals / Non-Goals

**Goals:**

- Make the target columns of the client/session/principal enrichment expressible as one Class-1
  evaluator version: `client_type`, `client_session_id`, `auth_type`, `user_ref`, `user_roles`,
  `client_session_source`.
- Keep every addition inside the existing extension mechanism — a catalog entry plus a translator
  branch, held in parity by `QueryFunctionCatalogParityTest`.
- Read the mixed-case header history without re-ingesting it.

**Non-Goals:**

- Arithmetic operators (`+`, `-`, `*`) in expressions. Nothing in scope needs them.
- Comparison operators in projection position. See Decision 2.
- Element typing for array expressions. See Decision 8.
- Any rewrite of stored `request_tags` values — the in-DB column-rewrite capability is a separate,
  later exploration.
- A generic user-defined-function surface. The catalog stays a closed allowlist.

## Decisions

### 1. Seven catalog functions, no new `Expr` kind

Two alternatives were considered and rejected.

*`IfExpr(FilterNode cond, Expr then, Expr else)`* — structurally elegant, since it makes the rich
filter language reachable from a projection and reuses `FilterTranslator` whole. Rejected because it
introduces a second way to express the same predicate: every consumer of the model
(`StructuredQueryToSqlRenderer`, translate-back, the object-field and sensitive-column walkers,
`ExprColumnCollector`) would have to walk a `FilterNode` nested inside an `Expr`, and the wire
contract for `Expr` — a closed five-kind sealed hierarchy documented in the `structured-query` spec —
would gain a kind whose payload is a different grammar.

*A `classifier` evaluator type* — an ordered `when`/`then` list compiled to `CASE`, confined to the
enrichment registry with zero blast radius on the read surface. Rejected as too specialised: the gap
is a general one in the expression language, and a second definition format for one shape of
derivation would need its own validation, its own discovery, and its own reasons to exist the next
time something is inexpressible.

Plain `fn` entries need neither. `if(cond, then, else)` is an ordinary three-argument function whose
first argument is boolean-typed.

### 2. `if`'s condition is a boolean-valued expression, not a comparison

`Expr` has no comparison node and this change does not add one. The condition producers are
`not_empty`, `starts_with`, `contains`, `equals`, and the existing `json_extract_bool`.

Rejected alternative: promote `ComparisonNode` into `Expr` so that `x > 0` works in a projection.
That is the general fix and it is genuinely useful, but it doubles the representation of every
predicate — the same `a = b` would be reachable as a `FilterNode` in `filter` and as an `Expr` in
`select`, and translate-back would have to choose. If arithmetic comparisons in projections are
wanted later, that is the change to make, deliberately and on its own.

The practical cost of not having it: `length(x) > 0` is spelled `not_empty(x)`, and a numeric
threshold test is not expressible. Nothing in scope needs a numeric threshold.

### 3. No `coalesce`, `nullif`, or `concat`

Both reasons point the same way.

First, they are redundant. With a NULL-safe `not_empty` (Decision 6), `if(not_empty(a), a, b)` is
`coalesce`, `nullif` and the mutually-exclusive `concat` merge in one form:

    user_ref          = if(not_empty(project_id), project_id, json_extract_string(jwt_claims,'user_id'))
    client_session_id = if(not_empty(a), a, if(not_empty(b), b, c))

Second, adding them by name is not free. Calcite's `StandardConvertletTable` expands `COALESCE` and
`NULLIF` into `CASE` at the Rex layer, so `RexToExprTranslator` would never see a `COALESCE` call —
it would see a `RexCall` of kind `CASE`, forcing a `CASE` → `Expr` mapping that nothing else in the
subset needs, and whose condition operand is exactly the comparison-in-expression-position that
Decision 2 declines. Shadowing the convertlet with a custom operator (Decision 4) would avoid the
expansion, but then two spellings of standard SQL behave differently in ADAS from every other engine.

Add them later if reads want them. They are not needed for derivations.

### 4. Register every new name as a custom operator ahead of Calcite's standard table

`SqlQueryOperators` already does this for `DATE_TRUNC`, where the class comment records that the
chain order is load-bearing because Calcite's own `DATE_TRUNC` takes its operands the other way
round. The same mechanism is what keeps the new names out of reach of dialect-library variants and
standard convertlets: the front end resolves against ADAS's table first, so the operator ADAS
declares is the operator that lowers.

### 5. The bare spellings parse — measured, not assumed

Operator registration resolves *names*; it cannot rescue a token the parser reserves before operator
resolution runs, so the spellings were checked against the Babel parser before anything was designed
around them.

**Result (Calcite Babel parser, this build):** `IF`, `CONTAINS` and `EQUALS` are keywords but
**non-reserved** ones (`Metadata.isReservedWord` is false for all three), and all seven statements —
`SELECT if(a,'x','y')`, `contains(a,'x')`, `equals(a,'x')`, `not_empty(a)`, `starts_with(a,'x')`,
`json_extract_string_ci(a,'x')`, `json_extract_array(a,'x')` — parse as ordinary function calls. No
fallback spelling is needed; `iff` and `str_contains` are dropped from the design.

Two consequences. First, the catalog name is the SQL name for all seven, so
`StructuredQueryToSqlRenderer`'s verbatim rendering round-trips. Second, the fact is a Calcite
property, not an ADAS one — a future upgrade that promoted one of these to reserved would break every
rendered statement carrying it, silently at parse time. That is why the throwaway spike becomes a
**permanent** front-end assertion rather than being deleted.

Parsing is not resolution: each name still has to be registered as an ADAS operator (Decision 4) for
the validator to type the call.

### 6. `not_empty` is NULL-safe by construction

Rendered as `notEmpty(ifNull(<operand>, ''))`, not bare `notEmpty`. ClickHouse's `notEmpty(NULL)`
yields NULL, and a NULL condition would fall to `if`'s else branch by accident rather than by
contract. Making it explicitly false for both NULL and the empty string is what lets one function
serve both ADAS's empty-string absence convention (`json_extract_string` yields `''` when a key is
missing; non-nullable mapped columns store `''`) and genuinely nullable columns — and it is what
makes Decision 3 hold.

### 7. `json_extract_string_ci` lowercases keys, never values

Rendered as an `arrayFirst` over the key/value pairs with the keys lowered:

    arrayFirst(kv -> kv.1 = '<key lowered at translation time>',
               arrayMap(kv -> (lower(kv.1), kv.2),
                        JSONExtractKeysAndValues(<json>, 'String'))).2

The search key is a string literal, so it is lowered once during translation rather than per row.

Rejected alternative: `JSONExtractString(lower(<json>), '<key>')`. Lowercasing the JSON *text*
lowercases values too, which silently corrupts emails, display names and any case-sensitive session
id. Cheap and wrong.

**Over an `object` column the operand must be `toJSONString(<col>)`, not the column.** This is the
single most consequential correction to the original shape of this design, because `request_tags` and
`jwt_claims` — the columns the whole change exists to read — are `object` columns, and the naive
rendering fails *silently* on exactly them. Measured on ClickHouse 26.3:

| expression over a `JSON` column | result |
|---|---|
| `JSONExtractKeysAndValues(tags, 'String')` | `[]` — **no error** |
| `JSONExtractKeysAndValues(toJSONString(tags), 'String')` | the real key/value list |
| `JSONExtract(tags, 'roles', 'Array(String)')` | `ILLEGAL_TYPE_OF_ARGUMENT` (code 43) |
| `JSONExtract(toJSONString(tags), 'roles', 'Array(String)')` | `['a','B']`; `[]` for an absent or non-array member |

So `json_extract_string_ci` over an `object` column would have returned the empty string for every
row, with nothing raised anywhere — the failure mode no test catches unless it is written on purpose.
`json_extract_array` at least fails loudly. Both therefore render over `toJSONString(<col>)` when the
JSON argument is an `object` field, and the ordinary text accessor when it is a `string` field.

Why not reuse the existing `object` form's subcolumn access, which the other four extractors use and
which reads ~20x less data? Neither shape can: a case-insensitive match has no statically known
subcolumn to address (the whole point is that the key's spelling is unknown), and an array subcolumn
comes back typed `Dynamic` — `CAST(tags.roles, 'Array(String)')` does work, but its totality over a
non-array member is unproven where `JSONExtract(toJSONString(…), …, 'Array(String)')` is measured
total. Full-document materialization is the cost of both shapes, which is the same cost
`json_extract_string_ci` already carries over text.

The cost is real: this materialises the whole header map per row, where `json_extract_string` parses
on demand and short-circuits. It is not obviously worse than the status quo — reading N headers today
costs 2N targeted parses (one per spelling), and if ClickHouse's common-subexpression elimination
folds the identical `JSONExtractKeysAndValues` across all columns of one `INSERT … SELECT`, it costs
one full parse instead. Whether CSE fires is a measurement, not an assumption; see Risks.

### 8. Array expressions stay untyped

`ExprSqlValidator.toFieldType` currently throws on `ResultColumnKind.ARRAY`. It will return
`QueryFieldType.ARRAY` — and that is the whole of the code change, because
`EnrichmentTypeMapping.isCompatible` is already `varType == columnType || (both NUMERIC)`, so `ARRAY`
against an array column passes and `ARRAY` against a scalar column fails with no edit at all. What
`EnrichmentTypeMapping` needs is a javadoc line saying the omission of an element-type comparison is
deliberate, so the next reader does not "fix" it.

Rejected alternative: model element types now. The catalog carries `element_type` on *declared*
columns, but a computed column has none — `group_uniq_array` already establishes that a computed
array's element kind follows its operand rather than a declaration. Typing this properly means
threading an element kind through `QueryFieldType`, which is a larger change than the one column
that needs it. The accepted consequence: an `array<long>`-producing expression may bind to an
`array<string>` column and fail at ClickHouse insert time rather than at rule registration.

### 9. The predicate producers are made NULL-safe in the rendering

The spec says `starts_with`, `contains` and `equals` are false — never null — for a null operand.
ClickHouse gives none of them for free: `startsWith(NULL,'x')`, `position(NULL,'x') > 0` and
`NULL = 'x'` all evaluate to NULL (measured). So each renders wrapped: `ifNull(<test>, false)`, the
same construction Decision 6 applies to `not_empty`.

`if` needs no such wrap on its *condition*: ClickHouse's `if` and `CASE WHEN` both take the else
branch for a NULL condition (measured), which is exactly the contract. The wrap is for the standalone
case — a projected `equals(a, b)` must serialize as a boolean, and a nullable operand would otherwise
make it null.

### 10. `if`'s two type rules are enforced per surface, with what each surface has

The spec requires HTTP 400 both for a non-boolean condition and for branches that do not unify. The
two surfaces reach that from different directions and neither is free:

- **SQL surface.** Calcite does it during validation, from the operator's declared signature: a
  boolean-family first operand and a least-restrictive return type over the two branches, which fails
  to derive when the branches share no common type. `RelToStructuredQueryTranslator.fnKind` then types
  the column from the branches under the same numeric-widening rule.
- **DSL surface.** This was designed as "no type inference needed — every branch is already a jOOQ
  `Field` with a Java type", and **that was wrong**, which is worth recording because the reasoning was
  plausible and the failures were silent. `JooqTypes` is a *lossy* image of the DSL's types in both
  directions: `long` and `timestamp` both become `BIGINT`/`Long`, `object`, `array` and `map` all become
  `OTHER`/`Object`, and a timestamp literal renders through `fromUnixTimestamp64Milli` carrying neither.
  Reading branch types off that unified a timestamp with a long (a pair ClickHouse has no common type
  for, so the promised HTTP 400 became a server error), unified an object with an array, and *refused* a
  timestamp column against a timestamp literal.

  What shipped is a small resolver, `translate.ExprFieldType`: `Expr` → `QueryFieldType` from the
  bindings for a column, the literal's own `ValueType` for a literal, and the catalog descriptor's
  declared `returns` for a call — with `same_as_argument` following the one argument its result type
  comes from. It is total over everything that can reach it, because `ParamExpr`/`ArrayExpr` are rejected
  earlier, an unknown column is rejected earlier, and an uncatalogued function is rejected earlier; the
  single unresolvable input is the null literal, which has no type and is refused on its own terms. That
  is a smaller change than "a second type-inference pass" made it sound — it reads declarations rather
  than inferring anything — and it is what lets the condition check, the branch check and `equals`'s
  comparability check all ask one question in one vocabulary.

### 11. The array projection-only guard stops being about one function

`ArrayValuedAggregate` recognizes `group_uniq_array` by name and owns `rejectAsOperand`, called from
`ExprTranslator`, `FilterTranslator` and `StructuredQueryToSqlRenderer`. `json_extract_array` is the
second array-returning catalog function, so the class becomes a **set** of array-returning names
rather than gaining a parallel guard beside it. One classifier, three call sites, unchanged; the
message names the offending function.

The same generalization is what keeps `RelToStructuredQueryTranslator` honest: an array result is
typed in `resultColumn()` (which can carry an element kind) and **throws** in `fnKind` (which cannot),
because reaching `fnKind` means the call was nested inside another expression — precisely the case
being refused. `json_extract_array` follows `group_uniq_array` into both halves of that arrangement,
with a fixed element kind of `string` rather than an operand-derived one.

### 12. `lowercase-json-keys` ships despite `json_extract_string_ci`

The two overlap: the CI function reads mixed-case and lower-case maps identically, so the transform
is not required for correctness. It is kept because it removes the per-row map materialisation for
rows ingested after the cutover. History stays mixed-case and is served by the CI function; no
re-ingest and no in-DB rewrite is part of this change.

## Risks / Trade-offs

- **`json_extract_string_ci` is slower than a targeted extract, by an unmeasured factor** → `EXPLAIN`
  a representative multi-column `INSERT … SELECT` on real ClickHouse before the function is
  advertised, and confirm whether CSE folds the repeated per-row work. Over `request_tags` — an
  `object` column — that work is `JSONExtractKeysAndValues(toJSONString(request_tags), 'String')`, so
  the question is whether both the serialization and the key-set parse fold across several calls in
  one statement. Reference volume: tens of thousands of rows per day, on the order of a million historically.
- **`if` or `contains` is rejected by the parser** → measured and closed before any catalog or
  translator work: all seven spellings parse (Decision 5). The residual risk is a future Calcite
  upgrade reserving one of them, which the permanent parser assertion catches.
- **The two new extractors silently return nothing over an `object` column** → the highest-impact
  risk in this change, because the target columns are `object` columns. Measured and closed by
  Decision 7's `toJSONString` rendering; the Flight SQL live test is what keeps it closed, since the
  broken form raises no error.
- **The object-field projection-only rule is enforced in one place, not several** — an earlier
  reading of this design had it duplicated across the execution front end and the translate-back
  renderer. It is not: `SqlProjectionOnlyTypes.JSON_EXTRACT_FUNCTIONS` is the single classifier both
  directions consult, precisely so they cannot drift. Adding the two names there is the whole edit;
  `query-translation-endpoint`'s prose spells the same list out and must be kept in step with it.
- **`if`'s branch types may disagree** (`if(c, 'x', 1)`) → the branches must unify under the
  numeric-widening rule `EnrichmentTypeMapping` already uses for output bindings, rejecting anything
  else. Enforcement mechanism per surface is Decision 10.
- **The new functions widen the read surface too**, not just derivations — they reach
  `/v1/queries/execute`, `/execute-sql`, saved queries, aggregate pipeline specs and Flight SQL by
  construction. That is intended (the catalog is shared on purpose), but it means the Flight SQL
  live-rendering test must cover them, as the `date_trunc` change did.
- **`lowercase-json-keys` splits `request_tags` key case at a cutover date** → accepted; the CI
  function reads both sides, and the split is recorded in the `data-sync` spec.

## Migration Plan

No data migration. No config keys. No catalog schema change: the enrichment table's target columns
are already declared nullable, so a new evaluator version fills them without a patch.

Deploy is a single service release. Rollback is a redeploy: the catalog additions are new surface, so
no existing query, rule or mapping depends on them. The one non-reversible edge is a rule registered
against a new function while the new build is live — after a rollback its expression no longer
validates, and `materialize` fails for that rule until it is rolled forward or the rule is disabled.

`lowercase-json-keys` takes effect for rows ingested after deploy only. Rows already stored keep
their original key case indefinitely unless a separate rewrite is run.

## Resolved Questions

All three questions this design opened are now answered in the `structured-query` delta; they are
kept here with their reasoning so the delta's requirement text is not the only record.

- **Does `if` require its branches to be the same type, or does it widen numerically?** → **Widens
  numerically.** `integer`/`long`/`decimal` are mutually acceptable and the call returns the widest;
  anything else is rejected. This reuses `EnrichmentTypeMapping`'s NUMERIC set, so an `if` result
  bound to an enrichment column is judged by the same rule that judges every other output binding.
  The catalog descriptor's `returns` is therefore `same_as_argument`.
- **Do `starts_with` / `contains` compare case-sensitively?** → **Case-sensitive, no `_ci`
  variants.** ClickHouse `startsWith`/`position` are case-sensitive and `lower()` is already in the
  catalog, so the user-agent classification wraps its operand explicitly. A second spelling of every
  predicate would double the catalog to save one call.
- **What does `json_extract_array` return for a member that is not an array?** → **The empty array**,
  consistent with the other extractors returning their type's zero value rather than failing.
  Measured: `JSONExtract(<text>, 'k', 'Array(String)')` yields `[]` for an absent member and for a
  non-array member alike. The accepted consequence is that "absent" and "empty JSON array" are
  indistinguishable, exactly as "absent" and "empty string" are for `json_extract_string`.

## Still Open

Nothing. The last question — the cost one — is answered below.

### Measured: CSE folds the repeated document read

**Question:** does ClickHouse's common-subexpression elimination fold the full-document read when one
statement calls `json_extract_string_ci` several times over the same column? That is the shape a
derivation actually takes: one `INSERT … SELECT` filling several session/client columns from
`request_tags`.

**Answer: yes.** `EXPLAIN actions = 1` over a three-call projection on ClickHouse 26.3 emits exactly
one `FUNCTION toJSONString(...)`, one `FUNCTION JSONExtractKeysAndValues(...)` and one
`FUNCTION arrayMap(...)`, feeding three `arrayFirst` calls that share the single lowered key/value
array.

So the cost of *n* case-insensitive extractions over one column is one serialization plus one key-set
parse plus one lowering pass, and then *n* linear scans of an already-materialized array — not *n*
full document reads. That is the comparison the function needed: reading *n* headers with
`json_extract_string` today costs 2*n* targeted parses (one per candidate spelling), so the CI form is
cheaper at the volumes in scope rather than more expensive, and the per-call cost stops growing after
the first. Against a **single** extraction `json_extract_string` still wins, which is why the spec
says to reach for `_ci` only where the key's case is genuinely unknown.
