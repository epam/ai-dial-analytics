## ADDED Requirements

### Requirement: An aggregate declaration cannot read a computed field

The computed fields `dial_usage_log_payload.request_body_full` and `dial_usage_log_payload.request_body_rebuild` (see
the data-sync capability, "A rebuilt request body is readable beside the stored one") are computed after a read's
statement has run, so no aggregate statement can compute them. An aggregate declaration — form or SQL — naming one in
`group_by[].column`, `group_by[].trunc.col`, `measures[].column`, `filter`, `measures[].where` or anywhere in an SQL
declaration SHALL be rejected with HTTP 422 `pipeline_validation_failed`, naming the field and stating that it can
only be projected by a query or bound as a transform input. The rejection SHALL apply on create, patch, preview and
import alike: it is a check by name made on every declaration write, so a registration, which always stores an
aggregate pipeline disabled, is refused rather than stored for the arming gate to refuse later. A name inside a
string literal or comment is not a reference.

#### Scenario: Grouping by the rebuild status is refused

- **WHEN** an aggregate declaration over `dial_usage_log` groups by `dial_usage_log_payload.request_body_rebuild`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the field

#### Scenario: An SQL declaration measuring the rebuilt body is refused

- **WHEN** an SQL aggregate declaration selects `max(length("dial_usage_log_payload.request_body_full"))`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the field

#### Scenario: The registration itself is refused

- **WHEN** `POST /v1/pipelines` registers an aggregate declaration whose `filter` reads
  `"dial_usage_log_payload.request_body_rebuild" = 'rebuilt'`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the field, and no pipeline is
  stored
