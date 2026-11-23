## MODIFIED Requirements

### Requirement: Ad-hoc SQL requires TABLE_READ on every resolved table

`POST /v1/queries/execute-sql` SHALL require `TABLE_READ` — held globally or granted on the table —
for **every** table the statement resolves, including each enrichment the flattened entity reads, whose
`TABLE_READ` is its source's (see [table-access-control](../table-access-control/spec.md)).
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
