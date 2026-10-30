## ADDED Requirements

### Requirement: Flight streams the completed value of a computed field

A Flight SQL statement SHALL accept and refuse the computed fields `"dial_usage_log_payload.request_body_full"` and
`"dial_usage_log_payload.request_body_rebuild"` exactly as `POST /v1/queries/execute-sql` does (see the
sql-query-translation capability, "Computed fields are projection-only in SQL"); a refusal SHALL map to the gRPC
status the endpoint maps every translation refusal to. `getFlightInfo` SHALL advertise both as nullable `Utf8`, and
the `doGet` stream SHALL deliver the completed value the JSON endpoint returns for the same statement — the rebuilt
body's JSON text and the status string — and an Arrow null where that value is `NULL`.

The rebuild SHALL run under the bounds of the data-sync capability ("A rebuilt request body is readable beside the
stored one") for the whole statement, whose rows may number up to `flight.max-rows`: a statement whose chains exceed
those bounds SHALL stream the rows it could not complete as `partial`, never fail for that reason, and SHALL NOT
raise its own bounds.

#### Scenario: The rebuilt body streams as Utf8

- **WHEN** a client runs `SELECT trace_id, "dial_usage_log_payload.request_body_full",
  "dial_usage_log_payload.request_body_rebuild" FROM dial_usage_log WHERE trace_id = 'trace-0001' LIMIT 1` via
  Flight SQL
- **THEN** getFlightInfo advertises both fields as `Utf8`, and the stream's cells equal the values
  `/v1/queries/execute-sql` returns for the same statement

#### Scenario: A large stream degrades to partial rows

- **WHEN** a Flight statement projects `"dial_usage_log_payload.request_body_full"` over more delta rows than
  `QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS` lets one read resolve
- **THEN** the stream completes, the rows whose chains were resolved carry `rebuilt`, and the rest carry their stored
  body and `partial`
