# sql-query-translation — delta for sql-array-support

## MODIFIED Requirements

### Requirement: SQL is validated against the query-schema catalog
SQL statements SHALL be parsed and validated with Calcite against a schema derived from the
same catalog as `GET /v1/queries/entities` and `GET /v1/queries/entities/schema/{name}`: each
ACTIVE queryable entity is a table whose columns are the entity's flat fields (source columns
plus dotted enrichment columns). Identifier matching SHALL be case-insensitive. Enrichment
columns SHALL be addressable only as double-quoted flat identifiers (e.g.
`"deployment_ref.display_name"`). Fields of type `object` SHALL be excluded from the
SQL-visible schema. Fields of type `array` SHALL be included in the SQL-visible schema as
array-typed columns (typed by their catalog `element_type`) but are projection-only (see the
array projection requirement). References to unknown tables or columns SHALL be rejected at
validation time.

#### Scenario: Known entity and columns validate
- **WHEN** a client submits `SELECT request_id, "deployment_ref.display_name" FROM dial_usage_log`
- **THEN** validation succeeds and both columns resolve to the entity's flat fields

#### Scenario: Unknown column rejected
- **WHEN** a client submits `SELECT no_such_column FROM dial_usage_log`
- **THEN** translation fails with a validation error naming the unknown column, before execution

#### Scenario: Object field invisible to SQL
- **WHEN** an entity has a field of type `object`
- **THEN** selecting or filtering on that field fails validation as an unknown column

#### Scenario: Array field visible to SQL
- **WHEN** a client submits `SELECT request_id, execution_path FROM dial_usage_log`
- **THEN** validation succeeds and `execution_path` resolves to the entity's array-typed flat field

## ADDED Requirements

### Requirement: Array fields are projection-only in SQL

An `array`-typed field SHALL be usable in SQL only as a plain projection item (optionally
aliased). Any other use SHALL be rejected at translation time — before any ClickHouse contact —
with an error naming the array field and stating that array fields can only be projected.
Rejected uses include: as an operand of any predicate or comparison (including `IS NULL` /
`IS NOT NULL` and `IN`), as a `GROUP BY` key, as an `ORDER BY` key, under `SELECT DISTINCT`
(row-mode distinct deduplicates over the projected array), and as an argument to any scalar or
aggregate function. The resulting `StructuredQuery` SHALL carry the array field as an ordinary
field projection, and the derived result schema SHALL type the column as an array of its
catalog `element_type`.

#### Scenario: Array projection translates
- **WHEN** a client submits `SELECT execution_path FROM dial_usage_log LIMIT 10`
- **THEN** the translator produces a row-mode StructuredQuery projecting `execution_path`, and the result schema types the column as an array with element type `string`

#### Scenario: Array field in WHERE rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE execution_path IS NOT NULL`
- **THEN** translation fails with an error naming `execution_path` and the projection-only restriction, and ClickHouse is not contacted

#### Scenario: Array field in GROUP BY rejected
- **WHEN** a client submits `SELECT execution_path, count(*) FROM dial_usage_log GROUP BY execution_path`
- **THEN** translation fails with an error naming `execution_path` and the projection-only restriction

#### Scenario: Array field in ORDER BY rejected
- **WHEN** a client submits `SELECT execution_path FROM dial_usage_log ORDER BY execution_path`
- **THEN** translation fails with an error naming `execution_path` and the projection-only restriction

#### Scenario: Array field as function argument rejected
- **WHEN** a client submits `SELECT count(execution_path) FROM dial_usage_log`
- **THEN** translation fails before execution with an error identifying the unsupported use of the array field
