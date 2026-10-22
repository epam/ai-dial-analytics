## 1. Fix the dropped computed sort key (design D1/D2)

- [x] 1.1 In `sql/RelToStructuredQueryTranslator#projectCtx`, after translating the projection: when the
      folded block is `sorted` and any `sortEntries` expression is **neither** present in the new outputs
      **nor** a bare `FieldExpr`, seal the block into a derived table and re-translate the projection
      against the sealed context (the `RexInputRef` ordinals index the sealed columns one-for-one).
      A projection that carries its key, and a key that is a plain column, MUST keep folding into one
      block — assert both by test, since that is what keeps every statement translating today unchanged
- [x] 1.2 Carry the sealed block's row bound outward on that path only: `fetch` = the sealed block's
      `fetch`, `sorted = true`, and **no** `offset` (design D2 — re-applying it would skip rows twice).
      Add a javadoc paragraph on the helper stating why the limit is re-applied and the offset is not
- [x] 1.3 Widen `RelToStructuredQueryTranslator.NO_DSL_EQUIVALENT` to name this case as well, so the
      refusal `POST /v1/queries/translate-sql` reports for such a statement is honest — every existing
      test asserts the constant, not the literal string. **Two shipped specs quote the old string
      verbatim** (`sql-query-translation`'s "Composite statements execute without a structured-query
      document" and `query-translation-endpoint`'s "SQL translates to the structured query DSL without
      execution"), so both are carried as MODIFIED requirements in this change's delta with the new
      message and every scenario they keep — otherwise archiving would mandate a string the code no
      longer emits (round 2)
- [x] 1.4 Front-end tests (`SqlCompositeTranslationTest`/`SqlQueryFrontEndTest`) for each measured shape:
      `GROUP BY … ORDER BY count(*) DESC LIMIT 5` with no derived table; the same inside `IN (…)`;
      `SELECT d FROM (… ORDER BY n DESC LIMIT 5) t`; a row-mode `ORDER BY length(event_kind) LIMIT 5`;
      the aggregate-mode `HAVING … ORDER BY count(*)` form. Each asserts the plan is composite, the inner
      block carries the sort and the bound, and the outer block carries the same `LIMIT` and no `OFFSET`
- [x] 1.5 Regression tests that the shapes which work today are byte-identical: `ORDER BY <plain column>`
      not projected stays a single-block `SqlTranslation`; `SELECT d, n FROM (… ORDER BY n …)` stays one
      block; `SELECT count(*) … GROUP BY event_kind ORDER BY event_kind` stays one block
- [x] 1.6 Governance tests: `… ORDER BY count(*) DESC LIMIT 500` reports `explicitLimit` true and is not
      clamped to the 100-row default; the same statement with `LIMIT 1500` is rejected with the page-limit
      error; an `OFFSET 10 LIMIT 5` form applies the offset exactly once
- [x] 1.7 Carry the sealed block's **collation** outward too, not only its bound (round 3): rebuild each
      `SortEntry` as a `FieldExpr` naming the sealed block's column for that key, direction and null
      ordering unchanged. Without it the outer block has no `ORDER BY` and the ordering the spec promises
      is not in the executed SQL — row order out of a subquery is not contractual and ClickHouse's
      default-on `query_plan_remove_redundant_sorting` strips the inner one when the outer block does not
      sort, which the no-`LIMIT` `HAVING` form is exactly. Requires the sealed column names to be computed
      once and handed to `seal`, so both halves name the same columns
- [x] 1.8 Guard the one hazard that comes with naming a sealed column: a select output named after the
      column a sort key names, while projecting a different expression, would capture the key (the DSL
      resolves `sort` against outputs first). Refuse it in `sortFieldName` the way
      `rejectAliasShadowingGroupColumn` refuses the same collision on a `GROUP BY` column
- [x] 1.9 Pin the ordering by test at both levels: the outer block's `sort` in
      `SqlCompositeTranslationTest` (aggregate, row-mode, multi-key and no-`LIMIT` `HAVING` forms), the
      emitted `order by "t0"."…"` in `CompositeQueryRenderTest`, and the actual row sequence against
      ClickHouse over rows a wrong order would separate — three distinct group counts and four distinct
      computed lengths, since the earlier assertions had a single row and a tie

## 2. Text composition on the DSL surface (design D3)

- [x] 2.1 Add the `concat` and `to_string` descriptors to `QueryFunctionCatalog` in alphabetical position
      (`concat` after `ceil`, `to_string` after **`sum`** — alphabetically `sum` < `to_string` < `trim`),
      group `scalar`, returns `string`,
      `distinct_supported` false. `concat`'s second argument carries `variadic: true`; both descriptions
      state their null contract — they differ (task 2.2) — and, for `concat`, that `to_string` is the
      single-value form
- [x] 2.2 Add one private rendering helper to `translate/ExprTranslator` — the single definition of "this
      scalar as text": `toString({0})` for every scalar type, `if({0}, 'true', 'false')` for `boolean`
      (design D3: the physical column may be `Bool` or `UInt8`, so the advertised rendering must not depend
      on it). `to_string`, `concat` and `array_to_string` all render through it. **The null guard is NOT in
      the helper**: `to_string` preserves null (cast semantics, which task 4.5 depends on) and the two
      composing functions wrap each operand in `ifNull(…, '')` themselves
- [x] 2.3 Add the `to_string` branch to `ExprTranslator#toFunction`: exactly one argument, rejected when
      its `ExprFieldType` is non-scalar (`object`/`array`/`map`) or a null literal, with a message naming
      the operand's type. `enum` is accepted deliberately (design D3 / the enum spec delta) — pin it by test
- [x] 2.4 Add the `concat` branch: two or more arguments (a one-argument call rejected with a message
      naming the minimum and pointing at `to_string`), every argument scalar, no operand-type agreement
      required, rendered as `concat(<text>, <text>, …)`
- [x] 2.5 Teach `translate/ExprFieldType` nothing new for these two — both declare `returns: string`, so
      the existing `STRING` case covers them. Confirm this explicitly rather than silently skipping it

## 3. Array text rendering and slicing on the DSL surface (design D5/D6)

- [x] 3.1 Add the `array_to_string` and `array_slice` descriptors to `QueryFunctionCatalog`, both after
      `array_length`: `array_to_string(array, delimiter)` returning `string` with a `string_literal`
      second argument, `array_slice(array, offset, length)` returning `array` with two `integer_literal`
      bounds. Both descriptions state the array-operand carve-out and, for `array_slice`, that its own
      result is projection-only
- [x] 3.2 Add `"array_to_string"` and `"array_slice"` to `ArrayValuedFunctions.ARRAY_OPERAND_FUNCTIONS`,
      and `"array_slice"` **also** to `NAMES` — it is the first function in both sets (it consumes an array
      and produces one). Update the class javadoc to say so, including why `array_to_string` is not in
      `NAMES` (its result is text)
- [x] 3.3 Add the `array_to_string` branch to `ExprTranslator#toFunction`: exactly two arguments, argument 0
      through `arrayArgument`, argument 1 a string literal (empty allowed, unlike `split_string`'s
      delimiter — note why in the javadoc), rendered as
      `ifNull(arrayStringConcat(arrayMap(e -> ifNull(<e as text>, ''), {0}), {1}), '')`. The `arrayMap` is
      load-bearing (`arrayStringConcat` is defined over `Array(String)`) — say so in the javadoc
- [x] 3.3a Render the element through task 2.2's helper, **type-aware**, not through a bare `toString`
      (round 3): the element type comes from `ExprFieldType`'s `array_join` derivation, so a `boolean`
      element takes the `if(e, 'true', 'false')` branch. Without it a `group_uniq_array` over any
      `UInt8`-producing boolean call (`not_empty(…)`, `map_contains(…)`, a `CASE` over a comparison)
      renders `1,0` where the spec — and `to_string` of the same value — says `true,false`. Cover both
      physical shapes by test: a real `Bool` column and a `UInt8`-valued call
- [x] 3.4 Add the `array_slice` branch: exactly three arguments, argument 0 through `arrayArgument`, a
      non-zero integer-literal `offset` (negative counts from the end) and a positive integer-literal
      `length`, each rejection naming the argument at fault; rendered bare as `arraySlice({0}, {1}, {2})`
      (design D6 — a ClickHouse `Array(T)` column cannot be `Nullable`, so there is nothing to normalize)
- [x] 3.5 Add the `array_slice` case to `ExprFieldType.ofElementOfFirstArg`: the element type of a slice is
      its **own** operand's element type, resolved recursively, not the fixed `string` the three
      text-producing array scalars carry — otherwise
      `array_join(array_slice(group_uniq_array(total_tokens), 1, 3))` types as text
- [x] 3.6 Confirm no positional guard is needed for either function beyond the carve-out sets, and that the
      aggregate pipeline's measure rules still refuse both (they resolve against `ArrayValuedFunctions`) —
      pin by test rather than by inspection

## 4. SQL front end (SQL → DSL) (design D4/D5)

- [x] 4.1 Register `CONCAT`, `TO_STRING`, `ARRAY_TO_STRING` and `ARRAY_SLICE` in `sql/SqlQueryOperators`
      and add them to `TABLE`: `CONCAT` variadic over `ANY` with a minimum of two operands returning
      `VARCHAR`; `TO_STRING` one `ANY` operand returning `VARCHAR`; `ARRAY_TO_STRING` `(ARRAY, CHARACTER)`
      returning `VARCHAR`; `ARRAY_SLICE` `(ARRAY, INTEGER, INTEGER)` returning its argument-0 type.
      Document in the class javadoc that our `CONCAT` is chained ahead of Calcite's library `CONCAT`
      (string-only) on purpose, and that the literal requirements are the translator's to enforce
- [x] 4.2 Assert by test that `CONCAT`, `TO_STRING`, `ARRAY_TO_STRING` and `ARRAY_SLICE` each parse as a
      function call under the Babel grammar — the measured-not-assumed check `IF` and `ARRAY_JOIN` carry,
      so a Calcite upgrade that reserved one fails there rather than in every rendered statement
- [x] 4.3 Add a new `sql/SqlTextRebinder` that swaps the operator on every parsed `||` call for
      `SqlQueryOperators.CONCAT` **before validation**, and call it beside `SqlDateTruncRebinder` in
      `SqlQueryFrontEnd`. Its javadoc states why an operator-table entry cannot do this and why it is a
      separate class from the date-trunc re-binder (design D4). **Named for text rather than for concat
      because task 4.5 moved the cast rewrite into it too**
- [x] 4.4 Add the four cases to `sql/RexToExprTranslator#callExpr`: `CONCAT` and `TO_STRING` through a
      variadic/fixed `fn` (minimum-arity message for `CONCAT`), `ARRAY_TO_STRING` and `ARRAY_SLICE` through
      `accessorFn` so argument 0 takes the container carve-out. `||` arrives as the re-bound `CONCAT`, so
      no separate operator case is needed — assert that by test
- [x] 4.5 Lower a caller-written `CAST(<expr> AS <character type>)` onto `to_string`. **Not in
      `RexToExprTranslator#castExpr` as planned**: implemented there first and it was wrong — Calcite's
      implicit coercion injects casts to character, so every text function silently widened to accept any
      type (`not_empty(total_tokens)` began succeeding). Moved into `SqlTextRebinder`, which sees only what
      the caller wrote; the `Rex` path keeps rejecting an injected cast, with a comment recording why.
      **Round-1 correction**: the pre-validation tree cannot tell a cast that needs rendering from an
      identity cast of text, so the swap emits the `TO_STRING_CAST` marker and `RexToExprTranslator`
      resolves it where the operand has a type (task 6.4). **Round-2 correction**: the swap therefore runs
      *after* `SqlSelectAliasInjector`, not before — the injector names an unnamed select item after the
      operator it finds, so rewriting first named an unaliased `CAST(x AS VARCHAR)` `to_string_cast`, an
      internal marker's name in a result column, an Arrow schema and a DSL document, where PostgreSQL (and
      this surface, before either rewrite existed) says `?column?`. The order is stated in both javadocs.
      **Round-4 correction**: registering the marker in `SqlQueryOperators.TABLE` also made
      `TO_STRING_CAST(x)` resolvable in a statement a *client* wrote, though the name is in neither the DSL
      catalog nor `GET /v1/queries/functions` — against the subset's own "a function outside the catalog is
      rejected" rule. `SqlTextRebinder` now refuses a caller-written marker as its walk's first act, before
      it introduces one of its own; the delta spec states it and a front-end test pins both operand kinds
      and both casings
