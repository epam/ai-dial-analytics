## MODIFIED Requirements

### Requirement: Queryable entity catalog

The system SHALL expose, at `GET /v1/queries/entities`, the list of entities that can be named as the
`entity` of a structured query. Queryable entities SHALL be derived from the table-management catalog:
each `ACTIVE` `source` table is one queryable entity, of either write discipline — an `upsert_by_key`
source (a rollup target) is listed exactly like an `append` source, with no separate table kind for
aggregation. Enrichment tables SHALL
NOT be listed as queryable entities — enrichments are surfaced only as flattened columns on their
source (see the base-schema requirement). Each catalog item SHALL carry the entity wire `name` and a
boolean `supports_final`: `true` when the entity is final-capable — its source is `upsert_by_key`, or it has
an active enrichment the caller may read (`TABLE_READ`, the bar execution resolves the entity at, whatever
bar the listing itself filters at) — and `false` otherwise, so a caller can tell where a structured
query's `"final": true` and a SQL `FINAL` are legal and where they are rejected with `illegal_final` (see
[structured-query](../structured-query/spec.md) "Read result deduplication"). `supports_final` is also what
an absent `final` resolves to. It SHALL NOT reveal an enrichment the caller cannot read, and an enrichment
the caller may list or get but not read SHALL NOT make it `true`.
Entities SHALL be listed in stable alphabetical order by name.

#### Scenario: Catalog lists queryable source entities

- **WHEN** `GET /v1/queries/entities` is called
- **THEN** the response lists each materialized `source` table as a queryable entity in alphabetical
  order, including `dial_usage_log`, and does not list enrichment tables

#### Scenario: An upsert-by-key source is listed

- **WHEN** an `upsert_by_key` source is materialized and `GET /v1/queries/entities` is called
- **THEN** it appears in the list in alphabetical order alongside the `append` sources, and
  `GET /v1/queries/entities/schema/{name}` returns its flat schema

#### Scenario: Catalog items report whether FINAL is supported

- **WHEN** `GET /v1/queries/entities` is called with `dial_usage_log` (`upsert_by_key`), an `append` source
  with no enrichment, and an `append` source with an enrichment materialized
- **THEN** the `dial_usage_log` item and the enriched `append` source's item carry `"supports_final": true`,
  and the unenriched `append` source's item carries `"supports_final": false`

#### Scenario: An enrichment the caller cannot read does not make the entity final-capable

- **WHEN** a caller holds `list` and `get` but not `read` on the only enrichment of an `append` source it may
  read, and calls `GET /v1/queries/entities`
- **THEN** that source's item carries `"supports_final": false`, matching execution, where the enrichment is
  not joined and `"final": true` is rejected with `illegal_final`
