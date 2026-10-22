# query-translation-endpoint Specification

## Purpose

The REST translation surface between the two query representations: `POST /v1/queries/translate-sql`
maps a SQL SELECT to the structured query DSL (the exact translation
[rest-sql-endpoint](../rest-sql-endpoint/spec.md) would execute, per
[sql-query-translation](../sql-query-translation/spec.md)), and `POST /v1/queries/translate` renders
a DSL document as a statement in the external SQL dialect (the subset `execute-sql` accepts). Both
are validation-only — same catalog rules and error contract as the execute pair, never contacting
ClickHouse — and round-trip coherent: each endpoint's output is valid input for the other, with DSL
constructs the SQL subset cannot express rejected fail-closed.
## Requirements
### Requirement: SQL translates to the structured query DSL without execution

`POST /v1/queries/translate-sql` SHALL accept the same request body as
`POST /v1/queries/execute-sql` (`{"sql": "<one read-only SELECT>"}`) and SHALL translate the
statement to the structured query DSL using exactly the mapping contract of the
`sql-query-translation` capability — same supported subset, same catalog validation, same
rejections. The endpoint SHALL NOT execute the query and SHALL NOT contact ClickHouse. On
success it SHALL return `200` with `{"query": <DSL document>}`, where the DSL document is
accepted verbatim by `POST /v1/queries/execute` and executes with semantics equivalent to the
submitted SQL. A select entry SHALL omit `as` only where omitting it leaves the whole select list
resolving to the same output names, so an unnamed SQL expression translates to an alias-free select
entry that still executes to the same column name, while an alias the DSL's naming would not
reproduce in place is always carried. Translation failures SHALL
return the same `ErrorView` errors as `execute-sql` (400 `bad_request` naming the offending
construct or unknown identifier).

Validation SHALL include the **row governance** the execute path applies, not only the catalog and
mapping checks the translation itself performs: the endpoint SHALL run the same
`StructuredQueryBuilder` pass `POST /v1/queries/execute` runs — field allowlist, function catalog, mode
coherence, and the default limit of 100 with the maximum of 1000 — and discard its result, so a document
this endpoint returns is one `POST /v1/queries/execute` accepts. A statement whose `LIMIT` exceeds the
maximum SHALL therefore be rejected with 400 `bad_request` naming the requested limit and the maximum,
rather than answered with a document that execution then rejects. (Previously the endpoint ran only the
SQL front end, so the governance it already described was not in fact applied and an over-maximum `LIMIT`
was answered `200`.) A caller needing the accept/reject verdict for the whole `execute-sql` surface —
composite statements included — SHALL use `POST /v1/queries/validate-sql` (see
[rest-sql-endpoint](../rest-sql-endpoint/spec.md)); this endpoint's product is the DSL document, and it
rejects whatever has no document.

A **composite** statement — one that joins, reads a derived table or CTE, carries a subquery, combines
branches with a set operation, or is nested
by the translator: to carry an `ORDER BY`, `GROUP BY` or `DISTINCT` key its `SELECT` list does not project
(the sort key only when computed; a group or deduplication key whether computed or a bare column), or to
separate a window function or a row expansion from a `DISTINCT` (either above it, or a window below it)
— is accepted and
executed by `execute-sql` but has no `StructuredQuery` equivalent; this endpoint SHALL reject it with 400
`bad_request` and the message
`statements with set operations, joins, derived tables or subqueries — including a statement nested by this surface to carry an ORDER BY, GROUP BY or DISTINCT key its SELECT list does not project, or to separate a window function or a row expansion from a DISTINCT — execute but have no structured-query equivalent`,
without contacting ClickHouse. A single-block statement using `CASE`, arithmetic or a window expression
is not composite and SHALL translate, and neither is one whose only computed sort or group key is already
carried by its projection.

#### Scenario: Aggregate SQL returns its DSL document

- **WHEN** a client posts `{"sql": "SELECT model, count(*) AS n FROM dial_usage_log GROUP BY model"}` to `/v1/queries/translate-sql`
- **THEN** the response is `200` with a `query` object having `entity="dial_usage_log"`, `mode="aggregate"`, `group_by=["model"]`, and a `count` fn select entry aliased `n` — and no query was executed

#### Scenario: Unsupported SQL rejected without execution

- **WHEN** a client posts `{"sql": "WITH RECURSIVE r AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 3) SELECT n FROM r"}`
- **THEN** the response is a 400 `ErrorView` with code `bad_request` naming the unsupported construct, and ClickHouse is not contacted

