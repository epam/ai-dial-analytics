## 1. The lambda expression kind in the DSL

- [x] 1.1 Add `LambdaExpr(List<String> params, Expr body)` to `service/query/model/`, register it in
      `Expr`'s `@JsonSubTypes` under the name `lambda`, and add it to the sealed `permits` list
- [x] 1.2 Validate a lambda at binding: exactly one parameter, each name matching
      `[a-z_][a-z0-9_]{0,63}`, rejecting anything else as HTTP 400 `bad_request` — the name is
      rendered as a bare identifier, so this check is load-bearing, not cosmetic
- [x] 1.3 In `ExprTranslator`, translate a lambda body against the statement's bindings **extended**
      with one synthetic `QueryFieldBinding` per parameter (`LambdaScope`: jOOQ field = the bare
      parameter name, `type` = the array operand's `elementType`), held under a prefixed key no column
      name can collide with. **Extended, not replaced** — the plan said replace, which was the
      no-capture design 2.7 superseded; a body resolves its parameter and the enclosing row's columns,
      as it does in ClickHouse
- [x] 1.4 Render the lambda as `<param> -> <body>` in the emitted ClickHouse SQL, with the parameter
      unquoted (ClickHouse rejects a quoted identifier there)
- [x] 1.5 Reject a `LambdaExpr` in every position other than a higher-order function's argument 0 —
      `toField`'s switch is the single place that sees one out of context
- [x] 1.6 Extend every expression walker that switches on `Expr` for the two new kinds:
      `RelToStructuredQueryTranslator.collectFieldNames`/`containsWindow`/`containsArrayExpansion`,
      `ExprTranslator.collectObjectPaths`, `ExprFieldType`, `SavedQueryBodyValidator`,
      `StructuredQueryBuilder.collectExpr`, `ExprColumnCollector` and the DSL→SQL
      renderer. Each descends into a lambda **body** — a captured column is a column the statement
      reads — and ignores a `lambda_param`, which is why the parameter has a node kind of its own.
      The last two were missed on the first pass (review round 1): `collectExpr` feeds
      `referencedFields`, so a capture-only enrichment reference emitted no join and the statement named
      a table nothing joined, and `ExprColumnCollector` feeds an enrichment declaration's entitlement
      check and scan projection. `NonDeterministicFunctions.findIn` was missed too (review round 2):
      it is the walk both pipeline determinism gates key on, so a lambda body hid `now()` from
      them

## 2. The higher-order array functions

- [x] 2.1 Carry the lambda through validation. **Shipped differently from the plan, because 2.7
      landed**: instead of binding the element type into Calcite's `SqlLambdaScope` (which forbids
      capture), `SqlLambdaRewriter` inlines the parameter as the internal `__lambdaParam(<array>,
      '<name>')` marker before validation, typed as the array's component type, and appends the
      parameter's name to the call as a third operand
- [x] 2.2 Register `arrayExists`, `arrayAll`, `arrayCount` (boolean/integer returns) and
      `arrayFilter`, `arrayMap` (array returns, `ARG1`-typed) in `SqlQueryOperators.TABLE` with that
      checker, ahead of the standard table
- [x] 2.3 Rebuild the lambda in `RexToExprTranslator`: the marker becomes a `LambdaParamExpr`, the
      call's third operand names the parameter, and the five call names map onto their catalog names.
      No `RexLambda` is involved — the rewrite means the plan never contains one
- [x] 2.4 Add the five translator branches in `ExprTranslator`, rendering the ClickHouse call with the
      lambda first; empty/null array answers `false` / `true` / `0` / empty array per the spec table
- [x] 2.5 Render the five back to SQL in `StructuredQueryToSqlRenderer`, lambda first, so
      `/v1/queries/translate` round-trips
- [x] 2.6 Add `arrayFilter` and `arrayMap` to `ArrayValuedFunctions.NAMES` (their results are arrays,
      hence projection-only) and generalize `acceptsArrayOperand(name, index)` from the hardcoded
      `index == 0` to a per-function array-operand position, with `SqlProjectionOnlyTypes`
      continuing to delegate to it
