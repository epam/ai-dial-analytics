## 1. Catalog

- [x] 1.1 Add `arg_max` to `QueryFunctionCatalog` in the `AGGREGATE` group: signature
  `arg_max(value, order)`, two **required** expression args, returns `SAME_AS_ARGUMENT`,
  `distinct_supported = false`. Place it between `abs` and `avg` — the list is globally alphabetical and
  `QueryFunctionCatalogParityTest.catalogIsAlphabeticallyOrderedWithUniqueNames` asserts it.
- [x] 1.2 Write the description so schema discovery surfaces the trap: it returns the `value` of the
  row with the greatest `order`, and ties on `order` are unspecified.
- [x] 1.3 No change to `QueryFunctionCatalogParityTest` itself — it instantiates every descriptor
  generically from its declared arity and arg kinds, so it covers `arg_max` automatically. Confirm it
  passes rather than assuming; a failure there means the descriptor and the translator disagree.

## 2. DSL execution

- [x] 2.1 Add an `arg_max` branch to `ExprTranslator.toFunction` rendering
  `DSL.field("argMax({0}, {1})", Object.class, value, order)`. This is a hand-built SQL template, the
  same mechanism `groupUniqArray` uses and the same call `AggregateRefreshService` already emits — jOOQ
  is not asked to know the function, and the experimental-dialect caveat does not apply (design D0).
- [x] 2.2 Reject the `distinct` quantifier explicitly in that branch. It cannot come from
  `toAggregate` — `arg_max` does not go through it, and `toAggregate`'s own contract already excludes a
  two-operand aggregate — so without an explicit check the flag would be silently ignored.
- [x] 2.3 Reject wrong arity (one argument, three arguments) with a message naming the two-argument
  signature. `requireSingleArg` is not usable here; the branch needs its own check.
- [x] 2.4 Confirm the result is typed nullable, since an all-null `order` group yields null.

## 3. SQL surface — three registration points, each failing alone

- [x] 3.1 Register an `ARG_MAX` `SqlAggFunction` in `SqlQueryOperators` and add it to `TABLE`. Without
  this, Calcite validation fails with "No match found for function signature" and the translator is
  never reached. Model it on `GROUP_UNIQ_ARRAY`:
  `SqlBasicAggFunction.create("ARG_MAX", SqlKind.OTHER_FUNCTION, ReturnTypes.ARG0_FORCE_NULLABLE, OperandTypes.ANY_ANY)`
  — both constants verified present in the pinned Calcite 1.42.0. `ARG0_FORCE_NULLABLE` rather than
  `ARG0_NULLABLE` because the result is nullable even over non-nullable operands' declared types once
  a null `order` or a null winning `value` is possible, which is what the spec requires. Note
  `withDistinct` is not public API, so the quantifier will parse and must be rejected in 3.2.
- [x] 3.2 Add an `ARG_MAX` case to `RelToStructuredQueryTranslator.aggExpr`: exactly two arguments,
  `DISTINCT` rejected with a message saying why, yielding `FnExpr("arg_max", false, [value, order])`.
- [x] 3.3 Add an `arg_max` case to `RelToStructuredQueryTranslator.fnKind` returning
  `kind(fn.args().get(0), fieldTypes)` — **argument 0**, the `value`. The adjacent `min`/`max` case reads
  `args.get(args.size() - 1)`, which is the same thing at arity 1 and returns the `order`'s type here.
- [x] 3.4 Confirm `StructuredQueryToSqlRenderer` needs no change: `arg_max` is not in
  `DISTINCT_AGGREGATES`, so it renders as an ordinary two-argument call. Note the resulting asymmetry —
  translate silently drops a `distinct` flag that execute rejects — and confirm it matches
  `group_uniq_array`'s existing behavior rather than being new (design D2).
- [x] 3.5 Confirm the SQL surface's array/object operand rejection needs no new code:
  `SqlProjectionOnlyTypes` already covers every function. Do **not** add a DSL-side check —
  `min`/`max` have none, and adding one only for `arg_max` is an inconsistency (design D7).

## 4. Pipeline measure path — javadoc, not logic

- [x] 4.1 Confirm no logic change is needed in `AggregateSpecValidator`: `aggregateFunction` resolves
  `arg_max` and `requireSingleColumnArity` rejects it, which is the intended behavior.
- [x] 4.2 Update `requireSingleColumnArity`'s javadoc. It asserts "No aggregate in the catalog currently
  takes two required arguments, so this is a guard on future additions rather than a live restriction" —
  false as of this change. Say instead that `arg_max` is the live case, and keep the note that the guard
  is keyed on arity rather than a name list.

## 5. Tests

- [x] 5.1 **The load-bearing one:** `arg_max` executes against a live ClickHouse through the SQL/Flight
  path, not just renders. Add it to `FlightSqlIntegrationTest` — reusing
  `provisionalRenderingsExecuteOnRealClickHouse` is fine, but if so, note in the test that `arg_max` is
  there for a different reason than the percentiles (a hand-written template plus a three-point Calcite
  registration, not a provisional dialect). A DSL-only test proves nothing about section 3.