#### Scenario: Set-operation statement has no DSL document

- **WHEN** a client posts `{"sql": "SELECT model FROM dial_usage_log UNION ALL SELECT model FROM rate_analytics"}`
- **THEN** the response is a 400 `ErrorView` with code `bad_request` carrying the composite-statement
  message, whose first clause names set operations, and ClickHouse is not contacted — while the same
  statement posted to `/v1/queries/execute-sql` executes

#### Scenario: Composite statement has no DSL document

- **WHEN** a client posts `{"sql": "SELECT a.x FROM t1 a JOIN t2 b ON a.id = b.id"}` over two queryable entities
- **THEN** the response is a 400 `ErrorView` with code `bad_request` and the composite-statement message, and ClickHouse is not contacted

#### Scenario: A statement nested for its sort key gets the composite message

- **WHEN** a client posts `{"sql": "SELECT deployment FROM dial_usage_log GROUP BY deployment ORDER BY count(*) DESC LIMIT 5"}`
- **THEN** the response is a 400 `ErrorView` with code `bad_request` carrying the same composite-statement
  message — which names the translator's own nesting, so the caller is not told about a derived table it
  never wrote — rather than the superseded `an ORDER BY expression must be a column or appear in the
  SELECT list` refusal, and ClickHouse is not contacted

#### Scenario: Translated document is executable

- **WHEN** the `query` object returned by `/v1/queries/translate-sql` is posted unchanged to `/v1/queries/execute`
- **THEN** it deserializes and validates successfully and runs with the same semantics as posting the original SQL to `/v1/queries/execute-sql`

#### Scenario: Unnamed SQL expression translates without an alias

- **WHEN** a client posts `{"sql": "SELECT model, count(*) FROM dial_usage_log GROUP BY model"}` to `/v1/queries/translate-sql`
- **THEN** the `query` object's `count` select entry carries no `as` key, and posting the document to
  `/v1/queries/execute` returns rows keyed `model` and `count`

#### Scenario: CASE statement translates to a case expression

- **WHEN** a client posts `{"sql": "SELECT CASE WHEN success THEN 1 ELSE 0 END AS ok FROM dial_usage_log"}` to `/v1/queries/translate-sql`
- **THEN** the response is `200` and the select entry is a `case` expression aliased `ok`

#### Scenario: Limit above the maximum rejected rather than translated

- **WHEN** a client posts `{"sql": "SELECT event_id FROM dial_usage_log LIMIT 50000"}` to `/v1/queries/translate-sql`
- **THEN** the response is a 400 `ErrorView` with code `bad_request` naming the requested limit and the
  1000-row maximum, and no `query` document is returned — the same answer `/v1/queries/execute-sql` gives

### Requirement: Structured query DSL translates to external-dialect SQL without execution