- [x] 2.7 **Investigate lifting the capture restriction** (design.md, Decision 9), before the
      restriction is written into the spec as permanent. Try the pre-validation rewrite first:
      replace each parameter reference in the lambda body with a synthetic marker call over the
      array operand, so the body validates in the *enclosing* scope with the parameter typed as the
      array's component type, and map the marker back to the parameter in `RexToExprTranslator`.
      The investigation is complete only when each of these has an answer backed by a run, not an
      argument: (a) a captured column resolves and renders; (b) nesting a lambda inside a lambda
      keeps the two parameters distinct; (c) an aggregate or window call inside a body is still
      rejected, which the enclosing scope would otherwise admit; (d) a captured column is subject to
      the sensitive-column and access rules exactly as a projected column is; (e) the DSL surface
      accepts and refuses the identical set of bodies, so the round-trip holds. (c) and (e) held on
      the SQL surface only until review round 1: `SqlLambdaRewriter.rejectAggregate` had no
      counterpart in `ExprTranslator.higherOrder`, so the DSL surface accepted an aggregate or a
      window in a body and let ClickHouse refuse it. The DSL translator now walks the body and
      raises the same message
- [x] 2.8 Write the outcome of 2.7 into the delta specs — capture supported, or the restriction
      stated as a boundary — so the spec describes what actually ships. If it lands, Decision 3's
      scope *replacement* becomes a scope *extension* and Decision 7's "no access-control surface is
      added" no longer holds: update both, and cover (d) with a test that a sensitive column is
      refused inside a body

## 3. `match`

- [x] 3.1 Register `match(haystack, pattern)` in `SqlQueryOperators`, add the `RexToExprTranslator`
      mapping and the `ExprTranslator` branch rendering ClickHouse's `match`
- [x] 3.2 Cover it inside a lambda body (`arrayExists(x -> match(x, '^coder-'), roles)`) as well as
      over a scalar column

## 4. Array-valued aggregates over an array field

- [x] 4.1 Register `groupUniqArrayArray` and `groupArrayArray` as `SqlAggFunction`s over an `ARRAY`
      operand returning `ARG0`, and add them to `RelToStructuredQueryTranslator.aggExpr` with
      `DISTINCT` refused (as `group_uniq_array` already refuses it)
- [x] 4.2 Add both to `ArrayValuedFunctions.NAMES`, and to `ExprTranslator`'s aggregate path
      (including `toAggregate`, which the prepared-operand callers use), rendering the native
      ClickHouse names
- [x] 4.3 Replace the blanket array rejection in
      `RelToStructuredQueryTranslator.rejectArrayKeyOrOperand(..., "an aggregate function over")`
      with the named carve-out: argument 0 of `groupUniqArrayArray`/`groupArrayArray`, and
      `arg_max`'s `value` — every other aggregate keeps the current rejection
- [x] 4.4 Make `group_uniq_array`'s array-operand rejection name `groupUniqArrayArray` as the
      flattening alternative, on both surfaces
- [x] 4.5 Type an `arg_max` over an array operand as an array in the derived result schema, and keep
      that result projection-only
- [x] 4.5a Answer the same element kind when such a call is **consumed**, not only when it is
      projected: `RelToStructuredQueryTranslator.elementKind` (what `array_join` and `array_slice`
      delegate to) gained the flattening-aggregate, `arrayFilter` and `arrayMap` arms that
      `ExprFieldType.ofElement` already had, so the two surfaces type the same statement alike
      (review round 2; the fallback was `STRING`, right only for an `Array(String)` operand)
- [x] 4.6 Verified for JSON by the integration test, which reads back an `arrayFilter` result, an
      `arg_max` array and a `groupUniqArrayArray` result as JSON arrays. Flight was **not** exercised:
      these columns reach the encoder as the same `ResultColumnKind.ARRAY` + element kind that
      `group_uniq_array` already ships, so no encoder path is new — stated rather than measured