- [x] 4.6 In `sql/RelToStructuredQueryTranslator`: add `concat`, `to_string`, `array_to_string` to `fnKind`
      as `STRING`; add `array_slice` to the array-returning group so a **nested** use is refused with the
      existing "returns an array and can only be projected" message; type a **projected** `array_slice` in
      `resultColumn` as `ARRAY` with the element kind of its own operand (the recursive case, mirroring
      task 3.5); add the same recursive case to `arrayJoinKind`
- [x] 4.7 Confirm `sql/SqlProjectionOnlyTypes` needs no edit — its array carve-out delegates to
      `ArrayValuedFunctions.acceptsArrayOperand` — and pin by test that a declared `array` column is
      accepted at argument 0 of both new functions and rejected everywhere else

## 5. DSL → SQL rendering (design D6)

- [x] 5.1 Confirm `sql/StructuredQueryToSqlRenderer` needs no new case: it emits `name(args…)` for any
      function without an arithmetic or ordered-set special case, and `rejectComputedArrayKey` picks up
      `array_slice` through `ArrayValuedFunctions.isArrayValued`. Verify by test rather than by inspection —
      including that the renderer never emits `||` or a `CAST`
- [x] 5.2 Renderer tests: `concat` over mixed operands, `to_string` over a numeric column,
      `array_to_string(group_uniq_array(...), ', ')`, the nested
      `array_to_string(array_slice(group_uniq_array(...), 1, 6), ', ')`, and an `array_slice` alias named in
      `group_by`/`sort` failing closed with `bad_request`. **That last refusal is SQL-surface only** — the
      execute path resolves such a key against the select output, exactly as it does for a `map_keys`
      alias — so the structured-query delta's projection-only scenario is worded the way the shipped
      `map_keys` one is rather than claiming an `/execute` rejection that does not exist (round 2)

