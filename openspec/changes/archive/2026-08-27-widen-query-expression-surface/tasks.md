## 1. Catalog entries and DSL translation

- [x] 1.1 Add the five arithmetic scalars (`plus`, `minus`, `multiply`, `div`, `modulo`) to `QueryFunctionCatalog`, each two required expression args returning `numeric`; state the null-on-zero-or-null-right-operand contract in the `description` of both `div` and `modulo`, and `modulo`'s sign-of-left-operand rule in its own
- [x] 1.2 Add `round` (second `digits` arg of kind `integer_literal`, marked optional), `floor` and `ceil` to `QueryFunctionCatalog`
- [x] 1.3 Add `map_value` (returning `string`) and `map_contains` (returning `boolean`) to `QueryFunctionCatalog`, each with `map` as an `expression` and `key` as a **single non-variadic** `string_literal` — follow `json_extract_string_ci`'s arg shape, NOT `pathKey()`; state in both descriptions that the key is matched literally, not as a path
- [x] 1.4 Add `map_keys` to `QueryFunctionCatalog` returning `array`, with exactly one `expression` argument
- [x] 1.5 Add `array_has`, `array_has_any`, `array_has_all` (returning `boolean`) and `array_length` (returning `integer`) to `QueryFunctionCatalog`
- [x] 1.6 Add `split_string` to `QueryFunctionCatalog` returning `array`, with `delimiter` as a `string_literal`
- [x] 1.7 Add the `distinct_supported: false` rationale to the `description` of `min`, `max`, `percentile_cont`, `percentile_disc`, `group_uniq_array` and `arg_max` (no-op / different statistic / already distinct / detaches operand)
- [x] 1.8 Render the sixteen functions in `translate.ExprTranslator`: `div` as `divide(a, nullIf(b, 0))` and `modulo` as `modulo(a, nullIf(b, 0))` — the guard is required on **both**, since ClickHouse's `modulo` throws on a zero divisor exactly as integer division does; `map_value` as `col['key']`; `map_contains` as `mapContains`; `map_keys` as `mapKeys`; `array_has*` as `has`/`hasAny`/`hasAll`; `array_length` as `length`; `split_string` as `arrayMap(x -> trimBoth(x), splitByString(<delim>, <text>))` — note ClickHouse's reversed argument order
- [x] 1.9 Declare the new return types in `translate.ExprFieldType`: `div` always yielding decimal, `modulo` preserving the widened operand type so an integer remainder stays an integer
- [x] 1.10 Register `map_keys` and `split_string` with `ArrayValuedFunctions` so their array results are projection-only alongside `json_extract_array`
- [x] 1.11 Confirm `QueryFunctionCatalogParityTest` passes — it holds the catalog and `ExprTranslator` in parity and will fail on any entry added to only one

## 2. Projection-only carve-outs

- [x] 2.1 Extend `SqlProjectionOnlyTypes.isAllowedFunctionArgument` to permit a `map` field at argument 0 of `map_value`/`map_contains`/`map_keys` and an `array` field at argument 0 of `array_has`/`array_has_any`/`array_has_all`/`array_length`
- [x] 2.2 Keep `JSON_EXTRACT_*` over a `map` field rejected — assert it with a test, since the carve-out sets now overlap in the same method and a widened `isExtractableFrom` would silently admit it
- [x] 2.3 Assert the carve-outs are positional: a container field in argument 1 of `map_value`, `map_contains` or `array_has` is still rejected
- [x] 2.4 Reject a non-literal `map_value`/`map_contains` key (a column reference or expression) with a message naming the single-string-literal rule
- [x] 2.5 Assert an array-returning scalar nests in an array accessor's argument 0 — `array_length(map_keys(m))` and `array_has(split_string(t, ','), 'x')` translate, while `map_keys` elsewhere is rejected

## 3. SQL front end

- [x] 3.1 Add the sixteen function names to `SqlQueryOperators`, following the `DATE_TRUNC` custom-operator precedent — but NOT for `ROUND`/`FLOOR`/`CEIL`/`MOD`, which Calcite's standard table already carries (design Decision 3)
- [x] 3.2 Add `case` arms in `RexToExprTranslator.callExpr` for the sixteen names
- [x] 3.3 Lower Calcite's arithmetic `RexCall`s (`"+"`, `"-"`, `"*"`, `"/"`, `"%"`) to the named functions; branch the `"-"` arm on operand count so unary minus lowers to `minus(0, x)` rather than a malformed two-arg call
- [x] 3.4 Lower Calcite's own `ROUND`/`FLOOR`/`CEIL`/`MOD` calls, and reject the `FLOOR(<timestamp> TO <unit>)` two-operand form with a message pointing at `date_trunc`
- [x] 3.5 Confirm `%` (Calcite `PERCENT_REMAINDER`), `MOD(a, b)` and `modulo(a, b)` all lower to the same `StructuredQuery` — three inbound spellings, one catalog function
- [x] 3.6 Widen `RexToExprTranslator.isSupportedCast` to accept `CHAR`/`VARCHAR` → `TIMESTAMP`, and parse the three ISO-8601 spellings in `castExpr`; scope it to a literal operand against a timestamp column, and reject unparseable strings with `invalid_argument` naming the accepted formats
- [x] 3.7 Verify arithmetic and the new calls are accepted in `WHERE` and `HAVING`, not only in projection
- [x] 3.8 Confirm `RelToStructuredQueryTranslator` needs no change for these (all are scalar `RexCall`s, no new relational construct) — or make the minimum change and say why

