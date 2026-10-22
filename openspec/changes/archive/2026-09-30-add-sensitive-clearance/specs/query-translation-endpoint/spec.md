## MODIFIED Requirements

### Requirement: Translation endpoints share the query surface's access rules

Both translation endpoints SHALL be governed by the same authentication rules as the other
`/v1/queries` endpoints, and SHALL be documented in the service's OpenAPI description. Auth failures
return the standard 401/403 `ErrorView`s.

Authorization SHALL require `TABLE_GET` on every table the request resolves, not `TABLE_READ`:
translation validates against the resolved catalog model and returns SQL or a structured query, and
never contacts ClickHouse, so it discloses schema rather than data. A caller lacking `TABLE_GET` on
a resolved table SHALL be refused as though the table did not exist — `400 bad_request` reporting an
unknown entity, relation or field, never `403`.

Sensitive columns SHALL remain governed by `TABLE_SENSITIVE` on the owning table: a caller
without it SHALL NOT be able to translate a query that references one, and the refusal SHALL be the
ordinary unknown-field error.

#### Scenario: Unauthenticated caller rejected in oidc mode

- **WHEN** security mode is `oidc` and an unauthenticated client posts to `/v1/queries/translate` or `/v1/queries/translate-sql`
- **THEN** the response is a 401 `ErrorView` with code `unauthorized`

#### Scenario: Translation needs only schema visibility

- **GIVEN** a table whose `get` list contains the caller's provider role and whose `read` list does
  not
- **WHEN** the caller posts a statement against that entity to `/v1/queries/translate-sql`
- **THEN** the translation succeeds
- **AND WHEN** the caller posts the same statement to `/v1/queries/execute-sql`
- **THEN** the response reports an unknown relation

#### Scenario: An entity the caller cannot get is unknown

- **WHEN** the caller posts a statement naming a table it holds no read-side privilege on
- **THEN** the response is `400 bad_request` reporting an unknown relation, and does not reveal that
  the table exists

#### Scenario: A sensitive column cannot be translated without entitlement

- **WHEN** a caller holding `TABLE_GET` but not `TABLE_SENSITIVE` on the owning table posts a
  query referencing a sensitive column
- **THEN** the response is `400 bad_request` reporting an unknown field
