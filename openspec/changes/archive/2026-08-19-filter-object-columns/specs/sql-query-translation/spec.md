## MODIFIED Requirements

### Requirement: Object fields are projection-only in SQL

An `object`-typed field SHALL be usable in SQL only as a plain projection item (optionally
aliased) **or as the first argument of a `JSON_EXTRACT_STRING`/`JSON_EXTRACT_LONG`/
`JSON_EXTRACT_DOUBLE`/`JSON_EXTRACT_BOOL` call**. Any other use SHALL be rejected at translation
time — before any ClickHouse contact —
with an error naming the object field and stating that object fields can only be projected;
the error SHALL NOT be an "unknown column" error and SHALL NOT surface as a raw ClickHouse
failure. Rejected uses include: as an operand of any predicate or comparison (including
`IS NULL` / `IS NOT NULL`, `LIKE`, and `IN`), as a `GROUP BY` key, as an `ORDER BY` key, under
`SELECT DISTINCT`, and as an argument to any scalar or aggregate function other than the
`JSON_EXTRACT_*` family. The resulting
`StructuredQuery` SHALL carry the object field as an ordinary field projection, and the derived
result schema SHALL type the column as an object column.

A `JSON_EXTRACT_*` call whose first argument is an `object` field SHALL translate to the
corresponding DSL `json_extract_*` function expression, exactly as one over a character column does,
and the derived result schema SHALL type the column by the function's fixed return type (text,
long, numeric, boolean) rather than as an object column. Because the translated node is an ordinary
scalar expression, such a call SHALL be accepted wherever the SQL subset accepts a scalar
expression — in `WHERE`, `HAVING`, `GROUP BY`, `ORDER BY`, `SELECT DISTINCT`, and as an aggregate
argument — and the restriction above SHALL apply to the bare `object` field only, never to the
extraction's result. Path arguments SHALL remain string literals, so an `object` field passed to
`JSON_EXTRACT_*` with a computed path SHALL be rejected for the path, not for the field's type.

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

#### Scenario: JSON_EXTRACT over an object field in the projection translates
- **WHEN** a client submits `SELECT JSON_EXTRACT_STRING(jwt_claims, 'sub') AS sub FROM dial_usage_log LIMIT 10`
- **THEN** translation succeeds, producing a DSL `json_extract_string` call over the `jwt_claims` field aliased `sub`, and the result schema types the column as text rather than as an object column

#### Scenario: JSON_EXTRACT over an object field in WHERE translates
- **WHEN** a client submits `SELECT request_id FROM dial_usage_log WHERE JSON_EXTRACT_STRING(jwt_claims, 'sub') = 'user-1'`
- **THEN** translation succeeds, producing a DSL `eq` comparison whose left operand is the `json_extract_string` call — the projection-only restriction does not apply to this position

#### Scenario: JSON_EXTRACT over an object field in GROUP BY and ORDER BY translates
- **WHEN** a client submits a statement grouping and ordering by `JSON_EXTRACT_STRING(jwt_claims, 'sub')` with an aggregate
- **THEN** translation succeeds and the group and sort keys reference the extraction's output name, not the bare object field

#### Scenario: JSON_EXTRACT over an object field with a computed path rejected
- **WHEN** a client submits `SELECT JSON_EXTRACT_STRING(jwt_claims, request_id) FROM dial_usage_log`
- **THEN** translation fails with an error naming the literal-path restriction on `json_extract_*`, not the object field's type

#### Scenario: The extraction result is not itself projection-restricted
- **WHEN** a client submits `SELECT DISTINCT JSON_EXTRACT_STRING(jwt_claims, 'sub') FROM dial_usage_log`
- **THEN** translation succeeds — `DISTINCT` is restricted over a bare `object` field, not over a scalar extracted from one
