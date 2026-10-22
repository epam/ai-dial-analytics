## MODIFIED Requirements

### Requirement: SQL is validated against the query-schema catalog

SQL statements SHALL be parsed and validated with Calcite against a schema derived from the
same catalog as `GET /v1/queries/entities` and `GET /v1/queries/entities/schema/{name}`: each
ACTIVE queryable entity is a table whose columns are the entity's flat fields (source columns
plus dotted enrichment columns). Identifier matching SHALL be case-insensitive. Enrichment
columns SHALL be addressable only as double-quoted flat identifiers (e.g.
`"deployment_ref.display_name"`, `"dial_usage_log_payload.request_body"`). Every declared field type
SHALL be included in the SQL-visible schema: fields of type `array` SHALL be typed as array columns
(element type from the catalog's `element_type`) and fields of type `object` SHALL be typed as
character columns; both are projection-only (see the respective projection requirements). The schema
SHALL be derived from the same access-filtered entity model the JSON query surface uses, so a column
the caller may not see (e.g. a sensitive column without the grant) SHALL be absent from the
SQL-visible schema and rejected as an unknown column. References to unknown tables or columns
SHALL be rejected at validation time.

Because every seeded verbatim-content column is owned by the `dial_usage_log_payload` enrichment,
their only valid spelling on `dial_usage_log` is the double-quoted qualified one; the bare names
`request_body`, `response_body`, `assembled_response`, `jwt_claims` and `request_tags` SHALL be
rejected as unknown columns of that entity.

#### Scenario: Known entity and columns validate
- **WHEN** a client submits `SELECT request_id, "deployment_ref.display_name" FROM dial_usage_log`
- **THEN** validation succeeds and both columns resolve to the entity's flat fields

#### Scenario: Unknown column rejected
- **WHEN** a client submits `SELECT no_such_column FROM dial_usage_log`
- **THEN** translation fails with a validation error naming the unknown column, before execution

#### Scenario: A bare body column name is rejected
- **WHEN** a client submits `SELECT request_body FROM dial_usage_log`
- **THEN** translation fails with a validation error naming `request_body` as an unknown column,
  before execution

#### Scenario: The qualified body column name resolves
- **WHEN** a client submits `SELECT "dial_usage_log_payload.request_body" FROM dial_usage_log`
- **THEN** validation succeeds and the column resolves to the entity's flat field, emitting the grain
  join

#### Scenario: Object field visible to SQL
- **WHEN** a client submits `SELECT request_id, "dial_usage_log_payload.jwt_claims" FROM dial_usage_log`
- **THEN** validation succeeds, the column resolves to the entity's `object`-typed flat field, and the
  grain join is emitted

#### Scenario: A bare claim column name is rejected
- **WHEN** a client submits `SELECT jwt_claims FROM dial_usage_log`
- **THEN** translation fails with a validation error naming `jwt_claims` as an unknown column, before
  execution

#### Scenario: Array field visible to SQL
- **WHEN** a client submits `SELECT request_id, execution_path FROM dial_usage_log`
- **THEN** validation succeeds and `execution_path` resolves to the entity's array-typed flat field

#### Scenario: Sensitive object column stays invisible without the grant
- **WHEN** a caller who may not see sensitive columns submits a statement selecting a sensitive `object` column
- **THEN** translation fails as an unknown column, exactly as the column is hidden from schema discovery