## 6. Tests

- [x] 6.1 `QueryFunctionCatalogParityTest`: the four new descriptors are accepted by the translator, with
      `concat`'s variadic minimum and the two literal-argument shapes enforced
- [x] 6.2 DSL acceptance tests: a concatenated label; `concat` with a null operand yielding the prefix
      rather than null; `to_string` over each scalar family including `enum`; a rendered label used as a
      `group_by` key, a `sort` key and a filter operand
- [x] 6.3 DSL rejection tests, each asserting HTTP 400, `error` `bad_request` and that the message names
      the reason: `concat(deployment)`; `to_string(execution_path)`; `to_string(point_attributes)`;
      `concat(map_keys(...), 'x')`; `array_to_string(execution_path, deployment)`;
      `array_slice(execution_path, 0, 3)`; `array_slice(execution_path, 1, 0)`;
      `array_slice(execution_path, 1, total_tokens)`
- [x] 6.4 SQL→DSL tests: each new call translating; `'a' || 'b'` and `'depth ' || total_tokens` both
      becoming `concat`; a three-operand `||` chain nesting; `CAST(total_tokens AS VARCHAR)` becoming
      `to_string` in a projection **and** in a `WHERE`; the identity case staying the bare column in all
      three positions (`SELECT CAST(project_id AS VARCHAR)`, `WHERE CAST(project_id AS VARCHAR) = 'p1'`,
      `GROUP BY CAST(deployment AS VARCHAR)`), which is what the `TO_STRING_CAST` marker exists for;
      an **unaliased** `CAST(project_id AS VARCHAR)`, `CAST(total_tokens AS VARCHAR)` and
      `project_id || '-'` each keeping the `?column?` derived name, which is what pins the re-binder's
      position after alias injection (round 2);
      `CAST(deployment AS INTEGER)` still rejected; `ARRAY_SLICE` refused as a group key by its
      alias; the element-typed result of `ARRAY_JOIN(ARRAY_SLICE(...))`
