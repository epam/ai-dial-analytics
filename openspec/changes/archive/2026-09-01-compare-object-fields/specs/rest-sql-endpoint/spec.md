## MODIFIED Requirements

### Requirement: Object columns are selectable and returned as JSON objects

`POST /v1/queries/execute-sql` SHALL accept SQL that projects `object`-typed catalog fields
(per the `sql-query-translation` contract: projection-only, plus the document comparisons named
there) and SHALL return each object cell
as a native JSON value inside the row object — never as an escaped JSON string and never as a
Java-style `toString` rendering — exactly the encoding `POST /v1/queries/execute` produces for
the same projection. An empty object SHALL be returned as `{}`.

The endpoint SHALL additionally accept SQL that **filters** on an `object`-typed field with
`LIKE`, `ILIKE`, `NOT LIKE`, `NOT ILIKE`, `=`, `<>` or `IN` against character literals, executing it
with the canonical-JSON-document semantics the `structured-query` capability defines, and SHALL
return the matching rows. A comparison the contract still refuses — `IS NULL`/`IS NOT NULL`, an
ordering comparison, or `=`/`<>`/`IN` against a literal that is not a well-formed JSON document —
SHALL be rejected with HTTP 400 `bad_request` before ClickHouse is contacted.

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

#### Scenario: Object column filtered with ILIKE executes

- **WHEN** a client posts `{ "sql": "SELECT request_id FROM dial_usage_log WHERE request_tags ILIKE '%gpt-4%' LIMIT 5" }`
- **THEN** the response is HTTP 200 and carries exactly the rows whose `request_tags` document contains
  `gpt-4` case-insensitively in its canonical JSON text

#### Scenario: Object column filtered by document equality executes

- **WHEN** a client posts SQL comparing an `object` column with `=` against a well-formed JSON literal
  whose keys are written in an order different from the stored document's
- **THEN** the response is HTTP 200 and the matching rows are returned — document equality does not
  depend on key order

#### Scenario: Object column equality against a non-JSON literal rejected

- **WHEN** a client posts `{ "sql": "SELECT request_id FROM dial_usage_log WHERE jwt_claims = 'x'" }`
- **THEN** the response is HTTP 400 `bad_request` naming the object field and the JSON-literal
  requirement, and ClickHouse is not contacted

#### Scenario: Object column under an ordering comparison rejected

- **WHEN** a client posts SQL comparing an `object` column with `>` against a JSON literal
- **THEN** the response is HTTP 400 `bad_request` naming the object field and the projection-only
  restriction, and ClickHouse is not contacted

#### Scenario: SQL and DSL agree on a document filter

- **WHEN** a client runs an `ILIKE` filter on an `object` column through `POST /v1/queries/execute-sql`
  and the equivalent `ico` document through `POST /v1/queries/execute`
- **THEN** the two response bodies are identical
