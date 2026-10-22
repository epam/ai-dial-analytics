## 1. Settle the shape before writing catalog or translator code

The parser spike that used to gate this section has been run and is recorded in design Decision 5;
both remaining items are decisions already written into the delta specs, restated here so the code
matches them.

- [x] 1.1 **Spellings are settled: `if`, `contains`, `equals`, `not_empty`, `starts_with`,
      `json_extract_string_ci`, `json_extract_array`.** All seven parse as ordinary function calls in
      the Babel parser (`IF`/`CONTAINS`/`EQUALS` are keywords but non-reserved); `iff`/`str_contains`
      are dropped. Each spelling is the DSL wire name and the SQL name, unchanged. The assertion that
      protects this lands permanently in 7.3 — do not re-run it as a throwaway.
- [x] 1.2 `if`'s branches **widen numerically** (`integer`/`long`/`decimal` mutually acceptable,
      result the widest) and mismatch otherwise is rejected; the catalog descriptor's `returns` is
      `SAME_AS_ARGUMENT`. Already stated in the `structured-query` delta — implement to it.
- [x] 1.3 `starts_with`/`contains` are **case-sensitive**; `json_extract_array` returns the **empty
      array** for an absent, unparseable or non-array member. Already stated in the delta.

## 2. DSL translator and catalog

- [x] 2.1 `translate.ExprTranslator.toFunction`: add the seven branches, each **NULL-safe by
      construction** (design Decision 9 — ClickHouse gives none of these for free):
      `if` → ClickHouse `if(<condition>, then, else)` (shipped as a hand-built template rather than
      the `DSL.when(...).otherwise(...)` this item first named — the two render the same semantics and
      the template is the form the null-condition behaviour was measured against);
      `not_empty` → `notEmpty(ifNull(<operand>, ''))`;
      `starts_with` → `ifNull(startsWith(a, b), false)`;
      `contains` → `ifNull(position(<haystack>, <needle>) > 0, false)`;
      `equals` → `ifNull(a = b, false)`.
      A bare `startsWith`/`position`/`=` on a nullable operand yields NULL, which the spec forbids —
      the wrap is the requirement, not a defensive extra.
- [x] 2.2 `ExprTranslator`: enforce `if`'s two type rules — condition must be boolean; branches must be
      the same type or both numeric. Reject with the message shape the existing branches use, naming
      `not_empty` as the expression-position form of a comparison for the non-boolean condition case.
      **Superseded in part by 8.2:** this item said to read the types off the jOOQ `Field`s the
      translator already holds, and that turned out to be unsound — `JooqTypes` is a lossy image of the
      DSL's own types, so the rule unified a timestamp with a long, unified an object with an array, and
      refused a timestamp column against a timestamp literal. The types are now read from
      `translate.ExprFieldType` instead. Every new rendering still declares its Java type, which is what
      keeps a projected boolean serializing as `true`/`false`.
- [x] 2.3 `ExprTranslator`: `json_extract_string_ci` renders as
      `arrayFirst(kv -> kv.1 = <key lowered at translation time>, arrayMap(kv -> (lower(kv.1), kv.2), JSONExtractKeysAndValues(<json>, 'String'))).2`.
      Lower the key literal in Java, not per row. Enforce arity exactly two — no variadic path.
- [x] 2.4 `ExprTranslator`: `json_extract_array` renders as
      `JSONExtract(<json>, <path…>, 'Array(String)')`, returning the empty array for an absent,
      unparseable, or non-array member.
- [x] 2.5 **Both new extractors need a distinct `object`-field rendering** (design Decision 7 — this
      is the item most likely to be skipped and the only one that fails *silently*). Route an
      `object` first argument through `toJSONString(<column>)` and then the same text rendering as
      2.3/2.4. Do **not** reuse `subcolumnExtract`, and do **not** pass the column itself: measured on
      ClickHouse 26.3, `JSONExtractKeysAndValues(<JSON column>, 'String')` returns `[]` **with no
      error** — `json_extract_string_ci` over `request_tags` would return the empty string for every
      row — and `JSONExtract(<JSON column>, 'k', 'Array(String)')` fails with
      `ILLEGAL_TYPE_OF_ARGUMENT`. Reuse `objectFieldArgument` to detect the case; record in a comment
      why these two diverge from the other four.
