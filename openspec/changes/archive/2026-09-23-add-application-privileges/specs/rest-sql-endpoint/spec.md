## ADDED Requirements

### Requirement: Ad-hoc SQL requires TABLE_READ on every resolved table

`POST /v1/queries/execute-sql` SHALL require `TABLE_READ` — held globally or granted on the table —
for **every** table the statement resolves, including each enrichment the flattened entity reads.
A caller lacking it on any resolved table SHALL be refused as though that table did not exist: the
statement SHALL fail as referring to an unknown relation or column with `400 bad_request`, never
`403`.

No separate privilege SHALL gate ad-hoc SQL as distinct from the structured DSL: both resolve through
the same governed catalog model and the same chokepoint, so a privilege that admitted one and refused
the other would describe no difference in what is disclosed.

#### Scenario: A statement over a readable table executes

- **GIVEN** a source table whose `read` list contains the caller's provider role
- **WHEN** the caller posts a statement selecting from that entity
- **THEN** the statement resolves and executes normally

#### Scenario: A statement over an unreadable table is unknown

- **WHEN** the caller posts a statement selecting from a table it holds no read privilege on
- **THEN** the response is `400 bad_request` reporting an unknown relation, and does not reveal that
  the table exists

#### Scenario: A join across a readable and an unreadable table is refused

- **WHEN** the caller posts a statement joining a readable entity to one it may not read
- **THEN** the response is `400 bad_request` reporting the unreadable side as unknown

### Requirement: SQL validation requires TABLE_GET on every resolved table

`POST /v1/queries/validate-sql` SHALL require `TABLE_GET` — not `TABLE_READ` — on every table the
statement resolves, matching the translation endpoints rather than `execute-sql`: it resolves the
statement against the catalog model and never contacts ClickHouse, so it discloses schema rather than
data.

A table the caller lacks `TABLE_GET` on SHALL be absent from the model it validates against, so the
statement is reported as naming an unresolvable relation through this endpoint's ordinary `200` diagnostic
shape rather than as an authorization failure. Sensitive columns SHALL remain governed by
`TABLE_READ_SENSITIVE` on the owning table, and a statement referencing one SHALL be reported as naming
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

## MODIFIED Requirements

### Requirement: Security inherits the /v1 policy

The endpoint SHALL be protected exactly as other `/v1/**` read endpoints: when
`security.mode=oidc`, the caller must authenticate (Bearer token or `Api-Key`) and carry
`TABLE_READ` on every table the statement resolves for `execute-sql`, and `TABLE_GET` for
`validate-sql` — failures yield 401 for an absent credential, and an unresolvable relation rather than
`403` for a table below the bar, in the `ErrorView` shape; when
`security.mode=none`, the endpoint is open. No endpoint-specific rules SHALL be added.

#### Scenario: Unauthenticated caller rejected in oidc mode

- **WHEN** `security.mode=oidc` and a client posts to `/v1/queries/execute-sql` without
  credentials
- **THEN** the response is HTTP 401 `unauthorized`

#### Scenario: Open in none mode

- **WHEN** `security.mode=none`
- **THEN** the endpoint accepts requests without credentials

### Requirement: Validation resolves against the caller's own column visibility

`POST /v1/queries/validate-sql` SHALL resolve entities and fields through the same catalog path as
execution, so a `sensitive` column absent from the model of a caller without `TABLE_READ_SENSITIVE` on
its owning table SHALL be reported as an
unresolvable column rather than confirmed to exist. This is the same answer `execute-sql` gives that
caller, and it is required: a validator that resolved names the caller cannot query would disclose the
column's existence.

#### Scenario: A sensitive column does not resolve for a read-only admin

- **WHEN** a caller holding `TABLE_GET` but not `TABLE_READ_SENSITIVE` posts a statement naming a
  `sensitive` column to `/v1/queries/validate-sql`
- **THEN** the response is `200` with `valid` false and an unresolvable-column issue — not a `403`, and
  not a valid verdict

#### Scenario: Security inherits the /v1 policy

- **WHEN** `security.mode=oidc` and a client posts to `/v1/queries/validate-sql` without credentials
- **THEN** the response is `401`, and with `TABLE_GET` on every resolved table the request is served —
  no
  endpoint-specific rule applies
