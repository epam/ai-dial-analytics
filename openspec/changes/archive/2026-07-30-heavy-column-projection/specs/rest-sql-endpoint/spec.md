## ADDED Requirements

### Requirement: Star projections omit heavy and enrichment columns

`POST /v1/queries/execute-sql` SHALL inherit the star expansion of the shared SQL front end (see the
`sql-query-translation` capability's "Star projection expands to the non-heavy source columns"): a
`SELECT *` response SHALL carry a row key per **non-heavy source** column and none for a `heavy` column
or an enrichment column (**BREAKING** — a star previously returned enrichment columns too), while a
statement that names a heavy or enrichment column SHALL return it unchanged. A star whose expansion
would be empty SHALL be rejected with HTTP 400 `bad_request` in the `ErrorView` shape, before any
ClickHouse contact.

The equivalence with the DSL endpoint SHALL hold for every entity, enrichments attached or not:
`SELECT *` and a row-mode document with no `select` SHALL return the same columns.

#### Scenario: Star response omits the heavy column

- **WHEN** a client posts `{ "sql": "SELECT * FROM dial_usage_log LIMIT 1" }` as a caller entitled to
  see `response_body`, which is marked `heavy`
- **THEN** the response is HTTP 200 and no row carries a `response_body` key, while every non-heavy
  column key is present

#### Scenario: Named heavy column comes back

- **WHEN** the same caller posts `{ "sql": "SELECT event_id, response_body FROM dial_usage_log LIMIT 1" }`
- **THEN** the response carries both keys with their values

#### Scenario: Star and empty DSL select agree

- **WHEN** a client posts `SELECT * FROM <source>` for a source with an attached enrichment, and the
  equivalent row-mode document with no `select` to `POST /v1/queries/execute`
- **THEN** both responses carry the same column keys — the non-heavy source columns, with no
  enrichment column in either

#### Scenario: Enrichment column returned when named

- **WHEN** a client posts `{ "sql": "SELECT event_id, \"deployment_ref.display_name\" FROM dial_usage_log LIMIT 1" }`
- **THEN** the response carries the enrichment column under its qualified flat name, with a JSON `null`
  where no enrichment row matches