- [x] 2.6 `QueryFunctionCatalog`: add the seven descriptors in **alphabetical** position
      (`contains`, `equals`, `if`, `json_extract_array`, `json_extract_string_ci`, `not_empty`,
      `starts_with`). `json_extract_string_ci`'s `key` arg is a `STRING_LITERAL` that is **not**
      variadic — unlike every other `json_extract_*` entry. `json_extract_array` returns `ARRAY`.
      The four predicate producers return `BOOLEAN`; `if` returns `SAME_AS_ARGUMENT`.
- [x] 2.7 Verify `QueryFunctionCatalogParityTest` passes unchanged — it asserts every descriptor is
      accepted by the translator. If a new descriptor needs a bespoke assertion (the non-variadic
      `json_extract_string_ci` key is the likely one), add it there rather than weakening the generic
      test.
- [x] 2.8 Generalize `ArrayValuedAggregate` from one name to the **set** of array-returning catalog
      functions (design Decision 11), so `rejectAsOperand` covers `json_extract_array` at all three
      call sites (`ExprTranslator`, `FilterTranslator`, `StructuredQueryToSqlRenderer`) without a
      parallel guard. Rename the class if the name no longer fits what it classifies; its javadoc
      currently says "the one function of the closed catalog whose result is an array" and must stop
      saying so. The rejection message names the offending function, not `group_uniq_array`.

## 3. SQL surface

- [x] 3.1 `SqlQueryOperators`: register seven `SqlBasicFunction`s with the spellings settled in 1.1,
      in the `TABLE` chain **ahead of** Calcite's standard operator table, and extend the class
      javadoc's bullet list. Add a comment recording why the ordering matters here specifically:
      shadowing Calcite's own definitions and, for any standard name, its convertlet (design
      Decision 4). `IF`'s signature carries a boolean first operand and a least-restrictive return
      type over the two branches, so a non-boolean condition and non-unifying branches both fail in
      validation (design Decision 10); the four predicate producers return `BOOLEAN_NULLABLE`;
      `JSON_EXTRACT_STRING_CI` is fixed-arity 2 and `JSON_EXTRACT_ARRAY` is variadic like the other
      extractors, both taking `CHARACTER` operands (an `object` column types as `VARCHAR` in the
      Calcite schema, so the carve-out needs no operand-type change).
- [x] 3.2 `RexToExprTranslator.callExpr`: add the seven cases. `JSON_EXTRACT_STRING_CI` and
      `JSON_EXTRACT_ARRAY` go through the `jsonExtract` helper so the literal-path rule and the
      `rejectUnextractableJsonOperand` check are shared; `JSON_EXTRACT_STRING_CI` must reject a third
      argument for arity.
- [x] 3.3 `RelToStructuredQueryTranslator`: the four predicate producers map to `ResultColumnKind.BOOL`
      and `json_extract_string_ci` to `STRING` in `fnKind`; `if` maps to its branches' kind under the
      widening rule from 1.2. **`json_extract_array` does not go in `fnKind`** — follow
      `group_uniq_array`: type it in `resultColumn()` as `ResultColumnKind.ARRAY` with an element kind
      of `STRING`, and **throw** in `fnKind`. `fnKind` returns a bare kind that cannot carry an element
      kind, and reaching it means the call was nested inside another expression, which is exactly the
      use being refused.
- [x] 3.4 Add `json_extract_string_ci` and `json_extract_array` to
      `SqlProjectionOnlyTypes.JSON_EXTRACT_FUNCTIONS`. That set is the **single** source of the
      object-field carve-out for both directions — the SQL→DSL translators and the DSL→SQL renderer
      both consult it — so this is a one-line edit, not a hunt. Re-read its javadoc: the sentence
      describing the carve-out's result as "an ordinary scalar carrying no restriction at all" stops
      being true once `json_extract_array` joins the set, and must say so.
- [x] 3.5 Apply the array restriction to `json_extract_array`'s **result** (projection item only) via
      the generalized classifier from 2.8. Confirm `json_extract_string_ci`'s scalar result carries no
      such restriction.
- [x] 3.6 `StructuredQueryToSqlRenderer`: confirm the generic function rendering already emits all
      seven verbatim. If `if`'s condition or the array call needs special handling, add it here — a
      divergence between the catalog name and the rendered name breaks the round-trip.

## 4. Class-1 array outputs

- [x] 4.1 `ExprSqlValidator.toFieldType`: return `QueryFieldType.ARRAY` for `ResultColumnKind.ARRAY`
      instead of throwing. Keep the `OBJECT` rejection, and rewrite the javadoc paragraph that
      currently explains why both non-scalar families are rejected.
