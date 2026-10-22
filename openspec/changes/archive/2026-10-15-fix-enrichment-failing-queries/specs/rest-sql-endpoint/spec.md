## MODIFIED Requirements

### Requirement: SQL execution inherits the query budget

The SQL endpoint SHALL execute its translated query under the same resource budget as the structured
query surface (see [structured-query](../structured-query/spec.md), "Query execution runs under a
resource budget"), and SHALL report a refusal with the same status and `error` code. The endpoint
translates SQL into the structured query and runs it through the same governed pipeline, so a ceiling
that bound only the DSL surface would leave the identical statement unbounded when written as SQL. The
same pipeline also applies the structured query's read shaping (late materialization of projection-only
heavy enrichment columns, and the enrichment-conjunct and scalar-call pushdowns), so a SQL statement and
its DSL equivalent cost the same.

#### Scenario: A SQL query exceeding a ceiling is refused

- **WHEN** a caller posts a `SELECT` whose execution exceeds the configured execution-time or memory
  ceiling
- **THEN** the response is HTTP 422 with `error` `query_budget_exceeded`, carrying the same message
  the DSL surface returns for that ceiling

#### Scenario: Selecting a heavy body column over an unbounded scan is refused

- **WHEN** a `FULL_ADMIN` caller posts
  `{ "sql": "SELECT count(*) FROM dial_usage_log WHERE \"dial_usage_log_payload.assembled_response\" ILIKE '%error%'" }`,
  reading a `heavy` column that no returned page bounds, with no predicate that prunes the partitions of
  either the source or the payload table it joins
- **THEN** the caller receives `query_budget_exceeded` naming the ceiling, rather than the connection
  failing with no response

#### Scenario: A projection-only body over an unbounded scan is fetched for the page

- **WHEN** a `FULL_ADMIN` caller posts
  `{ "sql": "SELECT event_id, \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log WHERE chat_id = '…'" }`,
  which names the heavy column only in its projection and runs under the default page `LIMIT`
- **THEN** the body is read for the returned page's rows only, so the heavy column's cost does not grow with
  the unpruned scan; the scan of `dial_usage_log` that chooses the page still runs under the budget and is
  refused with `query_budget_exceeded` if it exceeds a ceiling

#### Scenario: The same query inside a bound succeeds

- **WHEN** the refused statement adds a `request_time` range that prunes to a small set of partitions
- **THEN** the query completes inside the budget and returns its count

#### Scenario: A self-join bounded on one side completes inside the budget

- **WHEN** a caller posts
  `SELECT b."usage_client_identity.user_email" AS parent_user, count(*) AS c FROM dial_usage_log a JOIN dial_usage_log b ON a.trace_id = b.trace_id AND a.core_parent_span_id = b.core_span_id WHERE a.request_time >= TIMESTAMP '2026-10-13 00:00:00' AND a.request_time < TIMESTAMP '2026-10-14 00:00:00' GROUP BY b."usage_client_identity.user_email" ORDER BY c DESC LIMIT 20`,
  where only the child side `a` is time-bounded
- **THEN** `a`'s window restricts `a`'s operand, `b` is reduced to the trace ids `a` can produce, and `b`'s
  enrichment join is keyed by that reduction (see structured-query, "A composite join's operand-only conjuncts
  restrict that operand" and "An equi-join operand is reduced to the keys its restricted partner can produce")
- **AND** the statement completes inside the budget with the rows the unreduced statement returns
