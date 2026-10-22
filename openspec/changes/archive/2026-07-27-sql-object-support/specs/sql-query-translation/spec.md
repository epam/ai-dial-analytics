# sql-query-translation — delta for sql-object-support

## MODIFIED Requirements

### Requirement: SQL is validated against the query-schema catalog
SQL statements SHALL be parsed and validated with Calcite against a schema derived from the
same catalog as `GET /v1/queries/entities` and `GET /v1/queries/entities/schema/{name}`: each
ACTIVE queryable entity is a table whose columns are the entity's flat fields (source columns
plus dotted enrichment columns). Identifier matching SHALL be case-insensitive. Enrichment
columns SHALL be addressable only as double-quoted flat identifiers (e.g.
`"deployment_ref.display_name"`). Every declared field type SHALL be included in the
SQL-visible schema: fields of type `array` SHALL be typed as array columns (element type from
the catalog's `element_type`) and fields of type `object` SHALL be typed as character columns;
both are projection-only (see the respective projection requirements). The schema SHALL be
derived from the same access-filtered entity model the JSON query surface uses, so a column
the caller may not see (e.g. a sensitive column without the grant) SHALL be absent from the
SQL-visible schema and rejected as an unknown column. References to unknown tables or columns
SHALL be rejected at validation time.

#### Scenario: Known entity and columns validate
- **WHEN** a client submits `SELECT request_id, "deployment_ref.display_name" FROM dial_usage_log`
- **THEN** validation succeeds and both columns resolve to the entity's flat fields

#### Scenario: Unknown column rejected
- **WHEN** a client submits `SELECT no_such_column FROM dial_usage_log`
- **THEN** translation fails with a validation error naming the unknown column, before execution

#### Scenario: Object field visible to SQL
- **WHEN** a client submits `SELECT request_id, jwt_claims FROM dial_usage_log`
- **THEN** validation succeeds and `jwt_claims` resolves to the entity's `object`-typed flat field

#### Scenario: Array field visible to SQL
- **WHEN** a client submits `SELECT request_id, execution_path FROM dial_usage_log`
- **THEN** validation succeeds and `execution_path` resolves to the entity's array-typed flat field

#### Scenario: Sensitive object column stays invisible without the grant
- **WHEN** a caller who may not see sensitive columns submits a statement selecting a sensitive `object` column
- **THEN** translation fails as an unknown column, exactly as the column is hidden from schema discovery

## ADDED Requirements

### Requirement: Object fields are projection-only in SQL

An `object`-typed field SHALL be usable in SQL only as a plain projection item (optionally
aliased). Any other use SHALL be rejected at translation time — before any ClickHouse contact —
with an error naming the object field and stating that object fields can only be projected;
the error SHALL NOT be an "unknown column" error and SHALL NOT surface as a raw ClickHouse
failure. Rejected uses include: as an operand of any predicate or comparison (including
`IS NULL` / `IS NOT NULL`, `LIKE`, and `IN`), as a `GROUP BY` key, as an `ORDER BY` key, under
`SELECT DISTINCT`, and as an argument to any scalar or aggregate function. The resulting
`StructuredQuery` SHALL carry the object field as an ordinary field projection, and the derived
result schema SHALL type the column as an object column.

#### Scenario: Object projection translates
- **WHEN** a client submits `SELECT jwt_claims FROM dial_usage_log LIMIT 10`
- **THEN** the translator produces a row-mode StructuredQuery projecting `jwt_claims`, and the result schema types the column as an object column

#### Scenario: Object field in WHERE rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims IS NOT NULL`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction, and ClickHouse is not contacted

#### Scenario: Object field compared to a literal rejected
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE jwt_claims = 'x'`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction

#### Scenario: Object field in GROUP BY rejected
- **WHEN** a client submits `SELECT jwt_claims, count(*) FROM dial_usage_log GROUP BY jwt_claims`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction

#### Scenario: Object field in ORDER BY rejected
- **WHEN** a client submits `SELECT jwt_claims FROM dial_usage_log ORDER BY jwt_claims`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction

#### Scenario: Object field under SELECT DISTINCT rejected
- **WHEN** a client submits `SELECT DISTINCT jwt_claims FROM dial_usage_log`
- **THEN** translation fails with an error naming `jwt_claims` and the projection-only restriction

#### Scenario: Object field as function argument rejected
- **WHEN** a client submits `SELECT count(jwt_claims) FROM dial_usage_log`
- **THEN** translation fails before execution with an error identifying the unsupported use of the object field

#### Scenario: Star projection over an entity with object columns translates
- **WHEN** a client submits `SELECT * FROM dial_usage_log LIMIT 1` on an entity whose source columns include `object`-typed fields
- **THEN** translation succeeds and the object columns are carried as ordinary field projections
