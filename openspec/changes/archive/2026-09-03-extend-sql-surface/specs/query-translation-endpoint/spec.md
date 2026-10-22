## ADDED Requirements

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

## MODIFIED Requirements

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

A **composite** statement — one that joins, reads a derived table or CTE, or carries a subquery — is
accepted and executed by `execute-sql` but has no `StructuredQuery` equivalent; this endpoint SHALL reject
it with 400 `bad_request` and the message `statements with joins, derived tables or subqueries execute but
have no structured-query equivalent`, without contacting ClickHouse. A single-block statement using `CASE`,
arithmetic or a window expression is not composite and SHALL translate.

#### Scenario: Aggregate SQL returns its DSL document

- **WHEN** a client posts `{"sql": "SELECT model, count(*) AS n FROM dial_usage_log GROUP BY model"}` to `/v1/queries/translate-sql`
- **THEN** the response is `200` with a `query` object having `entity="dial_usage_log"`, `mode="aggregate"`, `group_by=["model"]`, and a `count` fn select entry aliased `n` — and no query was executed

#### Scenario: Unsupported SQL rejected without execution

- **WHEN** a client posts `{"sql": "SELECT event_id FROM dial_usage_log UNION ALL SELECT response_id FROM rate_analytics"}`
- **THEN** the response is a 400 `ErrorView` with code `bad_request` naming the unsupported construct, and ClickHouse is not contacted

#### Scenario: Composite statement has no DSL document

- **WHEN** a client posts `{"sql": "SELECT a.x FROM t1 a JOIN t2 b ON a.id = b.id"}` over two queryable entities
- **THEN** the response is a 400 `ErrorView` with code `bad_request` and the composite-statement message, and ClickHouse is not contacted

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
