# rest-sql-endpoint — delta for sql-object-support

## ADDED Requirements

### Requirement: Object columns are selectable and returned as JSON objects

`POST /v1/queries/execute-sql` SHALL accept SQL that projects `object`-typed catalog fields
(per the `sql-query-translation` contract: projection-only) and SHALL return each object cell
as a native JSON value inside the row object — never as an escaped JSON string and never as a
Java-style `toString` rendering — exactly the encoding `POST /v1/queries/execute` produces for
the same projection. An empty object SHALL be returned as `{}`.

#### Scenario: Object column projected via SQL

- **WHEN** a client posts `{ "sql": "SELECT request_id, jwt_claims FROM dial_usage_log LIMIT 5" }`
- **THEN** the response is HTTP 200 and each row carries `jwt_claims` as a native JSON object (e.g. `{"sub":"user-1","roles":["admin"]}`), matching the DSL endpoint's encoding for the same projection

#### Scenario: Object projection matches the DSL endpoint byte-for-byte

- **WHEN** a client runs a projection of the same columns through `POST /v1/queries/execute-sql` and the equivalent document through `POST /v1/queries/execute`
- **THEN** the two response bodies are identical

#### Scenario: Star projection over an entity with object columns succeeds

- **WHEN** a client posts `{ "sql": "SELECT * FROM dial_usage_log LIMIT 1" }` for an entity whose source columns include `object`-typed fields
- **THEN** the response is HTTP 200 and the object columns are present as native JSON values

#### Scenario: Object misuse rejected per the translation contract

- **WHEN** a client posts `{ "sql": "SELECT request_id FROM dial_usage_log WHERE jwt_claims IS NULL" }`
- **THEN** the response is HTTP 400 `bad_request` with a message naming the object field and the projection-only restriction, and ClickHouse is not contacted
