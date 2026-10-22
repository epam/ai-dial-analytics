## MODIFIED Requirements

### Requirement: Instance-independent base schema

The system SHALL expose, at `GET /v1/queries/entities/schema/{name}`, the entity's flat base schema:
a list of fields each carrying a name, a flat field type, and the physical source it maps to. For
`dial_usage_log` the schema is hand-declared to mirror the ClickHouse table DDL (ADAS has no jOOQ code
generation) and exposes the queryable subset of the `events` column set — scalar dimensions and metrics —
while excluding the heavy body columns, the `JSON` enrichment columns, and the monetary columns. ClickHouse
column types SHALL map to flat field types as: `LowCardinality(String)` and `String` → `string`,
`UUID` → `uuid`, `UInt32` → `long`, `UInt16` → `integer`, `Bool` → `boolean`,
`DateTime64(3)` and `DateTime` → `timestamp`, and `Array(String)` → `array`.

#### Scenario: Base schema lists the entity's flat fields

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log` is called
- **THEN** the response lists the entity's fields with their mapped types — e.g. `event_id` typed
  `uuid`, `event_kind` typed `string`, `user_hash` typed `string`, `project_id` typed `string`,
  `total_tokens` typed `long`, `success` typed `boolean`, `request_time` typed `timestamp`, and
  `execution_path` typed `array`

#### Scenario: Dropped columns are no longer advertised

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log` is called
- **THEN** the response does not list `api_type`, `user_id`, `user_title`, or `request_protocol`,
  because those columns no longer exist in the table