`POST /v1/queries/translate` SHALL accept the same request body as `POST /v1/queries/execute`
(a structured query DSL document), validate it against the catalog with the same rules as
execution (entity resolution, field allowlist, closed function catalog, mode coherence, offset
paging with limit governance, param rejection), and — without executing it — return `200` with
`{"sql": "<SELECT statement>"}` in the external SQL dialect: the statement SHALL be within the
subset accepted by `POST /v1/queries/execute-sql` and SHALL translate back (per
`sql-query-translation`) to a structured query semantically equivalent to the input. The
rendered SQL SHALL reference only catalog-level identifiers (entity and flat field names,
double-quoted) — never physical ClickHouse table names, join clauses, or internal SQL.
Function names SHALL match case-insensitively, exactly as execution does, and render
lowercase; a function's `distinct` flag SHALL render as the `DISTINCT` quantifier only on the
aggregates whose execution honors it (`count`/`sum`/`avg`) and SHALL be dropped on every other
catalog function (execution silently ignores the flag there, and rendering it would produce a
statement outside the accepted SQL subset); literal values SHALL be normalized (trimmed and
parsed) exactly as execution binds them before rendering, so a value execution accepts never
renders with different semantics.
Every output column SHALL render with an explicit `AS` alias carrying the resolved output name
execution would use (per the structured-query output-column-naming contract) unless the expression
already produces that name on its own — in practice a plain field reference whose resolved name is its
own field name. An entry with no `as` SHALL therefore render its derived name (e.g. `count(*) AS
"count"`) rather than an unnamed expression, so the rendered statement declares exactly the columns the
execute path returns and re-translating it yields the same names.
An absent/empty row-mode `select` SHALL render as `SELECT *` for **every** entity, because a star and
an omitted `select` now share one wildcard rule — the entity's non-heavy source-owned columns of every
declared type, `array` and `object` included (see the `structured-query` capability's "Wildcard
projection excludes heavy columns" and the `sql-query-translation` capability's star expansion). The
rendered star therefore translates back to exactly the projection execution uses. (Previously an
enriched entity had to be spelled out column by column, because SQL `*` would have fanned out into its
enrichment columns.) No source column type SHALL cause a bare `select` to be rejected; a bare `select`
on an entity whose every caller-visible source column is `heavy` SHALL be rejected with the same 400
`bad_request` the execute path produces, naming the entity.
Validation failures — including an unknown/non-queryable entity — SHALL return the same 400
`bad_request` `ErrorView` (with the same message) that `POST /v1/queries/execute` produces.

#### Scenario: Aggregate DSL renders round-trippable SQL

- **WHEN** a client posts a DSL document with `entity="dial_usage_log"`, `mode="aggregate"`, `group_by=["model"]`, a `count` select aliased `n`, a `gt` filter on `price`, sort on `n` desc, and limit 50 to `/v1/queries/translate`
- **THEN** the response is `200` with a single SELECT statement containing `GROUP BY`, `WHERE`, `ORDER BY … DESC`, and `LIMIT 50`, which `/v1/queries/execute-sql` accepts and executes with equivalent semantics

#### Scenario: Enrichment fields render as quoted flat identifiers

- **WHEN** the input DSL selects the enrichment field `deployment_ref.display_name`
- **THEN** the rendered SQL references it as the double-quoted flat identifier `"deployment_ref.display_name"` and contains no JOIN clause and no physical table names

#### Scenario: Bare select renders the execution projection

- **WHEN** the input DSL is a row-mode document with no `select` on an entity that has enrichments
- **THEN** the rendered SQL is `SELECT * FROM "<entity>"` and translates back to a query projecting
  exactly the entity's non-heavy source-owned columns — no enrichment column on either side of the
  round-trip

#### Scenario: Bare select on an entity with object source columns renders

- **WHEN** the input DSL is a row-mode document with no `select` on `dial_usage_log`, every one of whose verbatim-content columns — the three bodies and the two `object`-typed documents — is owned by the `dial_usage_log_payload` enrichment
- **THEN** the response is `200` with `SELECT *` — no source column type blocks the bare projection any more — and `/v1/queries/execute-sql` accepts the statement, expanding the star over the source columns while leaving every payload column out, exactly as execution does

#### Scenario: Selected array field renders and round-trips

- **WHEN** the input DSL is a row-mode document selecting the `array`-typed field `execution_path` on `dial_usage_log`
- **THEN** the rendered SQL projects `"execution_path"` as a double-quoted identifier, is accepted by `/v1/queries/execute-sql`, and translates back to the identical projection

#### Scenario: Selected object field renders and round-trips

- **WHEN** the input DSL is a row-mode document selecting the `object`-typed field `dial_usage_log_payload.jwt_claims` on `dial_usage_log`
- **THEN** the rendered SQL projects `"dial_usage_log_payload.jwt_claims"` as a double-quoted identifier, emits the grain join, is accepted by `/v1/queries/execute-sql`, and translates back to the identical projection

#### Scenario: Selected body field renders under its qualified name

- **WHEN** the input DSL is a row-mode document selecting `dial_usage_log_payload.request_body` on
  `dial_usage_log`
- **THEN** the rendered SQL projects `"dial_usage_log_payload.request_body"` as a double-quoted
  identifier, contains no JOIN clause, is accepted by `/v1/queries/execute-sql`, and translates back to
  the identical projection

#### Scenario: Every scalar catalog function renders within the SQL subset

- **WHEN** the input DSL selects any scalar function from the closed catalog — `lower`, `upper`, `length`, `trim`, `abs`, `date_bin`, or `width_bucket`
- **THEN** the rendered SQL spells the call under its lowercase DSL name (e.g. `length("deployment")`), `/v1/queries/execute-sql` accepts the statement, and it translates back to the identical DSL call

#### Scenario: Uppercase function names translate like execution

- **WHEN** the input DSL uses `COUNT` with no arguments or `PERCENTILE_CONT` with `(fraction, column)` arguments
- **THEN** the response is `200` and the rendered SQL spells the calls lowercase — `count(*)` and `percentile_cont(<fraction>) WITHIN GROUP (ORDER BY "<column>")` — exactly as for the lowercase spellings

#### Scenario: Inert distinct flag drops like execution ignores it

- **WHEN** the input DSL sets `distinct: true` on a function where execution ignores the flag — a scalar catalog function (e.g. `lower`) or `min`/`max` — in a document `/v1/queries/execute` accepts
- **THEN** the rendered SQL spells the call without a `DISTINCT` quantifier (e.g. `lower("deployment")`), is accepted by `/v1/queries/execute-sql`, and translates back to the execution-equivalent call

#### Scenario: Padded literal values normalize like execution

- **WHEN** the input DSL contains a boolean literal `" true "` (whitespace-padded) that `/v1/queries/execute` accepts as `TRUE`
- **THEN** the rendered SQL contains `TRUE` — the parsed value, never a silently inverted or unparseable literal from the raw text

#### Scenario: Unknown entity yields 400

- **WHEN** the input DSL names an entity that is not an ACTIVE queryable entity
- **THEN** the response is a 400 `ErrorView` with code `bad_request`, matching the execute
  endpoint's rejection (naming the supported entities)

#### Scenario: Validation failures yield 400 without rendering

- **WHEN** the input DSL references an unknown field or a limit above the structured-query maximum
- **THEN** the response is a 400 `ErrorView` with code `bad_request` carrying the same message the execute endpoint would produce

#### Scenario: A bare body field name yields 400

- **WHEN** the input DSL selects `request_body` on `dial_usage_log`
- **THEN** the response is a 400 `ErrorView` with code `bad_request` naming the unknown field, and no
  SQL is rendered

#### Scenario: Alias-free select entry renders its derived alias

- **WHEN** the input DSL is an aggregate-mode document grouping by `model` with a `count` select entry that has no `as`
- **THEN** the rendered SQL projects `count(*) AS "count"` alongside `"model"`, is accepted by
  `/v1/queries/execute-sql`, and translates back to outputs named `model` and `count`

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

### Requirement: Translation endpoints share the query surface's access rules

Both translation endpoints SHALL be governed by the same authentication rules as the other
`/v1/queries` endpoints, and SHALL be documented in the service's OpenAPI description. Auth failures
return the standard 401/403 `ErrorView`s.

Authorization SHALL require `TABLE_GET` on every table the request resolves, not `TABLE_READ`:
translation validates against the resolved catalog model and returns SQL or a structured query, and
never contacts ClickHouse, so it discloses schema rather than data. A caller lacking `TABLE_GET` on
a resolved table SHALL be refused as though the table did not exist — `400 bad_request` reporting an
unknown entity, relation or field, never `403`.

Sensitive columns SHALL remain governed by `TABLE_SENSITIVE` on the owning table: a caller
without it SHALL NOT be able to translate a query that references one, and the refusal SHALL be the
ordinary unknown-field error.

#### Scenario: Unauthenticated caller rejected in oidc mode

- **WHEN** security mode is `oidc` and an unauthenticated client posts to `/v1/queries/translate` or `/v1/queries/translate-sql`
- **THEN** the response is a 401 `ErrorView` with code `unauthorized`

#### Scenario: Translation needs only schema visibility

- **GIVEN** a table whose `get` list contains the caller's provider role and whose `read` list does
  not
- **WHEN** the caller posts a statement against that entity to `/v1/queries/translate-sql`
- **THEN** the translation succeeds
- **AND WHEN** the caller posts the same statement to `/v1/queries/execute-sql`
- **THEN** the response reports an unknown relation

#### Scenario: An entity the caller cannot get is unknown

- **WHEN** the caller posts a statement naming a table it holds no read-side privilege on
- **THEN** the response is `400 bad_request` reporting an unknown relation, and does not reveal that
  the table exists

#### Scenario: A sensitive column cannot be translated without entitlement

- **WHEN** a caller holding `TABLE_GET` but not `TABLE_SENSITIVE` on the owning table posts a
  query referencing a sensitive column
- **THEN** the response is `400 bad_request` reporting an unknown field

### Requirement: Catalog metadata statements have no DSL translation

`POST /v1/queries/translate-sql` SHALL reject a statement against `INFORMATION_SCHEMA` with `400` and
the stable `bad_request` code, because a catalog query has no `StructuredQuery` equivalent: the DSL
addresses queryable entities, and the catalog relations are not entities.

The message SHALL say that catalog metadata statements cannot be translated, rather than reporting the
relation as an unknown entity — the relation does exist and is executable elsewhere, so "not found"
would send a caller looking for a typo.

This SHALL NOT weaken the endpoint's contract that a successful translation is executable: a statement
that translates continues to be one `POST /v1/queries/execute` accepts.

#### Scenario: Metadata statement is not translatable

- **WHEN** a client posts `SELECT TABLE_NAME FROM INFORMATION_SCHEMA."TABLES"` to `translate-sql`
- **THEN** the response is `400` with `error` `bad_request` and a message stating that catalog metadata
  statements have no structured-query equivalent

#### Scenario: Data statements are unaffected

- **WHEN** a client posts a statement against a queryable entity, qualified or bare
- **THEN** it translates as before

### Requirement: Arithmetic renders as operators and round-trips

`POST /v1/queries/translate` SHALL render the arithmetic catalog functions `plus`, `minus`, `multiply`,
`div` and `modulo` as the **infix operators** `+`, `-`, `*`, `/` and `%` rather than as function calls,
with
parentheses inserted wherever the expression tree's nesting differs from standard SQL precedence. The
rendered statement SHALL be within the subset `POST /v1/queries/execute-sql` accepts and SHALL
translate back to a structured query semantically equivalent to the input.

Operator rendering is chosen over the function spelling because the rendered SQL's audience is external
BI clients and generated-SQL consumers, for which the operator form is the portable one. The round-trip
is what keeps the choice safe: `sql-query-translation` lowers each operator back to the same catalog
function, so `div` → `/` → `div` and `modulo` → `%` → `modulo` are stable, and a translated statement
re-submitted to `/translate-sql` SHALL yield the original DSL. `modulo` SHALL NOT render as
`MOD(a, b)`: the subset accepts that spelling inbound, but rendering one canonical form per operation
keeps the round-trip single-valued.

Rendering the operator is also the only option: the arithmetic catalog names are not SQL functions at
all (see `sql-query-translation`), so a rendering that emitted `minus(a, b)` would produce a statement
the subset cannot read back.

`round`, `floor` and `ceil` have no operator form and SHALL render as lowercase function calls, as
every other catalog function does.

#### Scenario: Division renders as an operator

- **WHEN** `POST /v1/queries/translate` is given an aggregate query selecting
  `div(sum(cached_prompt_tokens), sum(prompt_tokens))` aliased `rate`
- **THEN** the rendered SQL carries the division as an infix `/` between the two `sum` calls aliased
  `rate`, not a `div(...)` call

#### Scenario: Arithmetic round-trips through both endpoints

- **WHEN** a DSL query using `div`, `plus` and `round` is translated to SQL and that SQL is submitted to
  `POST /v1/queries/translate-sql`
- **THEN** the returned DSL is semantically equivalent to the input, each operator having lowered back
  to its catalog function

#### Scenario: Precedence is preserved with parentheses

- **WHEN** a DSL query selects `multiply(plus(a, b), c)`
- **THEN** the rendered SQL parenthesises the addition so that re-translation reproduces the same
  expression tree rather than the precedence-default grouping

#### Scenario: Remainder renders as the percent operator

- **WHEN** a DSL query selects `modulo(a, 3)`
- **THEN** the rendered SQL carries the remainder as an infix `%`, not a `modulo(...)` or `MOD(...)` call

#### Scenario: Rounding renders as a function call

- **WHEN** a DSL query selects `round(div(sum(x), count()), 2)`
- **THEN** the rendered SQL carries a lowercase `round` call whose first argument is the infix division
  and whose second is the integer literal

### Requirement: Map and array accessors render and round-trip

`POST /v1/queries/translate` SHALL render the map accessors `map_value`, `map_contains` and `map_keys`
and the array accessors `array_has`, `array_has_any`, `array_has_all` and `array_length` as lowercase
function calls over the double-quoted field name, each within the subset
`POST /v1/queries/execute-sql` accepts.

The projection-only carve-outs SHALL be rendered on the same terms the SQL subset accepts them: a
`map` field SHALL render as a map accessor's first argument, and an `array` field as argument 0 of any
**array-operand function** — the accessors, `array_join`, `array_to_string` and `array_slice` — while a
DSL query using either field in any other
position SHALL continue to fail closed with HTTP 400 and `error` `bad_request`, naming the field and
the projection-only restriction. Both directions SHALL agree on the carve-out's position and function
set, or the round-trip breaks. `array_join`'s own rendering is stated by "Array expansion renders and
round-trips"; `array_to_string`'s and `array_slice`'s by "Text composition renders and round-trips".

`map_keys` and `split_string` SHALL render as lowercase function calls, and their array results SHALL
be subject to the same projection-only rendering rule as `json_extract_array`'s: a DSL query using one
outside a plain projection — other than as an array-operand function's argument 0 — SHALL fail
closed rather than render a statement the SQL subset would reject. A nested
`array_length(map_keys(m))` SHALL therefore render, and SHALL round-trip, and so SHALL
`array_join(split_string(t, ','))` and `array_to_string(map_keys(m), ', ')`.

#### Scenario: Map accessor renders over a map field

- **WHEN** `POST /v1/queries/translate` is given an aggregate query grouping by
  `map_value(point_attributes, 'model')`
- **THEN** the rendered SQL selects and groups by that call over the quoted field name, and the map
  field is not refused

#### Scenario: Map field outside the carve-out still fails closed

- **WHEN** a DSL query sorts on, groups by, or filters on a bare `map` field
- **THEN** the request is refused with HTTP 400 and `error` `bad_request`, naming the field and the
  projection-only restriction

#### Scenario: Array predicate renders over an array field

- **WHEN** a DSL query filters on `array_has` over an `array` enrichment field
- **THEN** the rendered SQL carries that call over the quoted flat field name in its `WHERE` clause

#### Scenario: Accessors round-trip

- **WHEN** a DSL query using `map_value`, `map_contains`, `map_keys`, `array_has` and `array_length` is
  translated to SQL and that SQL is submitted to `POST /v1/queries/translate-sql`
- **THEN** the returned DSL is semantically equivalent to the input

### Requirement: String time literals normalize on the way back to DSL

`POST /v1/queries/translate-sql` SHALL accept the ISO-8601 string spellings that
`sql-query-translation` coerces against a `timestamp` column, and SHALL return a DSL `value` expression
carrying `value_type` `timestamp` with the epoch-millis encoding — never a `string` literal that the
execute path would then reject. Re-rendering that DSL through `POST /v1/queries/translate` SHALL emit
the canonical `TIMESTAMP` literal form, so the round-trip normalizes a client's loose spelling into the
subset's explicit one rather than preserving it.

#### Scenario: Loose date string normalizes to a timestamp value

- **WHEN** `POST /v1/queries/translate-sql` is given a filter comparing a timestamp column to the bare
  string `'2026-08-20'`
- **THEN** the returned DSL carries a `value` expression of `value_type` `timestamp` encoding midnight
  UTC of that date

#### Scenario: Re-rendering emits the canonical timestamp literal

- **WHEN** that returned DSL is submitted to `POST /v1/queries/translate`
- **THEN** the rendered SQL carries the explicit `TIMESTAMP` literal form rather than the bare string

### Requirement: CASE and window expressions render and round-trip

`POST /v1/queries/translate` SHALL render a `case` expression as `CASE WHEN <predicate> THEN <expr> […]
[ELSE <expr>] END`, with each `when` rendered by the filter grammar's SQL rendering, and a `window`
expression as `<fn>(<args>) OVER (PARTITION BY … ORDER BY … [ROWS|RANGE BETWEEN … AND …])`, omitting each
absent part. Both renderings SHALL be accepted by `POST /v1/queries/translate-sql` and SHALL translate
back to an equal DSL expression, so the two endpoints stay inverse for these kinds as they are for every
other. A DSL document a `window` or `case` expression makes invalid for the execute path (a window in
`filter`, an empty `case`) SHALL be rejected by `translate` with the same 400 the execute path gives.

#### Scenario: Case renders and round-trips
- **WHEN** a client posts a row-mode query selecting `{"type":"case","when":[{"when":{"op":"gt","args":[<total_tokens>,<1000>]},"then":<'large'>}],"else":<'small'>}` aliased `size` to `POST /v1/queries/translate`
- **THEN** the SQL contains `CASE WHEN "total_tokens" > 1000 THEN 'large' ELSE 'small' END AS "size"`, and posting that SQL to `POST /v1/queries/translate-sql` yields an equal `case` select entry

#### Scenario: Window renders and round-trips
- **WHEN** a client posts a row-mode query selecting a `window` whose `fn` is `row_number`, partitioned by `project_id` and ordered by `request_time` descending, aliased `rn`
- **THEN** the SQL contains `row_number() OVER (PARTITION BY "project_id" ORDER BY "request_time" DESC) AS "rn"`, and posting it to `POST /v1/queries/translate-sql` yields an equal `window` select entry

#### Scenario: Frame renders
- **WHEN** a `window` expression carries a `rows` frame from `unbounded_preceding` to `current_row`
- **THEN** the rendered `OVER` clause ends with `ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW`

### Requirement: Array expansion renders and round-trips

`POST /v1/queries/translate` SHALL render an `array_join` call as the lowercase single-operand
function `array_join(...)` over its rendered operand, within the subset
`POST /v1/queries/execute-sql` accepts. An `array` field SHALL render as `array_join`'s operand on the
same terms it renders as an array accessor's first argument, and an array-returning call (`map_keys`,
`split_string`, `json_extract_array`) SHALL render nested there.

