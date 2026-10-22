## MODIFIED Requirements

### Requirement: Execution reuses the structured-query pipeline and governance

Every REST SQL query **against queryable entities** SHALL execute through the shared SQL front end and
the same execution path as `POST /v1/queries/execute`: a single-block statement by translating to a
`StructuredQuery`, a composite statement (one that joins, reads a derived table or CTE, carries a
subquery, or combines branches with a set operation — see the sql-query-translation contract) by executing the composite plan the front end produces,
whose leaves are single-entity blocks rendered by that same path. All structured-query governance SHALL
apply unchanged: entity and field validation against the discovered catalog schema in every block, the
closed function catalog, enrichment join rules per entity, sensitive-column masking, `final`
deduplication, the query budget, the default limit of 100, and the maximum limit of 1000 rows. The default
and maximum limit govern the **outermost** block only: a SQL `LIMIT` on the outer statement above the
maximum SHALL be rejected with HTTP 400 naming the requested limit and the maximum — never silently
clamped — and an outer statement with no `LIMIT` SHALL receive the default limit, while an inner block's
`LIMIT`/`OFFSET` is executed as written and an inner block without one is not defaulted. For a
**set-operation** statement the outermost block is the set operation's combined result, so the default and
the maximum bound the rows the branches produce together, while a branch's own `LIMIT` is an inner limit
and is executed as written. A `LIMIT` written after the last branch is a **branch** limit, not an outer
one, because that is where ClickHouse binds it (see the sql-query-translation set-operation requirement):
such a statement has stated no outer bound, so it receives the default limit rather than being governed by
the limit the caller wrote. Unsupported SQL
constructs SHALL be rejected before any ClickHouse contact, per the sql-query-translation contract.

A statement against `INFORMATION_SCHEMA` is the one exception, and it is scoped deliberately: it never
reaches ClickHouse, so none of the governance above has anything to govern. See "Catalog metadata
statements execute over REST" for what does apply. Every other statement — including one that only
looks like metadata, such as a `SELECT` over an entity named after a catalog relation — takes the path
above.

#### Scenario: Same rows as the DSL endpoint

- **WHEN** the same logical query is issued as SQL via `POST /v1/queries/execute-sql` and as a
  structured query via `POST /v1/queries/execute`
- **THEN** both return the same rows and values in the same JSON encoding

#### Scenario: Limit above the maximum rejected

- **WHEN** a client posts `{ "sql": "SELECT request_id FROM dial_usage_log LIMIT 50000" }`
- **THEN** the response is HTTP 400 with a message naming the requested limit and the 1000-row
  maximum, and no rows are returned

#### Scenario: Unsupported construct rejected before execution

- **WHEN** a client posts SQL containing a construct outside the supported subset (e.g. a correlated
  subquery or `WITH RECURSIVE`)
- **THEN** the response is HTTP 400 identifying the unsupported construct, and ClickHouse is
  not contacted

#### Scenario: Set-operation statement executes under the outer governance

- **WHEN** a client posts `{ "sql": "SELECT model FROM dial_usage_log UNION ALL SELECT model FROM rate_analytics" }`
- **THEN** the response is HTTP 200 with `{"rows": [...]}` bounded by the default limit of 100 counted
  over the combined result, and the statement ran under the same `final` and `query.budget.*` settings as
  a single-entity statement

#### Scenario: A branch limit is not governed

- **WHEN** a client posts `{ "sql": "(SELECT event_id FROM dial_usage_log LIMIT 5000) UNION ALL (SELECT response_id FROM rate_analytics LIMIT 5000)" }`
- **THEN** the response is HTTP 200 — each branch `LIMIT 5000` is executed as written, and only the
  combined result is subject to the 1000-row maximum

#### Scenario: A limit after the last branch is a branch limit

- **WHEN** a client posts `{ "sql": "SELECT event_id FROM dial_usage_log UNION ALL SELECT response_id FROM rate_analytics LIMIT 5000" }`
- **THEN** the response is HTTP 200 and **not** the over-maximum rejection: the `LIMIT 5000` binds to the
  last branch, as it does in ClickHouse, so the statement states no outer bound and the combined result
  takes the default limit of 100

#### Scenario: Composite statement executes under the outer governance

- **WHEN** a client posts `{ "sql": "SELECT a.project_id, count(*) AS n FROM dial_usage_log a JOIN rate_analytics b ON a.response_id = b.response_id GROUP BY a.project_id" }`
- **THEN** the response is HTTP 200 with `{"rows": [...]}` bounded by the default limit of 100, and the
  statement ran under the same `final` and `query.budget.*` settings as a single-entity statement

