## 1. Catalog and type surface

- [x] 1.1 Add `ELEMENT_OF_ARGUMENT("element_of_argument")` to `web/dto/query/QueryFunctionReturnType`, with javadoc saying it is the inverse of `ARRAY` (the element type of argument 0) and why neither existing token can name it (design D3)
- [x] 1.2 Add the `array_join` descriptor to `QueryFunctionCatalog` in alphabetical position (after `array_has_any`, before `array_length`): group `scalar`, signature `array_join(array)`, returns `element_of_argument`, `distinct_supported` false, exactly one required expression argument. The description must state that the call produces one row per element and therefore changes the row count, and that an empty array yields no rows
- [x] 1.3 Add `"array_join"` to `ArrayValuedFunctions.ARRAY_OPERAND_FUNCTIONS`, and update that class's javadoc plus `SqlProjectionOnlyTypes`'s to say "array-operand functions" where they now say "array accessors" (design D8). `array_join` must NOT join `ArrayValuedFunctions.NAMES` — it returns an element, not an array
- [x] 1.4 Teach `translate/ExprFieldType` the `ELEMENT_OF_ARGUMENT` case: resolve argument 0 to its element type — the binding's `elementType` for an `array` `FieldExpr`, a fixed `string` for `map_keys`/`split_string`/`json_extract_array` — and `null` for anything else (the translator will already have rejected it)

## 2. DSL execution path

- [x] 2.1 Add the `array_join` branch to `translate/ExprTranslator#toFunction`: exactly one argument (arity 0 or 2+ rejected, with a message saying the function takes one operand and has no mode or outer variant — design D2), and that argument an `array` field or a call to one of the three array-returning scalars, with a message naming the expected operand
- [x] 2.2 Render it as `arrayJoin(<arg>)` through a jOOQ plain-SQL template on the CLICKHOUSE `DSLContext` — no conditional, no fallback (design D4)
- [x] 2.3 Add **no** positional guards: `array_join` is legal wherever any other scalar call is, including `filter`, `having`, an aggregate's arguments and a window expression (design D5). Verify by test that none of the existing window/aggregate guards catch it incidentally
- [x] 2.4 Reject `include_total` when a **row-mode** `select` contains an `array_join` call, with a message stating that the total is counted over source rows and does not describe an expanded result (design D7) — the one guard this change adds; aggregate mode returns early, since no count is ever built there
- [x] 2.5 Confirm no change is needed to group-key resolution — an `array_join` entry's alias already resolves through `resolveGroupKey`'s select-output branch — and add a test that pins it rather than a code change (design D9)

## 3. SQL front end (SQL → DSL)

- [x] 3.1 Register `ARRAY_JOIN` in `sql/SqlQueryOperators`: exactly one `ARRAY` operand, result type the collection element type of that operand; add it to `TABLE` and document in the class javadoc that Calcite's own `SqlLibraryOperators.ARRAY_JOIN` is a *different* Spark function (array→string) absent from the standard table, and that our single-operand arity is what makes the Spark spelling fail closed (design D2)
- [x] 3.2 Assert by test that `ARRAY_JOIN` parses as a function call under the Babel grammar (the measured-not-assumed check `IF` already carries), so a Calcite upgrade that reserved the word fails there rather than in every rendered statement
- [x] 3.3 Add the `ARRAY_JOIN` case to `sql/RexToExprTranslator#callExpr`, producing `FnExpr("array_join", false, args)`
- [x] 3.4 In `sql/RelToStructuredQueryTranslator`, derive the result column's kind and element type for an `array_join` call from its operand (element kind of an `array` column, `string` for the three array-returning scalars), and make sure it is NOT swept into the array-returning-call restriction — its result is a scalar and its alias IS groupable (design D9)
- [x] 3.5 Add **no** SQL-side positional guards, matching task 2.3; confirm by test that `ARRAY_JOIN` in `WHERE` and inside an aggregate both translate

## 4. DSL → SQL rendering

- [x] 4.1 Render `array_join` in `sql/StructuredQueryToSqlRenderer` as lowercase `array_join(<arg>)`, the operand rendered by the existing field/nested-call paths
- [x] 4.2 Make sure the renderer's projection-only checks admit an `array` field and an array-returning call as `array_join`'s operand (they should follow from task 1.3; verify rather than duplicate), and that the renderer does not refuse `array_join` in positions the DSL accepts

## 5. Tests

- [x] 5.1 `QueryFunctionCatalogParityTest`: the `array_join` descriptor is accepted by the translator, with its single-argument arity enforced
- [x] 5.2 DSL render tests: over an `array` column and over `split_string`/`map_keys`/`json_extract_array`; the aggregate-mode group-by-alias shape; an `array_join` in a filter and inside an aggregate
- [x] 5.3 DSL rejection tests: a non-array operand, `array_join(topics, 'left')` (arity), `array_join()` (arity), and `include_total` alongside a row-mode expansion — each asserting HTTP 400, `error` `bad_request`, and that the message names the reason; plus an acceptance test that an aggregate-mode expansion grouped by its alias is accepted with `include_total` true
- [x] 5.4 SQL→DSL tests: expansion translating over a column and over a computed array, the group-by-alias form, the element-typed result column, `ARRAY_JOIN` in `WHERE`, and the two arity rejections
- [x] 5.5 Round-trip tests in the translation-endpoint suite: DSL → SQL → DSL for `array_join` over a plain field, nested over `split_string`, and as a filter operand
- [x] 5.6 Discovery test: `GET /v1/queries/functions` lists `array_join` in alphabetical position with `returns` `element_of_argument` and one required argument, and the response parses strictly as JSON (not only via `jsonPath`)
- [x] 5.7 Assert the aggregate pipeline still refuses `array_join` as a measure (it resolves against the aggregate group only) so the design's claim is pinned rather than inferred

## 6. Runtime verification against ClickHouse

- [x] 6.1 Verify against the `local_env` ClickHouse that the rendered `arrayJoin(...)` returns the expected expanded row counts over a plain `Array(String)` column and an `Array(LowCardinality(String))` one (`execution_path`), and that an empty-array row yields no rows — a silent-empty over a container column is the failure the preceding change actually hit, and only a live query catches it
- [x] 6.2 Verify the two inherited multi-expansion behaviours design D6 states — two different expansions multiply, two identical ones do not — so the spec's claim is measured on the version we ship against
- [x] 6.3 Exercise `POST /v1/queries/execute` end to end for a plain expansion and the group-by-alias aggregate shape, and `POST /v1/queries/execute-sql` for the `ARRAY_JOIN` spellings of both

## 7. Documentation and verification

- [x] 7.1 No configuration property is added, so `docs/configuration.md` needs no change — confirm this explicitly rather than silently skipping it
- [x] 7.2 Update any client-facing surface that enumerates the catalog (the bundled demo pages, since removed, and the query controllers' OpenAPI descriptions) only where it lists functions by name; leave prose that links to discovery alone
- [x] 7.2a Update `docs/sql-reference.md` — the enumeration of the accepted SQL subset: an `array_join` row in the scalar table (row-multiplying, empty array yields no rows, result typed as the element), `array_join` in the `array` and computed-array rows of the type-rules table, and the "array accessor" carve-out wording widened to "array-operand function" wherever it names the position. Same widening in `docs/claude-code-telemetry.md`, where the sentence about `map_keys` being projection-only names the same four accessors
- [x] 7.3 Confirm the delta specs in `openspec/changes/add-array-join-function/specs/` match the shipped behavior, amending them if implementation changed a decision
- [x] 7.4 `./gradlew clean build` passes (compile + checkstyle + tests)
