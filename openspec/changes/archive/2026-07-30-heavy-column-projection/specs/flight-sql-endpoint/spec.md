## ADDED Requirements

### Requirement: Star projections omit heavy and enrichment columns in the Arrow schema and stream

The Flight SQL endpoint SHALL inherit the star expansion of the shared SQL front end (see the
`sql-query-translation` capability's "Star projection expands to the non-heavy source columns"). For a
`SELECT *` statement, `getFlightInfo` SHALL advertise an Arrow schema whose fields are exactly the
entity's **non-heavy source** columns, and the `doGet` stream SHALL carry those same fields — so an ADBC
or pandas client issuing the canonical `SELECT * FROM <entity>` never pays for a large payload column,
nor for an enrichment `LEFT JOIN`, it did not ask for (**BREAKING**: a star previously advertised the
entity's enrichment columns too). A statement naming a `heavy` or enrichment column SHALL stream it
unchanged, with the same Arrow type mapping as any other column of that kind.

A star whose expansion would be empty SHALL fail with `INVALID_ARGUMENT` naming the entity, at
`getFlightInfo` time, before ClickHouse is contacted. The pre-existing schema-agreement guarantee SHALL
hold: the `doGet` stream's schema SHALL equal the `FlightInfo` schema field-for-field for the same
statement.

#### Scenario: FlightInfo schema for a star omits heavy fields

- **WHEN** an entitled client calls getFlightInfo with `SELECT * FROM dial_usage_log` where
  `request_body` and `response_body` are marked `heavy`
- **THEN** the advertised Arrow schema carries every non-heavy source column and neither payload field,
  and the doGet stream delivers exactly those fields

#### Scenario: Star schema carries no enrichment field

- **WHEN** a client calls getFlightInfo with `SELECT * FROM <source>` for a source with an attached
  enrichment
- **THEN** the advertised schema carries no `<enrichment>.<column>` field, and the executed query joins
  no enrichment

#### Scenario: Explicitly named heavy column streams normally

- **WHEN** the client runs `SELECT event_id, response_body FROM dial_usage_log LIMIT 5`
- **THEN** the Arrow schema carries `response_body` as `Utf8` and the stream delivers its values

#### Scenario: All-heavy star fails fast
- **WHEN** a client calls getFlightInfo with `SELECT * FROM <entity>` whose every visible field is
  marked `heavy`
- **THEN** the call fails with `INVALID_ARGUMENT` and ClickHouse is not contacted
