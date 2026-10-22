# flight-sql-endpoint (delta)

Limit governance change: a SQL `LIMIT` above the structured-query maximum is rejected with
`INVALID_ARGUMENT` instead of silently clamped.

## MODIFIED Requirements

### Requirement: Execution reuses structured-query governance

Every Flight query SHALL execute by constructing a `StructuredQuery` and invoking the same
execution path as `POST /v1/queries/execute`. All structured-query governance SHALL apply
unchanged: entity and field validation, the closed function catalog, enrichment join rules,
default limit 100, and the maximum limit of 1000 rows. A SQL `LIMIT` above the maximum SHALL be
rejected with an `INVALID_ARGUMENT` status naming the requested limit and the maximum — never
silently clamped. A SQL query with no `LIMIT` SHALL receive the default limit.

#### Scenario: Limit above the maximum rejected

- **WHEN** a client runs `SELECT request_id FROM dial_usage_log LIMIT 50000` via Flight
- **THEN** the call fails with `INVALID_ARGUMENT` and no rows are returned, matching REST
  behavior for `limit: 50000`

#### Scenario: Same rows as REST

- **WHEN** the same logical query is issued via Flight SQL and via `POST /v1/queries/execute`
- **THEN** both return the same rows and values (modulo JSON vs Arrow encoding)