#### Scenario: Inner limit is not governed

- **WHEN** a client posts `{ "sql": "SELECT count(*) FROM (SELECT event_id FROM dial_usage_log LIMIT 5000) t" }`
- **THEN** the response is HTTP 200 — the inner `LIMIT 5000` is executed as written and only the outer
  result is subject to the 1000-row maximum

### Requirement: SQL validation endpoint agrees with execution without executing

`POST /v1/queries/validate-sql` SHALL report whether `POST /v1/queries/execute-sql` would accept a
statement, without executing it and without contacting ClickHouse. The request body SHALL be the same as
`execute-sql`'s — a JSON object with a single required `sql` field — and the endpoint SHALL be available
unconditionally, independent of `flight.enabled`.

The endpoint SHALL apply every check `execute-sql` applies before its ClickHouse round trip, over the
whole accepted surface: the statement screen (a single read-only `SELECT`), parse, star expansion,
entity and field validation against the discovered catalog schema, the closed function catalog,
translation to a `StructuredQuery` for a single-block statement or to a composite plan for one that
joins, reads a derived table or CTE, carries a subquery, or combines branches with a set operation, and
the row governance (default limit 100,
maximum limit 1000 on the outermost block). A statement reported valid SHALL therefore be one
`execute-sql` does not reject, and a statement reported invalid SHALL be one `execute-sql` rejects.
A statement against `INFORMATION_SCHEMA` SHALL be reported valid, since it is answered from ADAS's own
catalog and no governance applies to it.

Validity SHALL NOT be read as a promise that execution succeeds. The query budget
(`query.budget.*`) is enforced by ClickHouse as the statement runs and a breach is observable only then,
so a valid statement MAY still be refused at execution with `query_budget_exceeded`.

#### Scenario: A statement execute-sql accepts is reported valid

- **WHEN** a client posts `{ "sql": "SELECT event_kind FROM dial_usage_log LIMIT 10" }` to
  `/v1/queries/validate-sql`
- **THEN** the response is `200` with `valid` true and an empty `issues` array, and posting the same
  statement to `/v1/queries/execute-sql` returns `200`

#### Scenario: A composite statement is reported valid

- **WHEN** a client posts a statement joining two queryable entities with an equality in `ON`
- **THEN** the response is `200` with `valid` true — the composite surface `execute-sql` executes is
  validated, not only the DSL-expressible subset

#### Scenario: A set-operation statement is reported valid

- **WHEN** a client posts `{ "sql": "SELECT model FROM dial_usage_log UNION ALL SELECT model FROM rate_analytics" }`
- **THEN** the response is `200` with `valid` true and an empty `issues` array — superseding the
  verdict that reported it invalid with an unsupported-construct issue advising the branches be run as
  separate statements

#### Scenario: A set operation with mismatched branches is reported invalid

- **WHEN** a client posts `{ "sql": "SELECT model FROM dial_usage_log UNION ALL SELECT model, rate FROM rate_analytics" }`
- **THEN** the response is `200` with `valid` false and an issue naming the branch column-count
  mismatch, and posting the same statement to `/v1/queries/execute-sql` returns `400`

#### Scenario: A bare UNION is reported invalid

- **WHEN** a client posts `{ "sql": "SELECT model FROM dial_usage_log UNION SELECT model FROM rate_analytics" }`
- **THEN** the response is `200` with `valid` false and an issue stating that `UNION` requires `ALL` or
  `DISTINCT`, matching the rejection `/v1/queries/execute-sql` gives it

#### Scenario: An over-maximum limit is reported invalid

- **WHEN** a client posts `{ "sql": "SELECT event_kind FROM dial_usage_log LIMIT 50000" }`
- **THEN** the response is `200` with `valid` false and an issue naming the requested limit and the
  1000-row maximum, and posting the same statement to `/v1/queries/execute-sql` returns `400`

#### Scenario: A catalog metadata statement is reported valid

- **WHEN** a client posts `{ "sql": "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS" }`
- **THEN** the response is `200` with `valid` true and no `effective_limit`

#### Scenario: ClickHouse is never contacted

- **WHEN** a client posts any statement — valid, invalid, composite or metadata — to
  `/v1/queries/validate-sql`
- **THEN** no query is issued to ClickHouse, and the endpoint answers even while ClickHouse is
  unreachable
