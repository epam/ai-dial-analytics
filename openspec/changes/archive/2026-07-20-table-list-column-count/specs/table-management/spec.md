# Delta: table-management (table-list-column-count)

## MODIFIED Requirements

### Requirement: Read and list table definitions

The system SHALL expose `GET /v1/tables/{name}` returning a single definition including its current
`columns` (404 if absent) and `GET /v1/tables` returning all definitions — sources, enrichments, and
aggregations — as a response object (not a bare array). Listing SHALL use a stable order. Each list
item SHALL carry a `column_count` field: the number of exposed columns of that table, equal to the
length of the `columns` array `GET /v1/tables/{name}` returns for the same table (`0` for a
definition with no exposed columns yet); `column_count` SHALL NOT appear on the single-get response.
System columns (reserved `_`-prefix) and the hidden grain key SHALL NOT appear in the exposed
`columns` and SHALL NOT be counted by `column_count`. Each exposed column SHALL carry its optional
user-friendly `display_name` (absent/null when unset), its optional identifying `tag` (absent/null
when unset), and its `sensitive` flag (default `false` when unset). Each returned definition SHALL
carry its `system` flag (default `false` when unset) so operators can see which tables are protected
from modification. The table-management read surface is not column-role-filtered — it returns every
column with its `sensitive` flag so operators can audit and manage sensitivity; role-based hiding of
sensitive columns applies to the query/discovery surface (see
[query-schema-discovery](../query-schema-discovery/spec.md)), not to `/v1/tables`.

#### Scenario: Get a definition with its columns

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response is 200 with the definition's `name`, `description`, `type`, `status`,
  `system` flag, structural fields (`ordering_key`/`partition_by` or
  `source_table`/`grain_key`/`cardinality`), and its exposed `columns`

#### Scenario: Get an unknown definition

- **WHEN** `GET /v1/tables/{name}` is called for a name not in the catalog
- **THEN** the response is 404 with error code `not_found`

#### Scenario: System flag is returned

- **WHEN** `GET /v1/tables/{name}` is called for `dial_usage_log` (a seeded system table) and for a
  user-created table
- **THEN** the seeded table's response carries `system=true` and the user-created table's response
  carries `system=false`

#### Scenario: Column display name declared at create is returned

- **WHEN** a source is created with a column carrying a `display_name`, then `GET /v1/tables/{name}` is
  called
- **THEN** that column in the response carries the declared `display_name`, while columns that did not
  declare a display name carry a null `display_name`

#### Scenario: Column tag declared at create is returned

- **WHEN** a source is created with a column carrying a `tag`, then `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries the declared `tag`

#### Scenario: Column sensitive flag declared at create is returned

- **WHEN** a source is created with a column declared `sensitive: true`, then `GET /v1/tables/{name}`
  is called
- **THEN** that column in the response carries `sensitive: true`, while columns that did not declare
  the flag carry `sensitive: false`

#### Scenario: List includes all types

- **WHEN** `GET /v1/tables` is called
- **THEN** the response object lists every definition, including enrichment and aggregation
  definitions, in a stable order

#### Scenario: List items carry the exposed-column count

- **WHEN** a source is created with 3 columns and `GET /v1/tables` is called
- **THEN** that source's list item carries `column_count: 3` and no `columns` array

#### Scenario: Column count is zero for a column-less definition

- **WHEN** an enrichment is created (a grain shell with no exposed columns yet) and `GET /v1/tables`
  is called
- **THEN** that enrichment's list item carries `column_count: 0`

#### Scenario: Column count tracks schema evolution

- **WHEN** a table's schema is patched to add one column and drop one column, then `GET /v1/tables`
  is called
- **THEN** the table's `column_count` equals the length of the `columns` array returned by
  `GET /v1/tables/{name}`

#### Scenario: Column count absent on single-get

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response carries the `columns` array and no `column_count` field