- [x] 4.2 `EnrichmentTypeMapping.isCompatible` needs **no logic change** — it is already
      `varType == columnType || (both NUMERIC)`, so `ARRAY` against an array column passes and `ARRAY`
      against a scalar column fails. Add the javadoc line stating that element types are deliberately
      not compared (design Decision 8), so the omission does not read as an oversight.
- [x] 4.3 `EnrichmentTypeMapping.resolve` parses the **evaluator variable's** declared `type` token
      (the bound target column's type is already a `QueryFieldType` and needs no parsing). `array`
      resolves today; `array<string>` does not. Decide whether an author may write the parameterized
      token and either add the alias or leave it rejected with a message that names `array` — do not
      leave it as an unhandled `IllegalArgumentException` path.
- [x] 4.4 Verify the materialize `INSERT … SELECT` path needs no change for an array column
      (`MaterializeService` builds the projection through `ExprTranslator.toField`, so an array field
      flows like any other), and that the derivation still runs server-side — the credential split and
      the no-payload-on-the-wire invariant must hold.

## 5. Ingest transform

- [x] 5.1 `TransformRegistry`: add `lowercase-json-keys`, lower-casing **top-level member names only**,
      leaving values byte-for-byte and nested objects untouched. Last-write-wins on a key collision,
      documented as unspecified in the spec. The value it receives for an `object` column is the
      Jackson `JsonNode` (`MappingEngine.coerce` returns the node itself), so return a `JsonNode`;
      pass through `null` and any non-object node unchanged.
- [x] 5.2 `mapping/dial-usage-log.yaml`: apply the transform to `request_tags`. Leave `jwt_claims`
      alone — claim names are producer-stable, and the change is scoped to header maps.
- [x] 5.3 Confirm `SchemaValidator` accepts a transform on an `object` column: `expectedFamily` only
      special-cases `iso-to-ch-datetime` and otherwise maps `OBJECT` → `JSON`, so no change is
      expected — verify rather than assume, since every existing transform is string/array-shaped.

## 6. Specs

- [x] 6.1 Apply the `structured-query` delta: seven catalog table rows, the conditional/predicate,
      case-insensitive-extraction and array-extraction paragraphs, the `object`-argument paragraph for
      the two new extractors, and their scenarios. The delta restates the cross-server-version
      exemption paragraph from the ClickHouse 26 change — keep it; a MODIFIED requirement replaces the
      shipped text wholesale, so dropping it would silently revert that change.
- [x] 6.2 Apply the `query-schema-discovery` delta: the enumerated function-name scenario and the three
      new discovery scenarios.
- [x] 6.3 Apply the `sql-query-translation` delta: the function list, the operator-ordering paragraph,
      the widened object-field exception, the boolean-call-is-not-a-predicate rule, and the new
      scenarios.
- [x] 6.4 Apply the `query-translation-endpoint` delta: the widened render list and the three
      round-trip scenarios.
- [x] 6.5 Apply the `class1-enrichment` delta: the array-output rule in condition 3, the corrected
      type-incompatibility scenario, and the five new scenarios.
- [x] 6.6 Apply the `data-sync` delta: `lowercase-json-keys` in the closed transform registry, the
      cutover paragraph, and the three new scenarios.
- [x] 6.7 `openspec validate add-derivation-expression-functions --strict` passes.

## 7. Tests and verification

- [x] 7.1 `QuerySchemaControllerTest`: 21 → 28 functions, add the seven names to the expected list, and
      fix the positional index assertions — seven insertions shift most of them
      (`arg_max` stays at 1; `count` 3 → 4, `group_uniq_array` 6 → 8, `max` 13 → 18, `percentile_cont`
      15 → 21, `sum` 17 → 24 — recompute rather than trusting these).
- [x] 7.2 `ExprTranslator` unit tests: `not_empty` false for null **and** for empty (the assertion that
      protects design Decision 3); `starts_with`/`contains`/`equals` false — not null — for a null
      operand (design Decision 9); `if` selecting each branch; a nested `if`; a non-boolean condition
      rejected; mismatched branch types rejected and numeric branches accepted per 1.2.
- [x] 7.3 Front-end tests: each of the seven spellings parses and lowers to the expected `FnExpr`; a
      bare comparison in a projection is still rejected; a bare boolean call as a `WHERE` condition is
      rejected; `coalesce`/`nullif` are still rejected as uncatalogued; `json_extract_string_ci` with
      three arguments is rejected for arity. Include the **permanent** assertion from design
      Decision 5 that `IF`, `CONTAINS` and `EQUALS` are non-reserved in the Babel grammar, so a Calcite
      upgrade that reserved one fails here rather than in every rendered statement.
- [x] 7.4 Renderer and round-trip tests: each of the seven renders with its catalog name, and the
      rendered text re-parses to the identical DSL document.
- [x] 7.5 Object-field tests: `json_extract_string_ci` and `json_extract_array` accepted over an
      `object` column on both the execute and translate surfaces; `json_extract_array`'s result
      rejected as another function's operand and as a filter comparand on the DSL surface, and as a
      sort or group key on the SQL surfaces (the DSL surface does not restrict those positions today
      and this change does not widen it — see the `group_uniq_array` precedent).
- [x] 7.6 `ExprSqlValidator` tests: an array expression bound to an array column accepted, to a scalar
      column rejected, an object expression still rejected, and a conditional expression accepted.
- [x] 7.7 `TransformRegistry` test: mixed-case keys lowered, values untouched (including a mixed-case
      e-mail and user agent), nested objects untouched, a null and a non-object node passed through.
- [x] 7.8 Extend `FlightSqlIntegrationTest.provisionalRenderingsExecuteOnRealClickHouse` with the seven
      functions against live ClickHouse, **including both new extractors over an `object` column**.
      This is the only place the ClickHouse renderings are actually executed, and it is the only thing
      that would catch a regression to the broken `object` rendering, which returns the empty string
      rather than failing (design Decision 7).
- [x] 7.9 **Cost check for `json_extract_string_ci`** (design "Still Open"): `EXPLAIN` a multi-column
      `SELECT` that calls it several times over `request_tags` on real ClickHouse and confirm whether
      common-subexpression elimination folds the repeated full-document read. Record the finding in
      `design.md`. If CSE does not fire, say so plainly rather than shipping the function with an
      unmeasured cost claim.
- [x] 7.10 `./gradlew clean build` is green (`TESTCONTAINERS_RYUK_DISABLED=true` if the reaper cannot
      start in this Docker environment).
- [ ] 7.11 **Smoke against a running instance — NOT RUN, left for the operator.** Every clause of it now
      has an automated equivalent, so this is a confirmation against real data rather than a gate:
      the function list is `QuerySchemaControllerTest`; execute/`execute-sql` agreement and the
      `/translate` round trip are `SqlQueryFrontEndTest` + `StructuredQueryToSqlRendererTest`; the
      evaluator → rule → `materialize` path with an `if` and a `json_extract_array` expression is
      `Class1MaterializeIntegrationTest.materializeWritesConditionalAndArrayClaim` against real
      PostgreSQL + ClickHouse; the object-column renderings execute live in
      `FlightSqlIntegrationTest.derivationExpressionFunctionsExecuteOnRealClickHouse`.
      What only a real instance can show is `json_extract_string_ci(request_tags, '<header key>')`
      returning values over the **mixed-case ingest history** — the fixtures seed both spellings on
      purpose, but not the real distribution. Run it against a running deployment before relying on the reading.
      (Not run here because `bootRun` needs local Postgres/ClickHouse env wiring, and a wrong variable
      name silently points the service at a shared database.)

## 8. Review follow-ups

Found by reviewing the implemented change against its own delta specs. Each item is a place the code and
the spec disagreed, or where a test passed for a reason unrelated to what it claimed to hold.

- [x] 8.1 **`lowercase-json-keys` was a no-op at runtime.** `TransformRegistry` imported Jackson 2's
      `ObjectNode` while `MappingEngine` hands an {@code object} column's value over as a Jackson 3
      (`tools.jackson`) node — both generations are on the classpath and the two types share no
      hierarchy, so the `instanceof` never matched and every document was returned unchanged. The
      registry unit test passed because it built its fixtures with the Jackson 2 `ObjectMapper` too.
      Repointed the registry and the test at `tools.jackson`, recorded the trap in the transform's
      javadoc, and added `MappingEngineTest.objectKindLowerCasesHeaderKeysAndLeavesValuesAlone` —
      the level the mismatch is visible at, verified to fail against the Jackson 2 import.
- [x] 8.2 **`if`'s branch rule read jOOQ Java types, which are a lossy image of the DSL's.**
      `JooqTypes` collapses `long`/`timestamp` onto `BIGINT` and `object`/`array`/`map` onto `OTHER`, and
      a timestamp literal renders through `fromUnixTimestamp64Milli` carrying neither — so the rule
      unified a timestamp with a long (ClickHouse has no common type: a server error where the spec
      promises 400), unified an object with an array, and *refused* a timestamp column against a
      timestamp literal. Added `translate.ExprFieldType` (an `Expr` → `QueryFieldType` resolver over the
      bindings and the catalog's declared `returns`, with `same_as_argument` following the argument it
      names) and moved the condition check, the branch check and `equals`'s comparability check onto it.
      A null-literal branch and a non-scalar branch are now refused on their own terms, each naming its
      own fix.
- [x] 8.3 **The DSL surface did not enforce the operand types the SQL surface declares.**
      `SqlQueryOperators` gives `not_empty`/`starts_with`/`contains` a `CHARACTER` operand family, so
      `not_empty(total_tokens)` was a translation-time 400 on `/execute-sql` and a ClickHouse error on
      `/execute`. Added the text-operand check (`string`/`uuid`, which is what `CHARACTER` admits on the
      SQL side) and, for `equals`, the unification check — ClickHouse raises on a pair it has no common
      type for rather than returning false. `equals(x, <null literal>)` was accepted and rendered
      `x = NULL`, i.e. false for every row; now refused, naming `filter`'s `eq`/`ne`.
      `QueryFunctionCatalogParityTest`'s typed-filler map carries the new entries rather than the
      generic case being weakened.
- [x] 8.4 **`fnKind` typed an `if` from its `then` branch only.** Calcite derives, and ClickHouse
      returns, the widest of the two, so `if(c, int_col, decimal_col)` was advertised as an integer column
      while delivering decimals. `conditionalKind` now widens over the same numeric order the DSL surface
      uses (`FLOAT64` ahead of `DECIMAL`, which is the direction both Calcite and ClickHouse take that
      pair).
- [x] 8.5 **The `array<string>` rejection message never reached the caller.**
      `EnrichmentTypeMapping.resolve` raised an actionable message naming `array` as the token to write,
      and both `RuleGovernanceValidator` call sites caught `IllegalArgumentException` and replaced it with
      "unrecognized type". The new test asserted against the mapping class directly, so it never noticed.
      Both sites now carry the mapping's message through, asserted through the rule-registration surface
      in `Class1MaterializeIntegrationTest`.
- [x] 8.6 **Two contracts the specs state had no test.** The `ifNull(…, false)` wraps were asserted only
      as rendered text — every fixture column is non-nullable, so absence arrives as the empty string and
      the wraps' null path never executed. Added
      `FlightSqlIntegrationTest.predicateProducersAreFalseNotNullOverANullOperandOnRealClickHouse` over
      `parent_deployment` (nullable, never set by the fixture), with a guard asserting the column really
      is NULL. And `if`'s "a null condition takes the else branch" is a claim about ClickHouse that no DSL
      expression can currently produce — the predicate producers and `json_extract_bool` are all non-null
      by construction and no boolean column is nullable — so it is asserted against the server directly in
      `clickHouseConditionalTakesTheElseBranchForANullCondition` rather than left in a design note.
- [x] 8.7 Spec text the widening left stale: `query-schema-discovery`'s variadic-path scenario claimed
      *every* `json_extract_*` entry marks `key` variadic and returns one of four scalar types (false for
      both new members); `sql-query-translation` said the object carve-out never restricts "the
      extraction's result" a paragraph before carving out `JSON_EXTRACT_ARRAY`; `class1-enrichment` did
      not state the `array<string>` rejection at all. The new type rules from 8.2/8.3 are stated in the
      `structured-query` delta with their scenarios.
- [x] 8.8 Hygiene: dropped the dead `ArrayValuedFunctions.is(Expr)`; corrected the two javadocs still
      calling `group_uniq_array` the *one* non-scalar catalog function; noted why `documentSource`'s guard
      is unreachable rather than leaving it looking like live logic; replaced
      `DerivationExpressionTranslatorTest`'s private name holder with the production constant; corrected
      `conditional`'s javadoc, which described a jOOQ `CASE WHEN` while the code emits ClickHouse `if`.
- [x] 8.9 **`openspec/changes/archive/2026-08-25-upgrade-clickhouse-26/specs/structured-query/spec.md`
      deleted.** This change's commit had added it, restoring a delta the ClickHouse 26 change's archive
      was missing (its proposal lists the `structured-query` modification); it was byte-identical to the
      then-shipped requirement text, so it was a faithful restoration rather than a reconstruction. Deleted
      rather than split into its own commit: it is unrelated to this change, and the archive returns to
      its prior state. Nothing is lost that
      `openspec/specs/structured-query/spec.md` does not hold — the consequence, recorded here rather than
      rediscovered, is that the ClickHouse 26 archive still carries no copy of the `structured-query`
      requirement it modified.
