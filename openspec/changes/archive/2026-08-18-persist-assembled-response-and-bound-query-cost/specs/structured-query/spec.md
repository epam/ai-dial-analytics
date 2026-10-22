## ADDED Requirements

### Requirement: Query execution runs under a resource budget

The system SHALL execute every structured query under an explicit, configured **resource budget** — a
wall-clock ceiling and a memory ceiling — applied as per-query ClickHouse server settings on the
executed statement, and SHALL refuse a query that exceeds either ceiling rather than letting it run
unbounded.

The budget is required because a query's cost is not bounded by anything the caller supplies. `limit`
bounds the rows returned, not the rows read: an entity's `heavy` columns are unbounded in size per
row, and a filter on a column that is not a prefix of the source's ordering key prunes nothing, so a
single small-result query can read the whole table's payload columns. Without a ceiling that read
continues after the caller's HTTP client has given up, so the cost is paid in full and the caller
learns nothing about why.

The budget SHALL bound the query's **cost**, never its **result**. The system SHALL NOT sample, narrow,
or silently truncate a query's input or output to stay inside the budget: a partial answer returned as
though it were complete is worse than a refusal, because the caller cannot tell the difference. A
query that cannot be completed within its budget SHALL be refused with an error naming the ceiling it
exceeded.

The budget SHALL apply to the query surface only — the paths that serve a caller-supplied query. The
enrichment scan, group-fetch, materialize, and aggregate-refresh paths share the executor but are
bounded by their own windows, page sizes, and limits; the dry-run preview has its own budget. A ceiling
imposed on those paths would make a legitimate large window fail as though it were malformed.

The wall-clock ceiling SHALL bound the **caller's request**, not each statement it runs. A query that
also asks for a total count runs a second statement, and that statement SHALL inherit what remains of the
ceiling rather than being granted a fresh one: a request able to occupy twice the configured ceiling puts
the caller past its own read timeout, which is the failure the budget exists to remove.

The ceilings SHALL be deployment-configurable, so an installation whose ordinary queries are heavier
than the defaults can raise them without a code change. A configured value that would leave a ceiling
bounding nothing — a spill threshold at or above the memory ceiling, or a wall-clock ceiling below the
granularity the store enforces — SHALL be refused at startup rather than accepted and silently ignored.

#### Scenario: An ordinary query is unaffected

- **WHEN** a query completes inside the configured ceilings
- **THEN** it returns its rows exactly as before, with no truncation, sampling, or added metadata

#### Scenario: A query exceeding the wall-clock ceiling is refused

- **WHEN** an executed query exceeds the configured execution-time ceiling
- **THEN** the response is HTTP 422 with `error` `query_budget_exceeded`, and the message names the
  ceiling that was exceeded and its configured value

#### Scenario: A query exceeding the memory ceiling is refused

- **WHEN** an executed query exceeds the configured memory ceiling
- **THEN** the response is HTTP 422 with `error` `query_budget_exceeded`, and the message names the
  ceiling that was exceeded and its configured value

#### Scenario: An unbounded heavy-column read is refused, not dropped

- **WHEN** a query projects a `heavy` column with no predicate that prunes the source's partitions, so
  the read exceeds a ceiling
- **THEN** the caller receives the `query_budget_exceeded` error rather than an empty or transport-level
  failure, and the statement is refused by ClickHouse rather than continuing to run

#### Scenario: A total count shares the request's ceiling

- **WHEN** a row-mode query requests a total count, so a second statement runs for the same request
- **THEN** that statement runs under what remains of the wall-clock ceiling, and the caller's total wait
  stays within the single configured ceiling

#### Scenario: A refusal is deterministic, not a transient failure

- **WHEN** a query is refused for exceeding its budget
- **THEN** the status is 422 rather than 503, because re-running the identical query is refused
  identically and a retrying client would loop

#### Scenario: An unrelated ClickHouse failure is not reported as a budget refusal

- **WHEN** the executed statement fails for a reason other than the budget's ceilings
- **THEN** the existing error mapping for that failure applies unchanged, and `query_budget_exceeded`
  is not returned

#### Scenario: The scan and refresh paths keep their own bounds

- **WHEN** an enrichment scan, group fetch, materialize, or aggregate refresh runs a statement through
  the same executor
- **THEN** it is not subject to the query budget, and its own window, page size, and limits govern it