## 5. Catalog and discovery

- [x] 5.1 Add the eight new functions to `QueryFunctionCatalog` with their groups, signatures, return
      types, `distinct_supported`, `deterministic` and `args` — the lambda argument carrying the new
      `lambda` kind
- [x] 5.2 Add the `lambda` value to the argument-kind enum on the wire DTO
      (`QueryFunctionArgKind`)
- [x] 5.3 Put the cost note in each description: a higher-order call reads every element and cannot
      use a data-skipping index; each array-valued aggregate holds an accumulator per group
- [x] 5.4 Keep `QueryFunctionCatalogParityTest` green — every advertised descriptor must be accepted
      by the translator, which for a lambda argument means the parity test has to build one

## 6. Pipeline measure boundary

- [x] 6.1 Refuse `groupUniqArrayArray`/`groupArrayArray` as a measure `fn` in
      `AggregateSpecValidator` with HTTP 422 naming the function and stating that a measure's input
      column must be a scalar — placed with `requireSingleColumnArity`, which refuses `arg_max` for
      the sibling reason
- [x] 6.2 Test that the refusal message is the new one, not the array-measure rule's "a set of arrays
      is not a declarable column type"

## 7. Tests

- [x] 7.1 `SqlQueryFrontEndTest`: the lambda scenarios from the `sql-query-translation` delta —
      prefix test in `WHERE` and in a projection, nesting, non-array operand, non-boolean body,
      lambda outside a higher-order call, a body naming a column, and the array-at-argument-0 refusal
- [x] 7.2 A permanent test that a lambda statement parses, validates and lowers, so a Calcite upgrade
      that changes `getLambdaScope` or the mutability of its parameter map fails the build here with
      a message pointing at the cause rather than at a user's query
- [x] 7.3 `StructuredQueryToSqlRendererTest`: round-trip of every new function, including a lambda
      body — the rendered statement must re-translate to the same document
- [x] 7.4 DSL-surface tests: the `structured-query` delta's scenarios, including the parameter-name
      rejection and the one-parameter rule
- [x] 7.5 A ClickHouse integration test (`AbstractClickHouseIntegrationTest`) executing the
      motivating statement end to end — `HAVING arrayExists(r -> starts_with(r, 'coder-'),
      groupUniqArrayArray(roles))` with `sum` over the unfiltered group — asserting the aggregate is
      **not** row-filtered, which is the whole point of the change and the one thing an offline test
      cannot show
- [x] 7.6 An integration test that `arrayExists` over a `string` array and `match` inside a lambda
      return what ClickHouse returns for the same predicate, with the same data
- [x] 7.7 **Not applicable as written.** The rough edge it was to pin — Calcite deriving a lambda
      body's type twice, once with the parameter unbound — belonged to the superseded design. The
      shipped rewrite types a body once, in the enclosing scope, so a parameter compared against an
      incompatible literal now coerces exactly as a column would

## 8. Documentation

- [x] 8.1 No configuration property is added, so `docs/configuration.md` is **not** touched (recorded
      here so the omission reads as a decision)
- [x] 8.2 Confirm the delta specs in `openspec/changes/add-clickhouse-array-functions/specs/` match
      the shipped behaviour before the change is reported complete, per CLAUDE.md
- [x] 8.3 Update `docs/sql-reference.md` — the user-facing SQL reference enumerates every catalog
      function and every array rule, so this change made it state things the code contradicts. Missed
      on the first pass and caught in review round 3. It gains a **Lambdas** subsection under
      *Expressions*, `match` and the five higher-order calls in the scalar table, the two flattening
      aggregates in the aggregate table (with the post-aggregation `HAVING` example and the
      accumulator cost note), an array-position **table** replacing the "argument 0" prose — the
      position is now per function — and the same correction in the `array` and computed-array rows of
      *Rules by column type*, plus the lambda entries under *Not supported*

## 9. Verification

- [x] 9.1 `./gradlew clean build` passes (compile + checkstyle + tests)
