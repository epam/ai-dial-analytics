## MODIFIED Requirements

### Requirement: Response envelope matches the DSL endpoint

The endpoint SHALL return the same response envelope as `POST /v1/queries/execute`: a JSON
object with a `rows` array and a nullable `totalCount`. Each row SHALL be a field-name → value
map keyed by the selected column names or aliases (enrichment columns by their qualified flat
name); a select expression the statement does not name SHALL be keyed by its derived name per the
`sql-query-translation` naming contract (a function call by its lowercase name, e.g. `count`) and
never by the planner's synthetic `EXPR$<n>`. Every projected column SHALL be present in every row
with JSON `null` for null cells.
`totalCount` SHALL never be populated on this path — SQL provides no total-count opt-in — and,
per the service's shared response serialization (null bean properties are omitted), the
`totalCount` key SHALL be absent from the JSON body, exactly as the DSL endpoint serializes a
null `totalCount`.

#### Scenario: Envelope shape

- **WHEN** a valid SQL query returns two rows of two columns
- **THEN** the response body is `{ "rows": [ { <col>: <value>, <col>: <value> }, ... ] }` with
  both column keys present in every row and no `totalCount` key

#### Scenario: Null cells preserved

- **WHEN** a selected enrichment column has no matching enrichment row for some result row
- **THEN** that row still contains the column's key with a JSON `null` value

#### Scenario: Unnamed expression is keyed by its derived name

- **WHEN** a client posts `{"sql": "SELECT count(*) FROM dial_usage_log"}` to `/v1/queries/execute-sql`
- **THEN** each row carries the key `count` and no `EXPR$0` key
