## Why

Exploding an array into one row per element is the one shape of array question the query surface
cannot answer. The catalog can *test* an array (`array_has`), *measure* it (`array_length`) and
*compute* one (`map_keys`, `split_string`, `json_extract_array`), but a "requests per topic"
distribution over an open-vocabulary array column has no expression at all: an `array` field is
projection-only, so it is rejected as a `group_by` key, and no function turns its elements into
rows. The 2026-08-27 `widen-query-expression-surface` change declined this deliberately and
recorded it as the ask's deferred other half; this change is that half.

ClickHouse already has the function. This change exposes it, and **nothing beyond it**: same operand
count, same semantics, same positions. Where the DSL cannot follow — a `Map` operand, which
ClickHouse expands into tuples — the reason is a gap in the DSL's own type vocabulary, stated as
such rather than dressed up as a rule.

## What Changes

- **New catalog function `array_join(array)`** — one operand, mirroring ClickHouse `arrayJoin(arr)`
  exactly:
  - it evaluates to **one output row per element** of `array`, and is the only catalog function whose
    call changes the number of rows a query returns;
  - a row whose array is empty produces **no** output rows;
  - its declared return type is the array's **element** type, not an array and not the operand's own
    type.
- **No mode argument, and no left variant.** ClickHouse's `arrayJoin` takes exactly one operand
  (`arrayJoin(x, 'left')` is `NUMBER_OF_ARGUMENTS_DOESNT_MATCH`), and its `LEFT ARRAY JOIN` is a
  clause, not a function. Synthesizing a left mode would be surface this project invented; it is out
  of scope, and the clause remains the honest route if it is ever wanted.
- **Argument 0** accepts an `array` field despite the projection-only rule — the same positional
  carve-out the array accessors already have — and accepts the array-returning catalog scalars
  (`map_keys`, `split_string`, `json_extract_array`), so `array_join(split_string(tags, ','))` and
  `array_join(map_keys(attrs))` are expressible. A non-array operand is rejected, which is
  ClickHouse's own rejection (`ILLEGAL_TYPE_OF_ARGUMENT`), not an added rule.
- **`group_by` over individual array elements becomes expressible**: an `array_join` select entry
  carrying an alias, grouped by that alias — the ClickHouse idiom, verified against a live engine.
  This **modifies** the current `structured-query` statement that "exploding an array into one row
  per element is NOT in this catalog".
- **No positional restrictions beyond ClickHouse's own.** ClickHouse 26.3 accepts `arrayJoin` in
  `WHERE`, inside an aggregate's arguments, under a window's `OVER (ORDER BY …)`, and in `ORDER BY`;
  all four are therefore accepted here. Two select entries expanding *different* expressions produce
  ClickHouse's cartesian product and two expanding the *same* expression are deduplicated by
  ClickHouse — inherited behaviour, documented rather than refused.
- **`include_total` is refused together with `array_join` in row mode**: this one is about *our*
  response contract, not ClickHouse. The total-count query counts source rows before expansion, so the
  number it would report describes a different question than the paged result. Aggregate mode is
  exempt: no count is ever taken there, so the flag is already inert and there is nothing to be wrong.
- **SQL surface parity**: `array_join(...)` is accepted by the SQL front end (`execute-sql`,
  `translate-sql`, `validate-sql`, Flight SQL), translated to the same DSL call, and emitted by the
  DSL to SQL renderer — so the DSL/SQL round-trip stays exact, as it is for every other catalog
  function.
- **Discovery**: the new function appears in `GET /v1/queries/functions`, with a new `returns` token
  `element_of_argument` for "the element type of argument 0" (the existing `array` and
  `same_as_argument` tokens both name the wrong shape for it).

Not breaking: the function and the `returns` token are new surface. The one statement that changes
rather than extends is the `structured-query` sentence declaring element-level grouping
inexpressible.

### Deliberately out of scope

- **A left/outer variant in any spelling.** ClickHouse's function has none. `LEFT ARRAY JOIN` is a
  clause, and adding it means the clause, not a second operand this project made up.
- **The `ARRAY JOIN` clause and a new `StructuredQuery` section.** It is the real ClickHouse route to
  the three things the function form genuinely cannot reach — zipping two arrays element-wise
  (`ARRAY JOIN a, b`, which the function form turns into a cartesian product), element indices via
  `arrayEnumerate`, and `Nested` expansion. It is declined here on scope, not on principle: Calcite
  has no `ARRAY JOIN`, so those queries would work on `/execute` and fail on `/translate`, and the
  section is a change of its own.
- **A `Map` operand.** ClickHouse accepts one and expands it into `Tuple(key, value)`. The DSL has no
  tuple type — `QueryFieldType` has nowhere for one to land and the response envelope has no shape
  for it — so this is a type-vocabulary gap, not a policy. `array_join(map_keys(m))` covers the keys.
- **Nested expansion of a nested array** (`array_join(array_join(x))`). ClickHouse accepts it, but the
  catalog's `element_type` names a scalar type only, so an array-of-arrays column cannot be
  catalogued in the first place and the shape is unreachable rather than forbidden.
- **`array_join` as an aggregate-pipeline measure or dimension.** A pipeline measure resolves only
  against the catalog's *aggregate* group, so a scalar is already refused; changing row cardinality
  under an incrementally refreshed aggregate is a separate question with its own correctness
  argument.

## Capabilities

### New Capabilities

None — this extends the existing query surface.

### Modified Capabilities

- `structured-query`: adds `array_join` to the closed function catalog; states its row-multiplying
  semantics, the empty-array behaviour, the element-type result, the multi-expansion behaviour, and
  the `include_total` interaction; replaces the sentence declaring element-level grouping
  inexpressible.
- `query-schema-discovery`: publishes the `array_join` descriptor and the new `element_of_argument`
  `returns` token.
- `sql-query-translation`: accepts `ARRAY_JOIN(array)` in the SQL subset and translates it to the DSL
  call with the element-typed result column.
- `query-translation-endpoint`: renders an `array_join` call back to SQL, keeping the round-trip
  exact.

## Impact

**Code (main):**

- `service/query/QueryFunctionCatalog` — the new descriptor.
- `service/query/ArrayValuedFunctions` — `array_join` joins the array-operand position set, so the
  computed-array and declared-column carve-outs stay in agreement.
- `service/query/translate/ExprTranslator` — the new function branch, rendering `arrayJoin(...)`.
- `service/query/translate/ExprFieldType` — the `element_of_argument` return-type rule.
- `service/query/translate/StructuredQueryBuilder` — the `include_total` refusal (the only guard).
- `service/query/sql/SqlQueryOperators` — the `ARRAY_JOIN` SQL operator.
- `service/query/sql/RexToExprTranslator`, `RelToStructuredQueryTranslator` — SQL to DSL lowering and
  the result column's element type.
- `service/query/sql/StructuredQueryToSqlRenderer` — DSL to SQL rendering.
- `service/query/sql/SqlProjectionOnlyTypes` — the `array` carve-out for argument 0 (by delegation).
- `web/dto/query/QueryFunctionReturnType` — the `element_of_argument` token.

**APIs:** `POST /v1/queries/execute`, `/execute-sql`, `/translate`, `/translate-sql`,
`/validate-sql`, `GET /v1/queries/functions`, and the Flight SQL endpoint — all additively.

**No changes to:** configuration (no new env vars), database schema/migrations, the aggregate
pipeline, saved-query storage, or authentication.
