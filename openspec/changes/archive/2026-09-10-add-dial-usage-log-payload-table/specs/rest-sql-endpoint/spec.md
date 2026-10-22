## MODIFIED Requirements

### Requirement: SQL execution inherits the query budget

The SQL endpoint SHALL execute its translated query under the same resource budget as the structured
query surface (see [structured-query](../structured-query/spec.md), "Query execution runs under a
resource budget"), and SHALL report a refusal with the same status and `error` code. The endpoint
translates SQL into the structured query and runs it through the same governed pipeline, so a ceiling
that bound only the DSL surface would leave the identical statement unbounded when written as SQL.

#### Scenario: A SQL query exceeding a ceiling is refused

- **WHEN** a caller posts a `SELECT` whose execution exceeds the configured execution-time or memory
  ceiling
- **THEN** the response is HTTP 422 with `error` `query_budget_exceeded`, carrying the same message
  the DSL surface returns for that ceiling

#### Scenario: Selecting a heavy body column over an unbounded scan is refused

- **WHEN** a `FULL_ADMIN` caller posts
  `{ "sql": "SELECT event_id, \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log WHERE chat_id = '…'" }`,
  naming a `heavy` column with no predicate that prunes the partitions of either the source or the
  payload table it joins
- **THEN** the caller receives `query_budget_exceeded` naming the ceiling, rather than the connection
  failing with no response

#### Scenario: The same query inside a bound succeeds

- **WHEN** the same statement adds a `request_time` range that prunes to a small set of partitions
- **THEN** the query completes inside the budget and returns its rows, with the heavy column projected
  in full

### Requirement: Star projections omit heavy and enrichment columns

`POST /v1/queries/execute-sql` SHALL inherit the star expansion of the shared SQL front end (see the
`sql-query-translation` capability's "Star projection expands to the non-heavy source columns"): a
`SELECT *` response SHALL carry a row key per **non-heavy source** column and none for a `heavy` column
or an enrichment column (**BREAKING** — a star previously returned enrichment columns too), while a
statement that names a heavy or enrichment column SHALL return it unchanged. A star whose expansion
would be empty SHALL be rejected with HTTP 400 `bad_request` in the `ErrorView` shape, before any
ClickHouse contact.

The equivalence with the DSL endpoint SHALL hold for every entity, enrichments attached or not:
`SELECT *` and a row-mode document with no `select` SHALL return the same columns.

The two exclusions are independent and either alone suffices. On `dial_usage_log` the seeded heavy
columns are now enrichment columns as well, so a star omits them under either rule; the entity's
`object`-typed claim and header columns are source columns and are still expanded.

#### Scenario: Star response omits the heavy column

- **WHEN** a client posts `{ "sql": "SELECT * FROM <entity> LIMIT 1" }` as a caller entitled to see a
  **source** column of that entity marked `heavy`
- **THEN** the response is HTTP 200 and no row carries that column's key, while every non-heavy
  source column key is present

#### Scenario: Star over dial_usage_log omits every body column

- **WHEN** a `FULL_ADMIN` client posts `{ "sql": "SELECT * FROM dial_usage_log LIMIT 1" }`
- **THEN** the response is HTTP 200 and no row carries a `request_body`, `response_body`,
  `assembled_response`, `jwt_claims` or `request_tags` key under any spelling, while
  `request_body_bytes` and `response_body_bytes` are both present

#### Scenario: Named heavy column comes back

- **WHEN** the same caller posts
  `{ "sql": "SELECT event_id, \"dial_usage_log_payload.response_body\" FROM dial_usage_log LIMIT 1" }`
- **THEN** the response carries both keys with their values

#### Scenario: Star and empty DSL select agree

- **WHEN** a client posts `SELECT * FROM <source>` for a source with an attached enrichment, and the
  equivalent row-mode document with no `select` to `POST /v1/queries/execute`
- **THEN** both responses carry the same column keys — the non-heavy source columns, with no
  enrichment column in either

#### Scenario: Enrichment column returned when named

- **WHEN** a client posts `{ "sql": "SELECT event_id, \"deployment_ref.display_name\" FROM dial_usage_log LIMIT 1" }`
- **THEN** the response carries the enrichment column under its qualified flat name, with a JSON `null`
  where no enrichment row matches

### Requirement: Validation warns about the shapes the query budget refuses

`POST /v1/queries/validate-sql` SHALL report advisory `warnings` for a statement that passes every check
and is nonetheless likely to be refused at execution, and for the default limit being applied. A warning
SHALL NOT affect `valid`. Warnings are heuristics on the translated query, not verdicts: the query budget
is enforced as the statement runs, so a warned statement MAY execute successfully and an unwarned one MAY
still breach the budget.

The endpoint SHALL warn when the statement carries no outer `LIMIT`, naming the default that will be
applied; when it references a `heavy` column with no filter on a `timestamp` field of the entity, so the
read is not pruned to a few partitions; and when it aggregates with a `GROUP BY` and no such filter,
since a high-cardinality grouping builds one accumulator per group. The heavy-column warning SHALL fire
for a heavy enrichment column named by its qualified flat name as it does for a heavy source column: the
flag is carried into the query model by the column, not by which table owns it. A composite statement
SHALL receive only the limit warning: its blocks reference relation columns rather than a single entity's
fields, so a heavy or timestamp column cannot be attributed to a catalog binding with certainty.

#### Scenario: An unbounded heavy-column read is warned about

- **WHEN** a client posts
  `{ "sql": "SELECT event_id, \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log WHERE chat_id = '…' LIMIT 10" }`
- **THEN** the response is `200` with `valid` true and a warning naming the heavy column and the missing
  time bound

#### Scenario: A time bound removes the warning

- **WHEN** the same statement adds a range filter on `request_time`
- **THEN** the response carries no heavy-column warning

#### Scenario: The default limit is reported as a warning

- **WHEN** a client posts a valid statement with no `LIMIT`
- **THEN** the response carries a warning naming the default limit, and a statement with an explicit
  `LIMIT` carries none

#### Scenario: A composite statement receives only the limit warning

- **WHEN** a client posts a valid join carrying no outer `LIMIT`
- **THEN** the only warning is the default-limit one
