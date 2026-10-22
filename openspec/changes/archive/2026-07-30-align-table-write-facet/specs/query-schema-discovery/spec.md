## MODIFIED Requirements

### Requirement: Queryable entity catalog

The system SHALL expose, at `GET /v1/queries/entities`, the list of entities that can be named as the
`entity` of a structured query. Queryable entities SHALL be derived from the table-management catalog:
each `ACTIVE` `source` table is one queryable entity, of either write discipline — an `upsert_by_key`
source (a rollup target) is listed exactly like an `append` source, with no separate table kind for
aggregation. Enrichment tables SHALL
NOT be listed as queryable entities — enrichments are surfaced only as flattened columns on their
source (see the base-schema requirement). Each catalog item SHALL carry the entity wire `name`.
Entities SHALL be listed in stable alphabetical order by name.

#### Scenario: Catalog lists queryable source entities

- **WHEN** `GET /v1/queries/entities` is called
- **THEN** the response lists each materialized `source` table as a queryable entity in alphabetical
  order, including `dial_usage_log`, and does not list enrichment tables

#### Scenario: An upsert-by-key source is listed

- **WHEN** an `upsert_by_key` source is materialized and `GET /v1/queries/entities` is called
- **THEN** it appears in the list in alphabetical order alongside the `append` sources, and
  `GET /v1/queries/entities/schema/{name}` returns its flat schema
