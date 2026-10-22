## ADDED Requirements

### Requirement: Map fields are projection-only in SQL

A `map`-typed field SHALL be usable in SQL only as a plain projection item (optionally aliased). Any
other use SHALL be rejected at translation time — before any ClickHouse contact — with an error naming
the map field and stating that map fields can only be projected; the error SHALL NOT be an "unknown
column" error and SHALL NOT surface as a raw ClickHouse failure. Rejected uses include: as an operand of
any predicate or comparison (including `IS NULL` / `IS NOT NULL`, `LIKE`, and `IN`), as a `GROUP BY` key,
as an `ORDER BY` key, under `SELECT DISTINCT`, and as an argument to any scalar or aggregate function.
The resulting `StructuredQuery` SHALL carry the map field as an ordinary field projection, and the
derived result schema SHALL type the column as an object column, since a map cell travels as a JSON
object exactly as an `object` cell does.

A star projection over an entity carrying `map` columns SHALL translate, carrying them as ordinary field
projections.

In `INFORMATION_SCHEMA.COLUMNS`, a `map` column SHALL report `DATA_TYPE` `CHARACTER VARYING` — the same
value an `object` column reports, and for the same reason: it travels as JSON text.

#### Scenario: Map projection translates

- **WHEN** a client submits `SELECT log_attributes FROM otel_app_logs LIMIT 10`
- **THEN** the translator produces a row-mode StructuredQuery projecting `log_attributes`, and the result
  schema types the column as an object column

#### Scenario: Map field in WHERE rejected

- **WHEN** a client submits `SELECT trace_id FROM otel_app_logs WHERE log_attributes IS NOT NULL`
- **THEN** translation fails with an error naming `log_attributes` and the projection-only restriction,
  and ClickHouse is not contacted

#### Scenario: Map field in GROUP BY rejected

- **WHEN** a client submits `SELECT log_attributes, count(*) FROM otel_app_logs GROUP BY log_attributes`
- **THEN** translation fails with an error naming `log_attributes` and the projection-only restriction

#### Scenario: Map field in ORDER BY rejected

- **WHEN** a client submits `SELECT log_attributes FROM otel_app_logs ORDER BY log_attributes`
- **THEN** translation fails with an error naming `log_attributes` and the projection-only restriction

#### Scenario: Map column reports as CHARACTER VARYING

- **WHEN** a client reads `INFORMATION_SCHEMA.COLUMNS` for a table carrying a `map` column
- **THEN** that column's `DATA_TYPE` is `CHARACTER VARYING`
