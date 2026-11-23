## MODIFIED Requirements

### Requirement: A saved query is not a template

A saved query SHALL be a complete query, not a parameterized template. The system SHALL NOT accept or
store declared parameters, and SHALL reject a body containing a runtime parameter expression with `422`
and machine code `validation_error`. The rejection SHALL be raised by this capability's own validation
before translation, so the caller receives a message about parameters being unsupported for saved
queries rather than an internal translator message.

This follows from execution: the query translator rejects every parameter expression other than a caller value, so a
parameterized body could not be executed on any endpoint and storing one would persist a query that can never run.

A **caller value** (see [structured-query](../structured-query/spec.md), "Caller values in a query filter") is not
such a parameter: no client binds it, and every query surface resolves it for the caller executing the body. A body
holding one — the `param` `caller.user_hash` or `caller.project` in a `query`-kind body, `caller.user_hash()` or
`caller.project()` in a `sql`-kind body — SHALL be accepted and stored unchanged, never with a resolved value, so each
caller who runs it receives the rows of its own caller values. Where a caller value sits is checked by the translation
"Saved query body validation" performs, and a misplaced one SHALL be reported as that translation reports it,
`400 bad_request`. A `param` with any other name SHALL still be rejected `422 validation_error`.

A saved query SHALL NOT execute itself. Execution remains on `POST /v1/queries/execute` and
`POST /v1/queries/execute-sql`, to which the client posts the stored body unchanged.

#### Scenario: A parameter expression in a structured body is rejected

- **WHEN** a create or replace request's `query` body contains a runtime parameter expression other than a caller
  value
- **THEN** the response is `422` with machine code `validation_error` and nothing is persisted

#### Scenario: A caller value in a structured body is stored and resolves per runner

- **WHEN** a caller creates a saved query whose `query` body filters
  `{"op": "eq", "args": [{"type": "field", "name": "user_hash"}, {"type": "param", "name": "caller.user_hash"}]}`
- **THEN** the response is `201`, reading the saved query back returns the `param` unchanged, and two callers who
  post the stored body to `POST /v1/queries/execute` each receive only their own rows

#### Scenario: A caller value in a SQL body is stored as written

- **WHEN** a caller creates a saved query whose `sql` body is
  `SELECT * FROM "dial_usage_log" WHERE "user_hash" = caller.user_hash()`
- **THEN** the response is `201` and the stored `sql` is returned unchanged

#### Scenario: A misplaced caller value is rejected as translation rejects it

- **WHEN** a create request's `query` body uses the `param` `caller.user_hash` as a `select` expression
- **THEN** the response is `400` with machine code `bad_request` and nothing is persisted

#### Scenario: A parameter that is not a caller value is still rejected

- **WHEN** a create request's `query` body contains `{"type": "param", "name": "caller.email"}`
- **THEN** the response is `422` with machine code `validation_error` and nothing is persisted

#### Scenario: A call that is not a caller value in a SQL body is an unknown function

- **WHEN** a create request's `sql` body filters `"user_hash" = caller.email()`
- **THEN** the response is `400` with machine code `bad_request`, as `POST /v1/queries/execute-sql` rejects the same
  statement, and nothing is persisted

#### Scenario: Declared parameters are not an accepted field

- **WHEN** a create request supplies a `params` field
- **THEN** the response is `422` with machine code `validation_error`

#### Scenario: A stored body is accepted by the query surface unchanged

- **WHEN** a caller reads a saved query and posts its stored `query` body to the query surface with no
  substitution step
- **THEN** the query surface accepts it — the body is executable exactly as stored

#### Scenario: There is no execute endpoint for a saved query

- **WHEN** a caller posts to a saved query's id with an execute path segment
- **THEN** the response is `404` with machine code `not_found` — the capability exposes no execution
  endpoint