- [x] 6.5 Round-trip tests in the translation-endpoint suite: DSL → SQL → DSL for `concat`, `to_string`,
      `array_to_string`, and `array_to_string(array_slice(group_uniq_array(x), 1, 6), ', ')`
- [x] 6.6 Discovery test: `GET /v1/queries/functions` lists all four in alphabetical position with the
      declared `returns`, `args` and flags, and the response parses **strictly** as JSON (not only via
      `jsonPath`)
- [x] 6.7 An integration test over the real ClickHouse container covering one statement per new function
      and the fixed sort-key shape, so the renderings are exercised against the engine in CI and not only
      in the one-off runtime verification below

## 7. Runtime verification against ClickHouse

- [x] 7.1 Verify every row of the design D6 rendering table against a real ClickHouse, and every
      row of the spec's `to_string` rendering table. Done as a permanent integration test
      (`TextCompositionIntegrationTest`) rather than a one-off, so the claims stay measured in CI.
      **One row was wrong and the spec was amended to what was measured**: ClickHouse's
      `toString(Decimal(38, 12))` prints `1.5`, trimming trailing zeros rather than padding to the scale.
      Every row the `dial_usage_log` fixture can carry is measured (string, uuid, integer, decimal,
      boolean, timestamp); the `enum` and `date` rows rest on the offline tests plus this contract, since
      that table has no column of either type
