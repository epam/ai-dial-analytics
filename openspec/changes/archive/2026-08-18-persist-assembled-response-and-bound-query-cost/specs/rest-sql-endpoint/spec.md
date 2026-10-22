## ADDED Requirements

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
  `{ "sql": "SELECT event_id, assembled_response FROM dial_usage_log WHERE chat_id = '…'" }`, naming a
  `heavy` column with no predicate that prunes the source's partitions
- **THEN** the caller receives `query_budget_exceeded` naming the ceiling, rather than the connection
  failing with no response

#### Scenario: The same query inside a bound succeeds

- **WHEN** the same statement adds a `request_time` range that prunes to a small set of partitions
- **THEN** the query completes inside the budget and returns its rows, with the heavy column projected
  in full
