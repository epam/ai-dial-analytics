## MODIFIED Requirements

### Requirement: Validation resolves against the caller's own column visibility

`POST /v1/queries/validate-sql` SHALL resolve entities and fields through the same catalog path as
execution, so a `sensitive` column absent from the model of a caller without `TABLE_SENSITIVE` on
its owning table SHALL be reported as an
unresolvable column rather than confirmed to exist. This is the same answer `execute-sql` gives that
caller, and it is required: a validator that resolved names the caller cannot query would disclose the
column's existence.

#### Scenario: A sensitive column does not resolve for a read-only admin

- **WHEN** a caller holding `TABLE_GET` but not `TABLE_SENSITIVE` posts a statement naming a
  `sensitive` column to `/v1/queries/validate-sql`
- **THEN** the response is `200` with `valid` false and an unresolvable-column issue — not a `403`, and
  not a valid verdict

#### Scenario: Security inherits the /v1 policy

- **WHEN** `security.mode=oidc` and a client posts to `/v1/queries/validate-sql` without credentials
- **THEN** the response is `401`, and with `TABLE_GET` on every resolved table the request is served —
  no
  endpoint-specific rule applies

### Requirement: SQL validation requires TABLE_GET on every resolved table

`POST /v1/queries/validate-sql` SHALL require `TABLE_GET` — not `TABLE_READ` — on every table the
statement resolves, matching the translation endpoints rather than `execute-sql`: it resolves the
statement against the catalog model and never contacts ClickHouse, so it discloses schema rather than
data.

A table the caller lacks `TABLE_GET` on SHALL be absent from the model it validates against, so the
statement is reported as naming an unresolvable relation through this endpoint's ordinary `200` diagnostic
shape rather than as an authorization failure. Sensitive columns SHALL remain governed by
`TABLE_SENSITIVE` on the owning table, and a statement referencing one SHALL be reported as naming
an unresolvable column.

#### Scenario: Validation needs only schema visibility

- **GIVEN** a table whose `get` list contains the caller's provider role and whose `read` list does not
- **WHEN** the caller validates a statement selecting from that entity
- **THEN** the statement is reported as valid
- **AND WHEN** the caller posts the same statement to `/v1/queries/execute-sql`
- **THEN** the response reports an unknown relation

#### Scenario: A table below the bar is unresolvable, not forbidden

- **WHEN** the caller validates a statement naming a table it holds no read-side privilege on
- **THEN** the response is `200` reporting the relation as unresolvable, and does not reveal that the
  table exists
