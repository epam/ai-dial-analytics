## MODIFIED Requirements

### Requirement: Star projections omit heavy and enrichment columns in the Arrow schema and stream

The Flight SQL endpoint SHALL inherit the star expansion of the shared SQL front end (see the
`sql-query-translation` capability's "Star projection expands to the non-heavy source columns"). For a
`SELECT *` statement, `getFlightInfo` SHALL advertise an Arrow schema whose fields are exactly the
entity's **non-heavy source** columns, and the `doGet` stream SHALL carry those same fields — so an ADBC
or pandas client issuing the canonical `SELECT * FROM <entity> LIMIT <n>` never pays for a large payload
column, nor for an enrichment `LEFT JOIN`, it did not ask for (**BREAKING** as of the
heavy-column-projection change: a star previously advertised the entity's enrichment columns too). A
statement naming a `heavy` or enrichment column SHALL stream it unchanged, with the same Arrow type mapping as any other column of
that kind. The same Arrow schema SHALL be what `CommandGetTables` reports for that entity when
`include_schema` is set, so catalog metadata and the star statement cannot disagree.

A star whose expansion would be empty SHALL fail with `INVALID_ARGUMENT` naming the entity, at
`getFlightInfo` time, before ClickHouse is contacted, and SHALL do so whether or not the statement
carries a `LIMIT`. The pre-existing schema-agreement guarantee SHALL hold: the `doGet` stream's schema
SHALL equal the `FlightInfo` schema field-for-field for the same statement.

On `dial_usage_log` the two exclusions now coincide for the body columns: each is `heavy` **and** owned
by the `dial_usage_log_payload` enrichment, so a star omits it under either rule and the canonical
`SELECT * FROM dial_usage_log` reads only the small columns of a single table. The entity's
`object`-typed claim and header columns are source columns and are still advertised.

#### Scenario: FlightInfo schema for a star omits heavy fields

- **WHEN** an entitled client calls getFlightInfo with `SELECT * FROM <entity> LIMIT 1` for an entity
  one of whose own **source** columns is marked `heavy`
- **THEN** the advertised Arrow schema carries every non-heavy source column and not that field,
  and the doGet stream delivers exactly those fields

#### Scenario: A star over dial_usage_log advertises no body field

- **WHEN** an entitled client calls getFlightInfo with `SELECT * FROM dial_usage_log LIMIT 1`
- **THEN** the advertised schema carries no `request_body`, `response_body`, `assembled_response`,
  `jwt_claims` or `request_tags` field under any spelling, the executed query joins no enrichment,
  and `request_body_bytes` and `response_body_bytes` are present

#### Scenario: Star schema carries no enrichment field

- **WHEN** a client calls getFlightInfo with `SELECT * FROM <source> LIMIT 1` for a source with an
  attached enrichment
- **THEN** the advertised schema carries no `<enrichment>.<column>` field, and the executed query joins
  no enrichment

#### Scenario: Explicitly named heavy column streams normally

- **WHEN** the client runs `SELECT event_id, "dial_usage_log_payload.response_body" FROM dial_usage_log LIMIT 5`
- **THEN** the Arrow schema carries `dial_usage_log_payload.response_body` as `Utf8` and the stream
  delivers its values

#### Scenario: All-heavy star fails fast
- **WHEN** a client calls getFlightInfo with `SELECT * FROM <entity> LIMIT 1` whose every visible field
  is marked `heavy`
- **THEN** the call fails with `INVALID_ARGUMENT` and ClickHouse is not contacted

## ADDED Requirements

### Requirement: An unmatched payload row streams as an Arrow null

A statement naming a column of the payload enrichment for an event whose payload row is absent — most
often because the event is older than the payload table's 90-day retention while its `dial_usage_log`
row is not — SHALL stream that cell as an Arrow null and SHALL NOT fail. This is the existing
unmatched-`LEFT JOIN` rule (see "Arrow result schema and type mapping"), and it becomes reachable in
ordinary use once the two tables carry different retentions.

#### Scenario: An expired payload reads back as null

- **WHEN** a client selects `"dial_usage_log_payload.request_body"` for an event whose payload has
  passed the payload table's retention
- **THEN** the Arrow cell is null and the row is streamed normally, because the grain join found no row
