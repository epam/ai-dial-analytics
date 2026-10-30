## MODIFIED Requirements

### Requirement: Seeded payload enrichment on dial_usage_log

The catalog migrations SHALL seed a third built-in table, `dial_usage_log_payload`, as an
`enrichment` of `dial_usage_log` with `status=ACTIVE`, `system=true` and `grain_key=event_id`, and
SHALL therefore report `cardinality` as `ZERO_OR_ONE` (a derived facet of having a grain key, not a
stored one). It SHALL expose the three body columns — `request_body`, `response_body`,
`assembled_response` — which SHALL resolve into `dial_usage_log`'s flat schema under the qualified
names `"dial_usage_log_payload.request_body"`, `"dial_usage_log_payload.response_body"` and
`"dial_usage_log_payload.assembled_response"`, by the ordinary grain join (see "Grain-based enrichment
join with enforced cardinality").

It SHALL also expose `request_cache_control` (see the data-sync capability, "A chat request body is stored as
its new history and a parent link"), under the qualified name `"dial_usage_log_payload.request_cache_control"`.
It SHALL be added by a later catalog migration as a new `column_mapping` row of type `string`, `nullable`,
`sensitive` and `heavy`, with a description, placed after the existing columns; for a row whose request body is
stored whole it reads as `NULL`. The same migration SHALL extend the seeded description of `request_body` to say
that a chat request in delta form carries only its new history, replacing it only while it is still the seeded
text.

The payload enrichment's part of the entity schema SHALL also carry two **computed fields**,
`"dial_usage_log_payload.request_body_full"` (`string`, nullable) and `"dial_usage_log_payload.request_body_rebuild"`
(`enum` with values `stored`, `rebuilt`, `partial`, nullable), specified by the data-sync capability ("A rebuilt
request body is readable beside the stored one"). They SHALL be defined by the service, not by catalog rows: no
`column_mapping` row and no migration SHALL exist for them, and nothing a catalog row drives — `GET
/v1/tables/dial_usage_log_payload`, schema `PATCH`, row writes, row policies, grants, catalog export and import,
relayout and the DDL renderer — SHALL list, accept or act on them. Each SHALL be `heavy`, SHALL be `sensitive`
when the `request_body` or the `request_cache_control` catalog row is, and SHALL be defined exactly when the
`request_body` catalog row is present, placed after the enrichment's catalog columns, `request_body_full` first. A
caller SHALL see them only when it may see every sensitive column among `request_body` and `request_cache_control`,
so a caller who may see `request_body` but not a sensitive `request_cache_control` sees neither field. Each SHALL carry a fixed description and
display name, and SHALL be reported `projection_only` (see the query-schema-discovery capability). A computed field
SHALL be projection-only on every surface: its value is completed after the statement that reads it has run.

**BREAKING**: those five columns SHALL NOT be exposed as columns of `dial_usage_log`. A query naming
any of them as a bare field of `dial_usage_log` SHALL be rejected as an unknown field, exactly as any
other undeclared name is.

The seed SHALL move the five existing `column_mapping` rows rather than re-declare them, so every
per-column attribute — `sensitive`, `heavy`, `nullable`, `description`, `display_name`, `tag` — is
carried over unchanged and continues to be reported by `GET /v1/tables/dial_usage_log_payload` and by
schema discovery under the qualified name. Vacating those ordinals MAY leave gaps in
`dial_usage_log`'s column order; the surviving columns SHALL keep the relative order they had.

The definition's physical columns beyond the exposed ones — its `event_id` grain key,
`request_time`, `trace_id`, `core_span_id`, its `_updated_at` version column, and the chain columns
`request_hash`, `request_parent_hash` and `request_ancestor_hashes`, and the identity columns `project_id`,
`deployment`, `user_hash` and `session_id` — SHALL NOT appear in
the exposed schema, matching the existing rule that an enrichment's grain key is a hidden physical
column.

The definition's TTL fields (`ttl_column`, `ttl_interval`, `ttl_unit`) SHALL be unset, because a
seeded system table's retention is owned by its ClickHouse migration and is not mirrored into a catalog
the API could try to modify (see "Table TTL").

#### Scenario: The payload enrichment is present after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** it contains `dial_usage_log_payload` as an `enrichment` with `source_table=dial_usage_log`,
  `status=ACTIVE`, `system=true`, `grain_key=event_id`, and its exposed columns, including
  `request_cache_control`

#### Scenario: Body columns resolve under their qualified names

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log` is called by a caller entitled to the
  payload table's sensitive columns
- **THEN** the schema carries `"dial_usage_log_payload.request_body"`,
  `"dial_usage_log_payload.response_body"` and `"dial_usage_log_payload.assembled_response"`, and
  carries none of those names unqualified

#### Scenario: The old bare column name no longer resolves

- **WHEN** a query names `request_body` as a field of `dial_usage_log`
- **THEN** it is rejected as an unknown field, with no ClickHouse query issued

#### Scenario: The claim and header columns move with the bodies

- **WHEN** a query names `jwt_claims` or `request_tags` as a bare field of `dial_usage_log`
- **THEN** it is rejected as an unknown field, and the value is reachable only as
  `"dial_usage_log_payload.jwt_claims"` / `"dial_usage_log_payload.request_tags"`

#### Scenario: Carried-over column attributes are unchanged

- **WHEN** a caller holding `TABLE_GET` reads `GET /v1/tables/dial_usage_log_payload`
- **THEN** all three columns report `sensitive: true` and `heavy: true`, and each keeps the
  description and display name it carried on `dial_usage_log`

#### Scenario: The payload enrichment is not a queryable entity of its own

- **WHEN** a query targets `dial_usage_log_payload` as its entity
- **THEN** the request is rejected as an unknown entity, because only an `ACTIVE` `source` is
  queryable — the body columns are reachable only through their parent's grain join

#### Scenario: The payload enrichment rejects modification

- **WHEN** any of `PUT /v1/tables/dial_usage_log_payload`,
  `POST|PATCH /v1/tables/dial_usage_log_payload/schema`, `DELETE /v1/tables/dial_usage_log_payload`, or
  `POST /v1/tables/dial_usage_log_payload/rows` is called, by any caller including one holding every
  privilege
- **THEN** the request is rejected with HTTP 409 and the system-owned `error` code, and no ClickHouse
  DDL or write is issued

#### Scenario: The cache markers resolve under their qualified name

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log` is called by a caller entitled to the
  payload table's sensitive columns
- **THEN** the schema carries `"dial_usage_log_payload.request_cache_control"`, reported `sensitive: true` and
  `heavy: true`, and carries none of the chain columns

#### Scenario: A caller without sensitive access does not see the cache markers

- **WHEN** a caller not entitled to sensitive columns reads the schema of `dial_usage_log`
- **THEN** `request_cache_control` is not listed, exactly as for the body columns

#### Scenario: The rebuild fields resolve in the entity schema

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log` is called by a caller entitled to the payload table's
  sensitive columns
- **THEN** the schema carries `"dial_usage_log_payload.request_body_full"` typed `string` and
  `"dial_usage_log_payload.request_body_rebuild"` typed `enum` with `enum_values` `["stored", "rebuilt", "partial"]`,
  both reported `sensitive: true`, `heavy: true` and `projection_only: true`, after the payload's catalog columns

#### Scenario: A caller without sensitive access does not see the rebuild fields

- **WHEN** a caller not entitled to the payload table's sensitive columns reads the schema of `dial_usage_log`
- **THEN** neither rebuild field is listed, and a query naming one is rejected as an unknown field

#### Scenario: The table read surface lists stored columns only

- **WHEN** a caller holding `TABLE_GET` reads `GET /v1/tables/dial_usage_log_payload`
- **THEN** the response lists the catalog columns, `request_body` and `request_cache_control` included, and lists
  neither rebuild field

#### Scenario: No catalog migration adds the rebuild fields

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `column_mapping` holds no row for `request_body_full` or `request_body_rebuild`