- [x] 7.2 Verify `array_to_string` over an `Array(String)` column, over an `Array(LowCardinality(String))`
      one (`execution_path`), over a numeric `group_uniq_array` result, and over an empty array (the empty
      string, not null) — a silent empty over a container column is the failure the preceding array change
      actually hit
- [x] 7.3 Verify `array_slice` bounds behaviour: a slice past the end returning what exists, a negative
      offset counting from the end, and an offset past the end returning an empty array
- [x] 7.4 Exercise `POST /v1/queries/execute-sql` end to end for the four fixed sort-key shapes and for the
      two dashboard cells this change exists for
      (`concat(parent_deployment, ' -> ', deployment)` and
      `array_to_string(array_slice(group_uniq_array(deployment), 1, 6), ', ')`), plus
      `POST /v1/queries/execute` for the DSL spelling of each

## 8. Documentation and verification

- [x] 8.1 No configuration property is added, so `docs/configuration.md` needs no change — confirm this
      explicitly rather than silently skipping it
- [x] 8.2 Update `docs/sql-reference.md`: rows for `concat`, `to_string`, `array_to_string` and
      `array_slice` in the scalar table; the **array-operand functions** sentence and the two type-rules
      table rows widened to the seven-function set; the computed-arrays row to include `array_slice`; a
      note that `||` and `CAST(x AS VARCHAR)` are accepted inbound spellings of `concat`/`to_string`; and
      the `ORDER BY` section corrected — a computed sort key the projection does not carry is now nested
      rather than refused
- [x] 8.3 Update `docs/claude-code-telemetry.md` where it names the array-operand functions, so the
      `map_keys` guidance mentions rendering the keys with `array_to_string`
- [x] 8.4 Confirm the delta specs in `openspec/changes/add-text-functions-and-fix-derived-sort/specs/`
      match the shipped behaviour, amending them if implementation or the runtime measurements changed a
      decision
- [x] 8.5 `./gradlew clean build` passes (compile + checkstyle + tests)
