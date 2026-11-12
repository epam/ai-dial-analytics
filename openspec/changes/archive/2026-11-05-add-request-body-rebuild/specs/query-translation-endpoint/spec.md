## ADDED Requirements

### Requirement: Computed fields translate by name and keep the projection-only rule

Both translation endpoints SHALL treat the computed fields `dial_usage_log_payload.request_body_full` and
`dial_usage_log_payload.request_body_rebuild` as ordinary fields of `dial_usage_log` for a caller who may see them:
`POST /v1/queries/translate` SHALL render a plain projection of one as its double-quoted flat name, and `POST
/v1/queries/translate-sql` SHALL translate a plain select item naming one to a DSL field projection, so the round
trip holds. Every use the execution surfaces refuse (see the structured-query capability, "Computed fields are
projection-only and completed after the statement runs", and the sql-query-translation capability, "Computed fields
are projection-only in SQL") SHALL be refused here with the same HTTP 400 `bad_request` naming the field and the
projection-only restriction. Neither endpoint SHALL compute a value or contact ClickHouse for them.

#### Scenario: A projection round-trips

- **WHEN** `POST /v1/queries/translate` is given a row-mode query selecting `dial_usage_log_payload.request_body_full`
- **THEN** the rendered SQL selects `"dial_usage_log_payload.request_body_full"`, and `POST
  /v1/queries/translate-sql` on that SQL returns a query projecting the same field

#### Scenario: A computed field as a sort key is refused by translation

- **WHEN** `POST /v1/queries/translate` is given a query sorting by `dial_usage_log_payload.request_body_rebuild`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field and the projection-only restriction
