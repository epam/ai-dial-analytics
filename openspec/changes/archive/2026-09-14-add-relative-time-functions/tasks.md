## 1. Catalog and discovery

- [x] 1.1 Add a `deterministic` member to `QueryFunctionDto` (always serialized) and set it on every
      descriptor in `QueryFunctionCatalog` — `true` everywhere except `now`.
- [x] 1.2 Add the three descriptors to `QueryFunctionCatalog` in alphabetical position: `date_add`,
      `date_sub` (scalar, `returns` `timestamp`, `unit` a `string_literal` constrained to the six
      accepted units, `amount` an `integer_literal` with `min` 1, `timestamp` an `expression`) and
      `now` (scalar, `returns` `timestamp`, no args), with descriptions that state UTC, millisecond
      precision, the data store's clock, and — for `now` — that it is refused in a pipeline
      declaration.
- [x] 1.3 Expose the non-deterministic name set from `QueryFunctionCatalog`, **derived from the
      descriptors** rather than written out separately, and declare the accepted offset units
      (`DATE_OFFSET_ALLOWED_UNITS`) next to the existing `DATE_BIN_ALLOWED_UNITS` /
      `DATE_TRUNC_ALLOWED_UNITS`.
- [x] 1.4 Extend `QueryFunctionCatalogParityTest` so the advertised offset units equal the
      translator's accepted set (as it already does for `date_bin`), and so exactly one descriptor
      declares itself non-deterministic.

## 2. DSL translation and rendering

- [x] 2.1 `ExprTranslator`: add the `now` branch rendering `now64(3, 'UTC')`, rejecting any argument.
- [x] 2.2 `ExprTranslator`: add the `date_add` / `date_sub` branches — validate arity, the string
      literal `unit` against the six-unit map, and the positive integer literal `amount`; render
      `<operand> + INTERVAL <n> <UNIT>` / `- INTERVAL <n> <UNIT>` with the unit as an allowlisted
      keyword and the amount inlined as a validated integer (no injection surface), parenthesised so
      it nests safely.
- [x] 2.3 `ExprFieldType`: type all three calls from their descriptors (`timestamp`) — confirm the
      existing `ofFunction` path already reads `returns` and needs no special case, and add one only
      if it does not.
- [x] 2.4 `StructuredQueryToSqlRenderer`: render `now()` as the second nullary function (beside
      `count`), and render `date_add` / `date_sub` as the infix interval form
      `(<expr> ± INTERVAL <n> <UNIT>)`, validating the unit and amount so the renderer cannot emit a
      statement the front end would reject.
- [x] 2.5 Unit tests in `ExprTranslatorTest` / `StructuredQueryToSqlRendererTest` for the rendered
      SQL of all three, and for each rejection (argument to `now`, bad arity, non-literal unit or
      amount, zero/negative/fractional amount, `week`/`quarter` as a DSL unit).

## 3. SQL surface

- [x] 3.1 `SqlQueryOperators`: register a niladic `NOW` returning `TIMESTAMP`, plus `DATE_ADD` and
      `DATE_SUB` over `(CHARACTER, NUMERIC, TIMESTAMP)`, and chain them into `TABLE`.
- [x] 3.2 `RexToExprTranslator`: map `NOW`, `CURRENT_TIMESTAMP` and `LOCALTIMESTAMP` onto the
      zero-arg `now` `FnExpr`, and `DATE_ADD` / `DATE_SUB` onto the corresponding calls with the
      unit lowercased to the DSL spelling.
- [x] 3.3 `RexToExprTranslator`: in the `+` / `-` branches, recognise an interval **literal** right
      operand ahead of the numeric lowering and produce `date_add` / `date_sub` — reading the unit
      from the literal's `SqlTypeName` (enumerating the six accepted ones, so a compound qualifier
      fails closed) and the magnitude from its `BigDecimal`, requiring an exact non-zero integral
      quotient in that unit, and flipping the operator for a negative magnitude.
- [x] 3.4 `RexToExprTranslator`: reject the qualified datetime difference `(ts1 - ts2) HOUR`, which
      today lowers silently to numeric `minus` and renders a difference in seconds.
- [x] 3.5 Verify a bare interval literal outside those two operators is still rejected (projection,
      comparison, arithmetic), and that the message identifies the interval rather than reading as an
      internal literal-type failure.
- [x] 3.6 Translation tests in `SqlQueryFrontEndTest`: the three clock spellings produce one
      document; quoted and unquoted intervals agree; the function spelling agrees with the operator
      spelling; `+ INTERVAL -2 HOUR` normalizes to `date_sub`; `WEEK`/`QUARTER` normalize to
      `7 day` / `3 month`; interval arithmetic over a column in projection; and every rejection in
      the `sql-query-translation` delta, `CURRENT_DATE` and both timestamp-difference forms included.
- [x] 3.7 A round-trip test both ways — DSL → SQL → DSL for `now()` and for `date_sub` — asserting
      the document is unchanged.
- [x] 3.8 Settle and pin the aggregate-mode question: assert that a projected `now()` in a grouped
      query is treated the same on both surfaces, and make them agree if they do not.

## 4. Determinism gate

- [x] 4.1 `ExprSqlValidator`: after the front end returns, walk the translated `Expr` tree — through
      `FnExpr` args, `CaseExpr` branches and `WindowExpr`; no subquery arm is needed, because the
      front end both validators embed through refuses every composite plan — and raise
      `PipelineValidationException` (422 `pipeline_validation_failed`) naming any function the
      catalog declares non-deterministic. Replace the javadoc paragraph asserting no check is needed.
- [x] 4.2 `FilterSqlValidator`: the same walk over the translated `FilterNode` tree, so a pipeline
      `filter`, a `ready_when.signal`, a `member_select.prefer_sql`, an aggregate `filter` and a
      measure `where` are all covered through their one shared entry point.
- [x] 4.3 Tests: a Class-1 expression calling `now()` and one calling `date_sub('hour', 1, now())`
      are both 422; a pipeline filter and a readiness signal calling `now()` are 422; an aggregate
      `filter` and a measure `where` calling `now()` are 422 at registration — asserted end to end in
      `AggregatePipelineIntegrationTest`, since `AggregateSpecValidatorTest` stubs the validator the
      rule lives in; and a fragment calling `date_trunc`/`date_sub` over a **column** is still
      accepted. Preview needed no case after all: it names a *registered* pipeline, so a refused
      declaration can never reach it — asserted instead by the absent pipeline.

## 5. Saved queries

- [x] 5.1 Confirm no production code change is needed — a relative body validates and stores through
      the existing translation path — and add saved-query tests: a `sql` body with
      `now() - INTERVAL 2 HOUR` and a `query` body with `date_sub('hour', 2, now())` each store,
      return byte-for-byte unchanged, derive `source`, and leave `time` untouched.

## 6. Docs and completion

- [x] 6.1 `docs/sql-reference.md`: replace the "There is no `now()` / `CURRENT_TIMESTAMP`" paragraph
      with the accepted clock spellings and the interval arithmetic (including the `WEEK`/`QUARTER`
      normalization and the rejected forms), and add `now`, `date_add`, `date_sub` to the scalar
      function table. No `docs/configuration.md` change — this change adds no config property.
- [x] 6.2 Re-read the delta specs against the shipped behavior and correct either side where they
      disagree, in particular anything task 3.8 settles.
- [x] 6.3 `./gradlew clean build` passes.
