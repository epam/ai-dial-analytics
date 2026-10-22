## MODIFIED Requirements

### Requirement: Execution reuses the structured-query pipeline and governance

Every REST SQL query **against queryable entities** SHALL execute through the shared SQL front end and
the same execution path as `POST /v1/queries/execute`: a single-block statement by translating to a
`StructuredQuery`, a composite statement (one that joins, reads a derived table or CTE, or carries a
subquery — see the sql-query-translation contract) by executing the composite plan the front end produces,
whose leaves are single-entity blocks rendered by that same path. All structured-query governance SHALL
apply unchanged: entity and field validation against the discovered catalog schema in every block, the
closed function catalog, enrichment join rules per entity, sensitive-column masking, `final`
deduplication, the query budget, the default limit of 100, and the maximum limit of 1000 rows. The default
and maximum limit govern the **outermost** block only: a SQL `LIMIT` on the outer statement above the
maximum SHALL be rejected with HTTP 400 naming the requested limit and the maximum — never silently
clamped — and an outer statement with no `LIMIT` SHALL receive the default limit, while an inner block's
`LIMIT`/`OFFSET` is executed as written and an inner block without one is not defaulted. Unsupported SQL
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

- **WHEN** a client posts SQL containing a construct outside the supported subset (e.g. a `UNION` or a
  correlated subquery)
- **THEN** the response is HTTP 400 identifying the unsupported construct, and ClickHouse is
  not contacted

#### Scenario: Composite statement executes under the outer governance

- **WHEN** a client posts `{ "sql": "SELECT a.project_id, count(*) AS n FROM dial_usage_log a JOIN rate_analytics b ON a.response_id = b.response_id GROUP BY a.project_id" }`
- **THEN** the response is HTTP 200 with `{"rows": [...]}` bounded by the default limit of 100, and the
  statement ran under the same `final` and `query.budget.*` settings as a single-entity statement

#### Scenario: Inner limit is not governed

- **WHEN** a client posts `{ "sql": "SELECT count(*) FROM (SELECT event_id FROM dial_usage_log LIMIT 5000) t" }`
- **THEN** the response is HTTP 200 — the inner `LIMIT 5000` is executed as written and only the outer
  result is subject to the 1000-row maximum
