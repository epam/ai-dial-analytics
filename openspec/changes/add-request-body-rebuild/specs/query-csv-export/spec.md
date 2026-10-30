## ADDED Requirements

### Requirement: CSV cells of a computed field carry its completed value

`POST /v1/queries/execute-csv` and `POST /v1/queries/execute-sql-csv` SHALL accept a projection of the computed
fields `dial_usage_log_payload.request_body_full` and `dial_usage_log_payload.request_body_rebuild` exactly where
their JSON counterparts accept it, and SHALL refuse every other use with the same HTTP 400 `bad_request` `ErrorView`
(see the structured-query capability, "Computed fields are projection-only and completed after the statement
runs"). Each cell SHALL carry the completed value the JSON counterpart's row carries — the rebuilt body's JSON text,
quoted per the dialect rules, and the status as its string value — never the stored body in its place and never an
internal helper column.

#### Scenario: A CSV export of rebuilt bodies

- **WHEN** a client posts to `/v1/queries/execute-csv` a row-mode query selecting `trace_id` and
  `dial_usage_log_payload.request_body_full` for rows stored in delta form
- **THEN** the header is `trace_id,dial_usage_log_payload.request_body_full`, and each record's second field is the
  JSON text `/v1/queries/execute` returns for that row

#### Scenario: A misuse is refused before any CSV is produced

- **WHEN** a client posts to `/v1/queries/execute-sql-csv` a statement ordering by
  `"dial_usage_log_payload.request_body_full"`
- **THEN** the response is HTTP 400 `bad_request` `ErrorView`, and no CSV is produced