Because the result is a scalar, a rendered `array_join` projection SHALL be usable as the statement's
`GROUP BY` key through its alias, and the rendered statement SHALL therefore carry the alias in both
the select list and the `GROUP BY` clause for an aggregate-mode query grouping by it.

An `array_join` call SHALL render wherever the DSL accepts one, including in a rendered `WHERE` or
`HAVING` clause and inside an aggregate's arguments — the renderer SHALL NOT refuse positions the DSL
and the SQL subset both accept.

#### Scenario: Expansion renders over an array field

- **WHEN** `POST /v1/queries/translate` is given a row-mode query selecting `array_join(topics)`
  aliased `topic`
- **THEN** the rendered SQL selects `array_join("topics") AS "topic"` from the quoted entity name

#### Scenario: Grouping by an expanded element renders

- **WHEN** an aggregate-mode query selects `array_join(topics)` aliased `topic` plus `count()` aliased
  `requests`, grouped by `topic`
- **THEN** the rendered SQL selects both entries and groups by `"topic"`

#### Scenario: Expansion round-trips

- **WHEN** a DSL query using `array_join` — once over a plain `array` field, once nested over
  `split_string`, and once as a filter operand — is translated to SQL and that SQL is submitted to
  `POST /v1/queries/translate-sql`
- **THEN** the returned DSL is semantically equivalent to the input

