## ADDED Requirements

### Requirement: Queryable entity catalog

The system SHALL expose, at `GET /v1/queries/entities`, the list of entities that can be named as the
`entity` of a structured query. Each catalog item SHALL carry the entity wire `name`, a `complex`
flag, and a `schemaIdField` — the name of the query parameter whose value selects a concrete instance
for the detailed schema endpoint — which SHALL be present for complex entities and null for simple
entities. Entities SHALL be listed in stable alphabetical order by name.

#### Scenario: Catalog lists the registered entities

- **WHEN** `GET /v1/queries/entities` is called
- **THEN** the response lists each registered entity in alphabetical order, including the simple
  entity `dial_usage_log` with `complex=false` and a null `schemaIdField`

### Requirement: Instance-independent base schema

The system SHALL expose, at `GET /v1/queries/entities/schema/{name}`, the entity's flat base schema:
a list of fields each carrying a name, a flat field type, and the physical source it maps to. For
`dial_usage_log` the schema is hand-declared to mirror the ClickHouse table DDL (ADAS has no jOOQ code
generation). ClickHouse column types SHALL map to flat field types as: `LowCardinality(String)` and
`String` → `string`, `UInt32` → `long`, `DateTime64(3)` and `DateTime` → `timestamp`, and
`Array(String)` → `array`.

#### Scenario: Base schema lists the entity's flat fields

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log` is called
- **THEN** the response lists the entity's fields with their mapped types — e.g. `project_id` typed
  `string`, `total_tokens` typed `long`, `request_time` typed `timestamp`, and `execution_path` typed
  `array`

### Requirement: Instance-specific detailed schema for complex entities

The system SHALL expose, at `GET /v1/queries/entities/schema/{name}/detailed`, the flat schema of a
complex entity for a concrete instance, selected by the entity's advertised `schemaIdField`. A simple
(non-complex) entity has no detailed schema. No complex entity is registered in this phase, so the
endpoint returns a validation error for the only registered entity (`dial_usage_log`); the per-instance
flattening mechanics remain available for future complex entities.

#### Scenario: Detailed schema on a simple entity is rejected

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log/detailed` is called
- **THEN** the response status is 400 because `dial_usage_log` is not complex

### Requirement: Schema discovery error contract

The system SHALL reject invalid discovery requests with specific HTTP statuses: an unknown entity name
SHALL return 404; a detailed-schema request against a simple (non-complex) entity SHALL return 400.
Errors SHALL use the shared `ErrorView` contract and MUST NOT leak internals.

#### Scenario: Unknown entity

- **WHEN** any schema endpoint is called with an entity name that is not registered
- **THEN** the response status is 404 with error code `not_found`

#### Scenario: Detailed schema on a simple entity

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log/detailed` is called
- **THEN** the response status is 400 with error code `bad_request`