## 4. Translate-back rendering

- [x] 4.1 Render `plus`/`minus`/`multiply`/`div`/`modulo` as infix `+ - * / %` in `StructuredQueryToSqlRenderer`, wrapping every binary arithmetic expression in parentheses unconditionally (design Decision 7); emit only `%` for `modulo`, never `MOD`, so the round-trip stays single-valued
- [x] 4.2 Render `round`/`floor`/`ceil`/`map_value`/`map_contains`/`map_keys`/`array_has*`/`array_length`/`split_string` as lowercase function calls
- [x] 4.3 Render the widened carve-outs so a `map`/`array` field renders in the accessor's argument 0 and still fails closed elsewhere with HTTP 400 `bad_request`
- [x] 4.4 Normalize a coerced string time literal back to the canonical `TIMESTAMP` form on re-render
- [x] 4.5 Add DSL → SQL → DSL round-trip tests for arithmetic (including nesting, unary minus and `modulo`), both accessor families, the nested `array_length(map_keys(m))` form, `split_string`, and the time-literal normalization

## 5. Spec-documented behaviour with no code change

- [ ] 5.1 Verify the envelope `distinct` flag against the shipped behaviour the spec delta now requires — row mode, aggregate mode, an expression projection, an alias sort, and paging — adding test coverage for any case `queryLevelDistinct` does not already cover
- [x] 5.2 Verify a projection-only field as a `distinct` projection is still rejected

## 6. Sensitive column on the OTLP metrics table

- [x] 6.1 Declare `point_attributes` sensitive in `OtelSinkSchemas.metricColumns()`, leaving `resource_attributes` and `scope_attributes` unflagged
- [x] 6.2 Add an integration assertion that a newly created metrics sink reports `sensitive: true` for `point_attributes` and `false` for the other two maps
- [x] 6.3 Add an integration assertion that a non-`FULL_ADMIN` caller cannot read `point_attributes` — including through a `map_value` call — via the existing `CatalogQueryService` chokepoint, with no map-specific enforcement added
- [ ] 6.4 **Operational, not code**: `PATCH /v1/tables/{name}` the already-materialized `otel_claude_code_metrics` on each environment carrying an OTLP metrics sink, since `OtelSinkService:109` applies a schema once at creation and does not reconcile. Record the environments patched — the code change alone leaves existing data unprotected

## 7. Live-ClickHouse verification (the silent-empty risk)

- [x] 7.1 Assert `map_value` returns a **non-empty** value over real `Map(String,String)` data — a passing "the query runs" test is what the `json_extract_string_ci` predecessor also did
- [x] 7.2 Assert `map_value` AND `map_contains` with a **dotted** key (`user.email`) both hit, proving literal-key rather than path semantics. `map_contains` is the quieter failure: read as a path it returns `false` for every row, which looks like a valid empty result
- [x] 7.3 Assert `map_value` over an absent key and an empty map both yield the empty string, and that a `GROUP BY` on the result buckets correctly
- [x] 7.4 Assert `map_contains` distinguishes an absent key from a key stored with an **empty value**, where `map_value` returns the empty string for both — the reason the presence test exists
- [x] 7.5 Assert `map_keys` returns the real key names over live data, that it is empty (not null) for an empty map, and that `array_length(map_keys(...))` gives the entry count
- [x] 7.6 Assert `div` yields NULL — not `nan`, `inf` or an error — for a zero-denominator group, on both integer and float operands, with other groups still returning values
- [x] 7.7 Assert `modulo` yields NULL for a zero right operand rather than **throwing**, on both integer and float operands — the failure mode differs from `div`'s, so a guard dropped from `modulo` alone would pass every `div` test
- [x] 7.8 Assert `modulo(-7, 3)` is `-1`, pinning the sign-of-left-operand convention
- [x] 7.9 Assert `array_has*` yield false and `array_length` yields 0 for an empty and a NULL array column; guard the rendering if ClickHouse returns NULL instead of changing the spec
- [x] 7.10 Assert `split_string` trims elements, and that `array_has(split_string(...), ...)` nests
- [ ] 7.11 `EXPLAIN` a `map_value` group-by on real ClickHouse and record the cost before documenting the function as cheap — `json_extract_string_ci` set this precedent (design Open Questions)
- [ ] 7.12 Probe whether `nan`/`inf` can reach the response envelope from any surviving arithmetic path; guard the result if so

## 8. Discovery and documentation

- [x] 8.1 Verify `GET /v1/queries/functions` advertises all sixteen new entries in alphabetical order with their groups, signatures, return types and arg kinds, and that the enumeration test names them
- [x] 8.2 Verify the `distinct_supported: false` descriptions carry their rationale
- [x] 8.3 Update `docs/` where the query surface's function list or the OTLP table schema is described; no config property is added or renamed by this change, so `docs/configuration.md` needs no entry — confirm that rather than assuming it
- [x] 8.4 Note in the change hand-off that a non-`FULL_ADMIN` reader now gets "unknown column" for `point_attributes`, not a `403` — deliberate, but surprising to anyone who queried it before the flag

## 9. Verification

- [x] 9.1 Re-read the six delta specs and confirm the shipped behaviour matches each requirement and scenario
- [x] 9.2 `./gradlew clean build` passes (compile + checkstyle + tests)