### Requirement: Text composition renders and round-trips

`POST /v1/queries/translate` SHALL render `concat`, `to_string` and `array_to_string` as lowercase
function calls over their rendered operands, and `array_slice` as a lowercase call over its operand
and its two integer literals — each within the subset `POST /v1/queries/execute-sql` accepts. The
renderer SHALL emit the **catalog spelling**, never the `||` operator and never a `CAST`: those are
inbound spellings the SQL front end lowers onto `concat` and `to_string`, so one operation has one
rendering and the round-trip stays single-valued.

Because `concat`, `to_string` and `array_to_string` return ordinary text, a rendered call SHALL be
usable as the statement's `GROUP BY` key, `ORDER BY` key or predicate operand wherever the DSL accepts
one. `array_slice` returns an array, so its rendered call SHALL be subject to the same projection-only
rendering rule as `map_keys` and `split_string`: a DSL query using one outside a plain projection —
other than as an array-operand function's argument 0 — SHALL fail closed with HTTP 400 and `error`
`bad_request` rather than render a statement the SQL subset would reject.

A DSL query using any of the four SHALL round-trip: translated to SQL and submitted to
`POST /v1/queries/translate-sql`, the returned DSL SHALL be semantically equivalent to the input.

#### Scenario: A concatenated label renders

