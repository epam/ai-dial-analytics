## ADDED Requirements

### Requirement: Discovery flags projection-only fields

Every field of `GET /v1/queries/entities/schema/{name}` SHALL carry a boolean `projection_only`. It SHALL be `true`
for a computed field — `dial_usage_log_payload.request_body_full` and `dial_usage_log_payload.request_body_rebuild`
(see the table-management capability, "Seeded payload enrichment on dial_usage_log") — and `false` for every field
backed by a catalog column. A `projection_only` field's `source` SHALL be its own bare name, since no physical column
backs it. The flag SHALL NOT be role-filtered: a field is listed or hidden by the existing sensitive-column rule, and
a listed field always carries its flag.

The SQL `INFORMATION_SCHEMA.COLUMNS` view SHALL list a computed field like any other column of its entity, under the
same visibility rule, and SHALL NOT add a column for the flag.

#### Scenario: The rebuilt body is flagged projection-only

- **WHEN** an entitled caller reads `GET /v1/queries/entities/schema/dial_usage_log`
- **THEN** `dial_usage_log_payload.request_body_full` and `dial_usage_log_payload.request_body_rebuild` carry
  `projection_only: true`, and `dial_usage_log_payload.request_body` and `request_time` carry
  `projection_only: false`

#### Scenario: Every field carries the flag

- **WHEN** any entity schema is read
- **THEN** every field object carries `projection_only`, `false` unless the field is computed
