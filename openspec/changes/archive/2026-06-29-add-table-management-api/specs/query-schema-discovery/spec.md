## MODIFIED Requirements

### Requirement: Queryable entity catalog

The system SHALL expose, at `GET /v1/queries/entities`, the list of entities that can be named as the
`entity` of a structured query. Queryable entities SHALL be derived from the table-management catalog:
each `MATERIALIZED` `source` table is one queryable entity. Enrichment and aggregation tables SHALL
NOT be listed as queryable entities — enrichments are surfaced only as flattened columns on their
source (see the base-schema requirement). Each catalog item SHALL carry the entity wire `name`.
Entities SHALL be listed in stable alphabetical order by name.

#### Scenario: Catalog lists queryable source entities

- **WHEN** `GET /v1/queries/entities` is called
- **THEN** the response lists each materialized `source` table as a queryable entity in alphabetical
  order, including `dial_usage_log`, and does not list enrichment or aggregation tables

### Requirement: Instance-independent base schema

The system SHALL expose, at `GET /v1/queries/entities/schema/{name}`, the entity's flat schema sourced
from the table-management catalog. The flat schema SHALL be the union of the source table's exposed
columns and every attached enrichment's exposed columns; foreign-key (join) columns SHALL be hidden
and SHALL NOT appear. The source's columns SHALL be exposed under their bare exposed `name`, while each
enrichment column SHALL be exposed under a table-qualified flat name `<enrichment_table>.<name>` (dot
separator) so that a source column and an enrichment column — or two enrichments — may share the same
bare `name` without colliding. Because table and column names match `^[a-z][a-z0-9_]*$` (no dot),
qualified names are unambiguous and unique by construction. Each field SHALL carry its flat `name` (bare
for source columns, qualified for enrichment columns), its flat field type, the bare physical `source`
column it maps to, and the column's optional identifying `tag` (null when unset).

#### Scenario: Base schema lists the entity's flat fields

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log` is called
- **THEN** the response lists the entity's fields with their mapped types — e.g. `project_id` typed
  `string`, `total_tokens` typed `long`, `request_time` typed `timestamp`, and `execution_path` typed
  `array`

#### Scenario: Flat fields carry their column tag

- **WHEN** a source (or one of its enrichments) has a column with an identifying `tag` and
  `GET /v1/queries/entities/schema/{source}` is called
- **THEN** the corresponding flat field carries that `tag`, while fields whose columns have no tag carry
  a null `tag`

#### Scenario: Enrichment columns are flattened into the source schema

- **WHEN** a source has one or more attached enrichments and
  `GET /v1/queries/entities/schema/{source}` is called
- **THEN** the response lists the source's exposed columns under their bare `name` plus every
  enrichment's exposed columns under their table-qualified flat name `<enrichment_table>.<name>` as a
  single flat field list, excluding the enrichment foreign-key columns, and each enrichment field's
  `source` is the bare physical column name

#### Scenario: Two enrichments exposing the same column name are both surfaced

- **WHEN** a source has two enrichments that each expose a column with the same bare `name` and
  `GET /v1/queries/entities/schema/{source}` is called
- **THEN** the response lists both as distinct qualified flat fields (`<enrichment_a>.<name>` and
  `<enrichment_b>.<name>`) rather than rejecting the second enrichment

## REMOVED Requirements

### Requirement: Instance-specific detailed schema for complex entities

**Reason**: The `complex` / `schemaIdField` / `/detailed` per-instance schema mechanism was
speculative and unused (no entity was ever complex). It is superseded by source+enrichment flattening,
which presents related tables as one flat schema directly.

**Migration**: None required — the `/v1/queries/entities/schema/{name}/detailed` endpoint and the
`complex`/`schemaIdField` fields are removed; no client used them. Use the flat base schema at
`GET /v1/queries/entities/schema/{name}`, which now already includes enrichment columns.

## MODIFIED Requirements

### Requirement: Schema discovery error contract

The system SHALL reject invalid discovery requests with specific HTTP statuses: an unknown entity name
SHALL return 404. Errors SHALL use the shared `ErrorView` contract and MUST NOT leak internals.

#### Scenario: Unknown entity

- **WHEN** any schema endpoint is called with an entity name that is not registered as a queryable
  source
- **THEN** the response status is 404 with error code `not_found`