- **WHEN** `POST /v1/queries/translate` is given a row-mode query selecting
  `concat(parent_deployment, ' -> ', deployment)` aliased `edge`
- **THEN** the rendered SQL selects `concat("parent_deployment", ' -> ', "deployment") AS "edge"` from
  the quoted entity name

#### Scenario: A rendered cell over a group's values renders

- **WHEN** an aggregate-mode query selects `array_to_string(array_slice(group_uniq_array(deployment), 1, 6), ', ')`
  aliased `models` and groups by `chat_id`
- **THEN** the rendered SQL nests the three lowercase calls in that order, with the bounds as integer
  literals

#### Scenario: The text functions round-trip

- **WHEN** a DSL query using `concat`, `to_string`, `array_to_string` and `array_slice` is translated to
  SQL and that SQL is submitted to `POST /v1/queries/translate-sql`
- **THEN** the returned DSL is semantically equivalent to the input, and no `||` or `CAST` appears in
  the rendered SQL

#### Scenario: A rendered label is groupable in the rendered SQL

- **WHEN** an aggregate query groups by the alias of a `concat` projection
- **THEN** the rendered SQL groups by that output name and is accepted by `POST /v1/queries/execute-sql`

#### Scenario: A slice outside a projection fails closed

- **WHEN** a DSL query sorts on or groups by the alias of an `array_slice` projection
- **THEN** the request is refused with HTTP 400 and `error` `bad_request`, naming the function and the
  projection-only restriction

