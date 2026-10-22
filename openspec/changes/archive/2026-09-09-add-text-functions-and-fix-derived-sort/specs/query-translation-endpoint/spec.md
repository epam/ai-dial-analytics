## ADDED Requirements

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

## MODIFIED Requirements

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

A **composite** statement — one that joins, reads a derived table or CTE, carries a subquery, or is nested
by the translator to carry an `ORDER BY` expression its `SELECT` list does not project — is accepted and
executed by `execute-sql` but has no `StructuredQuery` equivalent; this endpoint SHALL reject it with 400
`bad_request` and the message
`statements with joins, derived tables or subqueries — including a statement nested to carry an ORDER BY expression its SELECT list does not project — execute but have no structured-query equivalent`,
without contacting ClickHouse. A single-block statement using `CASE`, arithmetic or a window expression
is not composite and SHALL translate, and neither is one whose only computed sort key is already carried
by its projection.

#### Scenario: Aggregate SQL returns its DSL document

- **WHEN** a client posts `{"sql": "SELECT model, count(*) AS n FROM dial_usage_log GROUP BY model"}` to `/v1/queries/translate-sql`
- **THEN** the response is `200` with a `query` object having `entity="dial_usage_log"`, `mode="aggregate"`, `group_by=["model"]`, and a `count` fn select entry aliased `n` — and no query was executed

#### Scenario: Unsupported SQL rejected without execution

- **WHEN** a client posts `{"sql": "SELECT event_id FROM dial_usage_log UNION ALL SELECT response_id FROM rate_analytics"}`
- **THEN** the response is a 400 `ErrorView` with code `bad_request` naming the unsupported construct, and ClickHouse is not contacted

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