- [x] 5.2 Structured-execution test: `arg_max` returns the latest row's value, not the greatest value —
  use a fixture where those differ, or the test proves nothing.
- [x] 5.3 Null tests, both clauses, since they are separate facts about ClickHouse: rows with a null
  `order` are ignored; a group whose every `order` is null yields null; a group whose **winning row has
  a null `value`** yields null.
- [x] 5.4 Tie test: two rows tying on the greatest `order` return one of their values and do not error.
- [x] 5.5 Rejection tests on **both** surfaces: `distinct`/`DISTINCT`, one argument, three arguments —
  each failing before ClickHouse is contacted. The DSL and SQL rejections come from different code, so
  one test per surface.
- [x] 5.6 SQL round-trip test:
  `SELECT response_id, arg_max(rate, request_time) AS final_rate FROM rate_analytics GROUP BY response_id`
  translates, renders back, and re-translates to the identical DSL. Cover the unaliased form too —
  `OutputColumnNaming` derives an `FnExpr`'s name from the lowercase function name regardless of arity,
  so it should be `arg_max`, needing no change but worth asserting — and assert the **result schema
  types `final_rate` as the `rate` operand's type**, which is what catches a `fnKind` reading the wrong
  argument (task 3.3).
- [x] 5.7 Schema-discovery test: `arg_max` is advertised with two required args and
  `distinct_supported: false`.
- [x] 5.8 Pipeline-rejection test: a measure declaring `fn: arg_max` is refused at registration with the
  two-argument-signature message. This path is currently **unreachable** (no two-arg aggregate exists),
  so this change makes dead code live — cover it.
- [x] 5.9 Name new test methods to satisfy Checkstyle: no upper-case second character, no two
  consecutive capitals. This fails `build`, not just the lint task.

## 6. Specs

- [x] 6.1 Confirm the three delta specs match what shipped — especially the catalog table row and the
  null/tie/`distinct` rules. **The null rule was wrong and was corrected:** the draft said a winning row
  with a null `value` yields null. ClickHouse 25.3 skips that row instead and falls through to the
  next-latest one with a value, so the result is the latest *non-null* value. Caught by the live test
  (5.1), then propagated to the structured-query delta, design D3, the catalog description, and the
  translator javadoc.
- [x] 6.2 Confirm the structured-query delta does **not** add `arg_max` to the experimental-dialect
  caveat sentence: its rendering is a directly emitted native function name, like `date_bin`'s (D0).
- [x] 6.3 Confirm the array/object operand sentence is scoped to the SQL surface and claims no DSL-side
  behavior that does not exist (D7).
- [x] 6.4 No `docs/configuration.md` change: no configuration property is added. Confirm rather than
  assume.
- [x] 6.5 Confirm `query-translation-endpoint` needs no delta — true because `arg_max` renders as an
  ordinary call.

## 7. Verification

- [x] 7.1 Run `./gradlew clean build` and confirm it passes. Integration tests need Docker.
- [x] 7.2 Run `openspec validate add-arg-max-aggregate` and confirm valid.

## 8. Post-merge rollout — drop rate_min/rate_max (no code, no spec)

Do this only after section 7 passes. These are catalog declaration changes against a **running
instance**, not repository edits, so nothing here is proven by a green build — and the ordering is the
part that bites.

- [ ] 8.1 Confirm nothing besides the plan doc reads `turn_feedback.rate_min`/`rate_max` or
  `response_ratings.rate_min`/`rate_max`. The turn-feedback plan doc does — its
  rating-distribution query groups by `(rate_min, rate_max)` — so that query is rewritten as part of
  this step, grouping on `rate_distinct_count` plus the three sign counts instead.
- [ ] 8.2 Disable `response_ratings_rollup` **before** touching any schema: a target column no measure
  writes fails every refresh *and* every preview.
- [ ] 8.3 Re-declare the pipeline without the `rate_min`/`rate_max` measures (17 measures), then drop
  those two columns from `response_ratings`. Re-enable and let it catch up — note the re-declaration resets the
  cursor, which is cheap at the small row count involved.
- [ ] 8.4 Patch `turn_feedback` to drop its `rate_min`/`rate_max` columns, register `feedback-rollup`
  **v2** without those two output vars, and update the rule's `output_bindings` to match (15 bindings).
- [ ] 8.5 Verify the feed still returns rated turns, and that the final rating is obtainable per page.
  Read `arg_max(rate, request_time)` for coverage and, if `preserve-raw-rate-value` has shipped,
  `arg_max(rate_raw, request_time)` alongside it for the unambiguous form — the latter is null for any
  response whose latest event predates that column. Run it on `/v1/queries/execute`, not `execute-sql`: Calcite's default
  `inSubQueryThreshold` of 20 turns a 50-id `IN` list into a sub-query join the translator refuses.
- [ ] 8.6 Update the turn-feedback plan doc — several of its sections name these columns, and its
  rating-distribution query is the one that has to change rather than merely lose two names.