### Requirement: Translation carries FINAL

The translation endpoints SHALL carry whether a query reads its entity final, in both directions, so that
the translated query reads exactly what the input reads once `query.force-final` is off (see
[structured-query](../structured-query/spec.md) "Read result deduplication"). Translation SHALL follow
what the query says, never what `query.force-final` would force on execution, so its output does not
change when the flag is turned off.

- **DSL → SQL** (`POST /v1/queries/translate`): the entity reference SHALL render with `FINAL` when the
  document's `final` is `true`, or when `final` is absent and the entity is final-capable (its source is
  `upsert_by_key`, or it has an enrichment); it SHALL render without `FINAL` when `final` is `false`, or
  when `final` is absent and the entity is not final-capable.
- **SQL → DSL** (`POST /v1/queries/translate-sql`): a statement whose entity reference carries `FINAL`
  SHALL translate to `"final": true`, and one without it to `"final": false`. `final` SHALL always be
  emitted, never left absent, because an absent `final` reads a final-capable entity final and would turn
  a raw SQL read into a final one.

Both endpoints SHALL apply the `illegal_final` rejection execution applies — `"final": true` or `FINAL` on
an `append` source with no enrichment is HTTP 400, `error` = `illegal_final` — so a query that translates is
one that executes.

#### Scenario: A DSL query omitting final over a collapsing source renders FINAL

