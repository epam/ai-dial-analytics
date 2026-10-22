## MODIFIED Requirements

### Requirement: DSL constructs inexpressible in the SQL subset fail closed

`POST /v1/queries/translate` SHALL reject, with 400 `bad_request` and a message naming the
construct, any DSL document that the external SQL dialect cannot express even though execution
would accept it: a contains comparison (`co`/`nc`/`ico`/`inc`) whose value contains `%` or `_`
(the SQL subset's contains rule forbids metacharacters in the pattern text), a null literal
used outside an `eq`/`ne` comparison (only `IS NULL`/`IS NOT NULL` are renderable), an
offset page with `include_total: true` (the SQL surface has no total-count opt-in), a
`having` clause on a row-mode query (execution silently ignores it, and a rendered `HAVING`
without grouping would be rejected by the SQL subset), `distinct: true` on an aggregate-mode
query (execution deduplicates aggregate rows, but `SELECT DISTINCT` combined with `GROUP BY`
is rejected by the SQL subset as nested aggregation), and any reference to an `array`- or
`object`-typed field outside a plain projection item — as a `sort` key or `group_by` key (directly
or via the alias of such a projected field), a function/aggregate argument, or under a
`distinct` projection (execution accepts all of these, but array and object fields are
projection-only in the SQL subset: the rendered statement would be rejected on translate-back;
the error names the field and the projection-only restriction). An `array`-typed field SHALL also be
rejected as a `filter`/`having` comparison operand, including `eq`/`ne` against a null literal.

**An `object`-typed field as a `filter` comparison operand is the second exception.** The SQL subset
accepts an `object` field as the **left** operand of `LIKE`/`ILIKE`/`NOT LIKE`/`NOT ILIKE`/`=`/`<>`/`IN`
against a character literal — the one shape execution accepts as well — so a **`filter`**
comparison using `co`/`nc`/`ico`/`inc`/`eq`/`ne`/`in`
on one SHALL render rather than be rejected, the field rendering as its plain double-quoted
identifier and never wrapped in a coercion. The exception SHALL NOT extend to `having`: a `having`
comparison on a bare `object` field SHALL still be rejected with 400 `bad_request` naming the field and
the projection-only restriction. The rendered `HAVING "<field>" ILIKE …` references a field that is
neither grouped nor aggregated — `group_by` on an `object` field being rejected above — so the
statement would be refused by the SQL front end on translate-back and by ClickHouse on execution;
emitting it would break the round trip this endpoint guarantees. (See the matching statement in
[sql-query-translation](../sql-query-translation/spec.md).) Rendering follows the ordinary rules for those operators:
`co`/`nc` as `LIKE`/`NOT LIKE`, `ico`/`inc` as `ILIKE`/`NOT ILIKE`, `eq`/`ne` as `=`/`<>`, `in` as
`IN (…)` — and the metacharacter rule above applies to the contains family here exactly as it does to
a character field. The remaining comparisons on an `object` field SHALL still be rejected with 400
`bad_request` naming the field and the projection-only restriction: the ordering comparisons
`lt`/`gt`/`le`/`ge`, and `eq`/`ne` against a **null literal** (which would render as
`IS NULL`/`IS NOT NULL` — a constant predicate over a column that is never null, and a use the SQL
subset refuses on translate-back). A right-hand literal compared with `eq`/`ne`/`in` SHALL be a
well-formed JSON document; one that is not SHALL be rejected with 400 `bad_request` naming the field
and the literal.

The **other exception** is an
`object`-typed field passed as the first argument of a `json_extract_*` call: the SQL subset
accepts that position, so such a document SHALL render rather than be rejected, and the call
SHALL render as
`json_extract_string|long|double|bool|string_ci|array("<field>", '<key>'[, '<key>'…])` — the bare
object field never appearing as an operand in its own right. `json_extract_string_ci` SHALL render
with exactly its two arguments; `json_extract_array` SHALL render as a projection item and SHALL be
rejected as another expression's operand, since its result is an array — the same restriction
`group_uniq_array`'s result carries, and reaching exactly as far. It SHALL extend to a `sort` or
`group_by` key naming the **output name** of a projected array-valued call (`json_extract_array`,
`group_uniq_array`, `map_keys`, `split_string`), which SHALL likewise be rejected with 400
`bad_request` naming the producing function: the rendered `GROUP BY "alias"`/`ORDER BY "alias"` is
refused by the SQL front end on translate-back, so emitting it would break the round trip this endpoint
guarantees. That the declared-field-type guard cannot see a computed array is an implementation fact
about one guard, not a licence to render — the check is made on the output names instead. It SHALL
NOT extend to a `distinct` projection of such a call, which the SQL subset accepts. The exception SHALL recognize the
function name **case-insensitively**, exactly as execution does: a document spelling the call
`JSON_EXTRACT_STRING` SHALL render (in the catalog's canonical lower case) rather than be rejected,
since a document the execute endpoint accepts must not be untranslatable on spelling alone. The restriction continues to apply
unchanged to the same `object` field used in a still-restricted position in the same document, and to an
`array`-typed field in every position including a `json_extract_*` argument. Neither `array`- nor
`object`-typed columns SHALL cause an absent/empty row-mode `select` to be rejected: they
render as ordinary projected identifiers (see the render requirement), so no source column
type makes a bare projection untranslatable. Contains
operators with metacharacter-free values SHALL render with exact semantics preserved:
`co`/`nc` (case-sensitive) as `LIKE '%<value>%'`/`NOT LIKE '%<value>%'`, `ico`/`inc`
(case-insensitive) as `ILIKE '%<value>%'`/`NOT ILIKE '%<value>%'`; `eq`/`ne` against a null
literal SHALL render as `IS NULL`/`IS NOT NULL`.

#### Scenario: Grouping by a projected array call's alias is rejected

- **WHEN** the input DSL projects `split_string(deployment, '-')` as `parts` and names `parts` as a
  `group_by` or `sort` key
- **THEN** the response is a 400 `ErrorView` naming `split_string` and the array restriction, rather
  than SQL carrying `GROUP BY "parts"` that `POST /v1/queries/translate-sql` would then refuse

#### Scenario: Distinct over a projected array call still renders

- **WHEN** the input DSL sets `distinct` and projects `split_string(deployment, ',')`
- **THEN** the translation succeeds and the rendered `SELECT DISTINCT` translates back unchanged — a
  distinct projection is not a key, and the SQL subset accepts it

#### Scenario: Contains value with percent sign rejected

- **WHEN** the input DSL contains a `co` comparison with value `50%`
- **THEN** the response is a 400 `ErrorView` naming the contains operator and the metacharacter restriction

#### Scenario: Contains operator renders LIKE

- **WHEN** the input DSL contains a `co` comparison on `model` with value `gpt`
- **THEN** the rendered SQL contains `"model" LIKE '%gpt%'`

#### Scenario: Case-insensitive contains renders ILIKE

- **WHEN** the input DSL contains an `ico` comparison on `model` with value `gpt`
- **THEN** the rendered SQL contains `"model" ILIKE '%gpt%'`

#### Scenario: Null equality renders IS NULL

- **WHEN** the input DSL contains an `eq` comparison between a field and a null-typed value
- **THEN** the rendered SQL contains `"<field>" IS NULL`

#### Scenario: Bare select with object source column renders

- **WHEN** the input DSL is a row-mode document with no `select` on an entity that has enrichments and whose source columns include an `object`-typed field — a document `/v1/queries/execute` accepts
- **THEN** the response is `200` and the rendered projection spells out the source-owned columns, including the object column as an ordinary double-quoted identifier

#### Scenario: Bare select with array source column renders

- **WHEN** the input DSL is a row-mode document with no `select` on an entity whose source columns include an `array`-typed field
- **THEN** the response is `200` and the rendered projection includes the array column as an ordinary double-quoted identifier alongside the scalar source-owned columns

#### Scenario: Array field in filter rejected

- **WHEN** the input DSL filters on the `array`-typed field `execution_path` (e.g. `eq` against a null literal)
- **THEN** the response is a 400 `ErrorView` naming `execution_path` and the projection-only restriction

#### Scenario: Object field contains comparison renders

- **WHEN** the input DSL filters `ico` on the `object`-typed field `request_tags` with the value `gpt-4`
- **THEN** the response is `200`, the rendered SQL contains `"request_tags" ILIKE '%gpt-4%'`, and
  posting that statement to `/v1/queries/translate-sql` yields the identical DSL document

#### Scenario: Object field equality against a JSON literal renders

- **WHEN** the input DSL filters `eq` on the `object`-typed field `jwt_claims` against the literal `{"sub":"user-1"}`
- **THEN** the response is `200` and the rendered SQL compares the plain `"jwt_claims"` identifier with
  that character literal, round-tripping through `/v1/queries/translate-sql`

#### Scenario: Object field equality against a non-JSON literal rejected

- **WHEN** the input DSL filters `eq` on an `object`-typed field against the literal `x`
- **THEN** the response is a 400 `ErrorView` naming the field and the JSON-literal requirement

#### Scenario: Object field in filter rejected

- **WHEN** the input DSL filters on the `object`-typed field `jwt_claims` with `eq` against a null literal
- **THEN** the response is a 400 `ErrorView` naming `jwt_claims` and the projection-only restriction

#### Scenario: Object field document comparison in having rejected

- **WHEN** the input DSL is an aggregate-mode document whose `having` compares an `object` field with
  `ico`, grouping by an ordinary scalar field
- **THEN** the response is a 400 `ErrorView` naming the field and the projection-only restriction — the
  carve-out is a `filter` rule, and the rendered `HAVING` would reference an ungrouped field that
  neither `/v1/queries/translate-sql` nor ClickHouse accepts

#### Scenario: Object field ordering comparison rejected

- **WHEN** the input DSL filters `gt` on an `object`-typed field against a JSON literal
- **THEN** the response is a 400 `ErrorView` naming the field and the projection-only restriction

#### Scenario: Object field contains value with a metacharacter rejected

- **WHEN** the input DSL filters `co` on an `object`-typed field with a value containing `%`
- **THEN** the response is a 400 `ErrorView` naming the contains operator and the metacharacter
  restriction — the same rule a character field carries, not the projection-only restriction

#### Scenario: Array field as sort or group key rejected

- **WHEN** the input DSL sorts on or groups by an `array`-typed field — named directly or through the alias of a projected array field
- **THEN** the response is a 400 `ErrorView` naming the field and the projection-only restriction

#### Scenario: Object field as sort or group key rejected

- **WHEN** the input DSL sorts on or groups by an `object`-typed field — named directly or through the alias of a projected object field
- **THEN** the response is a 400 `ErrorView` naming the field and the projection-only restriction

#### Scenario: Array field as function argument or distinct projection rejected

- **WHEN** the input DSL passes an `array`-typed field to a function/aggregate (e.g. `count`) or sets `distinct: true` on a row-mode document whose projection includes one
- **THEN** the response is a 400 `ErrorView` naming the field and the projection-only restriction

#### Scenario: Object field as function argument or distinct projection rejected

- **WHEN** the input DSL passes an `object`-typed field to a function/aggregate (e.g. `count`) or sets `distinct: true` on a row-mode document whose projection includes one
- **THEN** the response is a 400 `ErrorView` naming the field and the projection-only restriction

#### Scenario: Row-mode having rejected

- **WHEN** the input DSL is a `mode="row"` document carrying a `having` clause
- **THEN** the response is a 400 `ErrorView` naming `having` and row mode, even though `/v1/queries/execute` would accept the document (ignoring the clause)

#### Scenario: Aggregate-mode distinct rejected

- **WHEN** the input DSL is a `mode="aggregate"` document with `distinct: true`
- **THEN** the response is a 400 `ErrorView` naming `distinct` and aggregate mode, even though `/v1/queries/execute` would accept the document

#### Scenario: Percentile functions render in WITHIN GROUP form

- **WHEN** the input DSL selects `percentile_cont` or `percentile_disc` with `(fraction, column)` arguments
- **THEN** the rendered SQL spells the call `<fn>(<fraction>) WITHIN GROUP (ORDER BY "<column>")` — the only percentile shape the SQL subset parses — and the statement translates back to the identical DSL call

#### Scenario: json_extract over an object field renders

- **WHEN** the input DSL selects `json_extract_string` over the `object`-typed field `jwt_claims` with a literal path
- **THEN** the response is `200` and the rendered SQL contains `json_extract_string("jwt_claims", '<key>')`, and the statement translates back to the identical DSL call

#### Scenario: An upper-case json_extract over an object field renders

- **WHEN** the input DSL selects `JSON_EXTRACT_STRING` over the `object`-typed field `jwt_claims` — a spelling the execute endpoint accepts
- **THEN** the response is `200` and the rendered SQL spells the call in the catalog's canonical lower case, translating back to that same normalized DSL call

#### Scenario: Filter on an extracted object path renders and round-trips

- **WHEN** the input DSL filters `json_extract_string(jwt_claims, 'sub') = 'user-1'`
- **THEN** the response is `200`, the rendered SQL carries the extraction in its `WHERE` clause, and posting that statement to `/v1/queries/translate-sql` yields the identical DSL document

#### Scenario: Sort or group on an extracted object path renders

- **WHEN** the input DSL groups by, or sorts on, the alias of a projected `json_extract_string` call over an `object` field
- **THEN** the response is `200` — the projection-only restriction applies to the bare object field, not to the extraction's output

#### Scenario: The object field is still rejected elsewhere in the same document

- **WHEN** the input DSL both extracts from an `object` field and separately sorts on that same bare field
- **THEN** the response is a 400 `ErrorView` naming the field and the projection-only restriction, raised by the bare use

#### Scenario: json_extract over an array field is still rejected

- **WHEN** the input DSL passes an `array`-typed field as the JSON argument of `json_extract_string`
- **THEN** the response is a 400 `ErrorView` naming the field and the projection-only restriction — the carve-out is for `object` fields only


#### Scenario: A conditional expression renders and round-trips

- **WHEN** the input DSL carries a nested `if` FnExpr over `not_empty` and `starts_with` calls
- **THEN** the response renders `if(not_empty(...), ..., if(starts_with(...), ..., ...))` using the
  catalog's canonical names, and the rendered statement re-parses through the SQL front end to the
  same DSL document

#### Scenario: Case-insensitive extraction over an object field renders

- **WHEN** the input DSL selects `json_extract_string_ci` over an `object`-typed field
- **THEN** the response renders `json_extract_string_ci("<field>", '<key>')` and the bare object field
  does not appear as an operand in its own right

#### Scenario: Array extraction renders in a projection only

- **WHEN** the input DSL selects `json_extract_array` over an `object`-typed field as a projection item
- **THEN** the response renders the call; and when the same call is used as **another expression's
  operand**, the response is a 400 `ErrorView` naming the array result and the projection-only
  restriction — the positions that restriction covers for `group_uniq_array`, no wider. A `sort` or
  `group_by` naming the **output name** of such a projection renders, exactly as it does for
  `group_uniq_array`: the projection-only guard is keyed on a declared field's type and a computed
  result has none, and this change does not widen it.
