# rest-sql-endpoint — delta for sql-array-support

## ADDED Requirements

### Requirement: Array columns are selectable and returned as JSON arrays

`POST /v1/queries/execute-sql` SHALL accept SQL that projects `array`-typed catalog fields
(per the `sql-query-translation` contract: projection-only) and SHALL return each array cell
as a native JSON array of the field's element type inside the row object — never as an escaped
string — exactly the encoding `POST /v1/queries/execute` produces for the same projection. An
empty ClickHouse array SHALL be returned as an empty JSON array `[]`.

#### Scenario: Array column projected via SQL

- **WHEN** a client posts `{ "sql": "SELECT request_id, execution_path FROM dial_usage_log LIMIT 5" }`
- **THEN** the response is HTTP 200 and each row carries `execution_path` as a JSON array of strings (e.g. `["core","assistant"]`), matching the DSL endpoint's encoding for the same projection

#### Scenario: Empty array preserved

- **WHEN** a projected array cell is an empty ClickHouse array
- **THEN** the row carries the column key with a JSON `[]` value, not `null` and not a missing key

#### Scenario: Array misuse rejected per the translation contract

- **WHEN** a client posts `{ "sql": "SELECT request_id FROM dial_usage_log WHERE execution_path IS NULL" }`
- **THEN** the response is HTTP 400 `bad_request` with a message naming the array field and the projection-only restriction, and ClickHouse is not contacted