- **WHEN** a client posts to `translate` a query over `dial_usage_log` without `final`
- **THEN** the returned SQL reads `… FROM "dial_usage_log" FINAL …`

#### Scenario: A DSL query omitting final over an append source renders no FINAL

- **WHEN** a client posts to `translate` a query over an `append` source with no enrichment, without `final`
- **THEN** the returned SQL carries no `FINAL`

#### Scenario: final false renders no FINAL

- **WHEN** a client posts to `translate` a query over `dial_usage_log` with `"final": false`
- **THEN** the returned SQL carries no `FINAL`, with `query.force-final` on or off

#### Scenario: SQL without FINAL translates to an explicit false

- **WHEN** a client posts `SELECT trace_id FROM dial_usage_log` to `translate-sql`
- **THEN** the returned document carries `"final": false`

#### Scenario: SQL with FINAL translates to true

- **WHEN** a client posts `SELECT trace_id FROM dial_usage_log FINAL` to `translate-sql`
- **THEN** the returned document carries `"final": true`

#### Scenario: The FINAL mark round-trips

- **WHEN** a DSL document with `"final": true` or `"final": false` is translated to SQL and the SQL
  translated back
- **THEN** the resulting document carries the same `final` value as the original

#### Scenario: Translation rejects FINAL on an append source

- **WHEN** a client posts to `translate` a query with `"final": true` over an `append` source with no
  enrichment, or to `translate-sql` a statement writing `FINAL` after one
- **THEN** the response is HTTP 400, `error` = `illegal_final`

## Implementation notes

- Endpoints: `web.controller.query.QueryTranslationController` (`/v1/queries/translate`,
  `/v1/queries/translate-sql`); response DTOs `SqlTranslationResponseDto` (`{query}`) and
  `StructuredQueryTranslationResponseDto` (`{sql}`).
- SQL→DSL reuses `SqlQueryFrontEnd` verbatim (no execution); the `StructuredQuery` model
  serializes back to the canonical wire shape (round-trip pinned by
  `StructuredQueryDeserializationTest`).
- DSL→SQL: `service.query.QueryTranslationService` resolves the entity via `CatalogQueryService`,
  validates by running `StructuredQueryBuilder.build(…)` on the connection-less render context
  (result discarded), then renders via `service.query.sql.StructuredQueryToSqlRenderer`
  (always-double-quoted identifiers, execution-normalized literals, percentile
  `WITHIN GROUP` form, DISTINCT quantifier only on `count`/`sum`/`avg`).
- Errors reuse `QueryValidationException`/`SqlTranslationException` → 400 `bad_request` in
  `DefaultExceptionHandler`; no new codes.
- Coverage: `StructuredQueryToSqlRendererTest` (exact rendering, inexpressible rejections, and
  render→`SqlQueryFrontEnd.translate` round-trips), `QueryTranslationServiceTest`,
  `QueryTranslationControllerTest`.
