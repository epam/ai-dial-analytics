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

It SHALL also expose `request_cache_markers` (see the data-sync capability, "A chat request body is stored as
its new history and a parent link"), under the qualified name `"dial_usage_log_payload.request_cache_markers"`.
It SHALL be added by a later catalog migration as a new `column_mapping` row of type `string`, `nullable`,
`sensitive` and `heavy`, with a description, placed after the existing columns; for a row whose request body is
stored whole it reads as `NULL`. The same migration SHALL extend the seeded description of `request_body` to say
that a chat request in delta form carries only its new history, replacing it only while it is still the seeded
text.

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
`request_path`, `request_parent`, `request_depth`, `request_ancestors` and `request_tools_hash`, the
`request_inherited` key list, and the identity columns `project_id`,
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
  `request_cache_markers`

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
- **THEN** the schema carries `"dial_usage_log_payload.request_cache_markers"`, reported `sensitive: true` and
  `heavy: true`, and carries none of the chain columns nor `request_inherited`

#### Scenario: A caller without sensitive access does not see the cache markers

- **WHEN** a caller not entitled to sensitive columns reads the schema of `dial_usage_log`
- **THEN** `request_cache_markers` is not listed, exactly as for the body columns

### Requirement: Seeded heavy columns on the built-in source tables

The catalog migrations SHALL seed the `heavy` flag on the built-in tables' raw-payload columns
so that a wildcard projection over the seeded schema is cheap by default. The seeded heavy set SHALL
be `dial_usage_log_payload.request_body`, `dial_usage_log_payload.response_body`,
`dial_usage_log_payload.assembled_response` and `dial_usage_log_payload.request_cache_markers` — the verbatim
LLM request, the verbatim response, the producer's merged response, and the cache markers a chat request's
history loses under `sync.request-body-mode=delta` (see the data-sync capability), the only seeded columns whose
per-row size is unbounded. Every other
seeded column SHALL default to `heavy = false`, including the `object`-typed
`dial_usage_log_payload.jwt_claims` and `dial_usage_log_payload.request_tags` (small claim/header
documents) and `rate_analytics.comment` (a short free-text note). `heavy` and payload ownership SHALL
remain independent: the claim/header pair lives on the enrichment without being heavy.

All four seeded heavy columns are also seeded `sensitive` (see "Seeded column sensitivity on the
built-in source tables"), so for a caller without `TABLE_SENSITIVE` on the owning table they are
already absent from the
model and the heavy flag changes nothing.

For an entitled caller the flag is now belt-and-braces on a wildcard over `dial_usage_log`: every
heavy seeded column is an enrichment column, and a wildcard never fans out into an enrichment join, so
the four are omitted whether or not they are flagged. The flag remains load-bearing where the
enrichment rule does not reach — a wildcard's expansion is still reported with `heavy` in discovery,
the `validate-sql` heavy-column warning is keyed on it, and an operator flipping it through the API
still changes what a wildcard over a non-seeded table returns.

The claim and header columns are excluded from a wildcard over `dial_usage_log` too, but by the
enrichment rule alone rather than by the heavy flag — which is what shows the two rules are
independent.

#### Scenario: Seeded heavy flags after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log_payload` reports `request_body`, `response_body`, `assembled_response` and
  `request_cache_markers` with `heavy: true`, and every other column of `dial_usage_log`, `dial_usage_log_payload` and
  `rate_analytics` with `heavy: false`

#### Scenario: Management responses expose the seeded heavy flag

- **WHEN** a caller holding `TABLE_GET` reads `GET /v1/tables/dial_usage_log_payload`
- **THEN** the response lists `response_body` with `heavy: true` alongside its `sensitive: true`, so
  an operator can audit which columns a wildcard omits

#### Scenario: A wildcard omits the merged response

- **WHEN** an entitled caller runs a wildcard projection over `dial_usage_log`
- **THEN** no row carries an `assembled_response`, `request_body`, `response_body`, `jwt_claims` or
  `request_tags` key under any spelling — the last two excluded for being enrichment columns despite
  not being heavy — and naming any of them by its qualified name returns its value in full

### Requirement: Seeded column sensitivity on the built-in source tables

The catalog migrations SHALL seed the `sensitive` flag on the built-in tables so that columns
carrying caller identity or raw user content are restricted to a caller holding the clearance
`TABLE_SENSITIVE` on the table that owns them, globally or granted on that table, alongside the verb
it uses. The restriction applies by
default (see the `authentication` capability's "Sensitive column authorization" for the enforcement
rule). The seeded sensitive set SHALL be `dial_usage_log_payload.jwt_claims`,
`dial_usage_log_payload.request_tags`, `dial_usage_log_payload.request_body`,
`dial_usage_log_payload.response_body`, `dial_usage_log_payload.assembled_response`,
`dial_usage_log_payload.request_cache_markers`, and `rate_analytics.comment`. Every other seeded column SHALL
default to `sensitive = false`.

`request_cache_markers` SHALL be seeded sensitive on the same grounds as `request_body`: under
`sync.request-body-mode=delta` it holds part of that body's content, the cache markers taken out of its history,
so storing a request in parts SHALL NOT relax its sensitivity.

All six SHALL carry the flag on `dial_usage_log_payload` because that is where they are stored;
re-homing them SHALL NOT relax it. `rate_analytics.comment` SHALL therefore be the only sensitive
SOURCE column left in the seeded catalog — which matters for any surface reporting an entity's
wildcard schema, since an enrichment column is absent from one for every caller regardless of role. Enforcement is unchanged by the move: the flag is applied
when the column enters the query model, and an enrichment column enters it under its qualified name,
so an unentitled caller sees neither spelling and a reference to either fails as an unknown field.

`dial_usage_log_payload.assembled_response` SHALL be seeded sensitive on the same grounds as the two
body columns: it stores the model's answer verbatim, which is user-visible content, and for a streamed
call it is the *only* form in which that answer can be read without reassembling a chunk transcript.
That it is a merged rather than a raw payload changes its shape, not its sensitivity.

`dial_usage_log_payload.request_tags` SHALL be seeded sensitive because it stores the producer's request
`headers` object verbatim — the ingest mapping applies no allowlist or denylist — so it may carry
credentials, cookies, forwarded client addresses, and per-user correlation identifiers. It is
classified by what it stores rather than by its intended "routing metadata" role.

`dial_usage_log.user_hash` SHALL NOT be seeded sensitive: it is a de-identified per-user surrogate
whose purpose is to let unentitled callers run per-user analytics (distinct-user counts, per-user
rollups) without exposing identity.

Sensitivity is catalog metadata, not an immutable property: a caller holding `TABLE_MODIFY` and
`TABLE_SENSITIVE` on the table MAY change a column's flag via `PATCH /v1/tables/{name}/schema`, and the
catalog row is thereafter authoritative.
Seeding is applied by a `column_mapping` update only — the flag is never part of the ClickHouse DDL, so
no seeding change materializes as an `ALTER`. The seeded tables are the exception in practice: all
three are `system`, so every modifying `/v1/tables` operation on them is refused and their seeded flags
are changed only by a further migration.

#### Scenario: Seeded sensitivity is present after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log` reports `jwt_claims` and `request_tags` with `sensitive: true` and
  `user_hash` with `sensitive: false`, `dial_usage_log_payload` reports all six of its exposed columns with
  `sensitive: true`, and `rate_analytics` reports `comment` with `sensitive: true`

#### Scenario: Management responses expose the seeded flag unfiltered

- **WHEN** a caller holding `TABLE_GET` and `TABLE_SENSITIVE` reads `GET /v1/tables/dial_usage_log_payload`
- **THEN** the response lists `request_tags` with `sensitive: true`
- **AND WHEN** a caller holding `TABLE_GET` but not `TABLE_SENSITIVE` reads the same table
- **THEN** `request_tags` is absent from its `columns`

#### Scenario: A seeded sensitive column can be unrestricted by an operator

- **WHEN** a caller holding `TABLE_MODIFY` and `TABLE_SENSITIVE` sends `PATCH /v1/tables/{name}/schema` with
  `{"update":[{"name":"<column>","sensitive":false}]}` for a **non-`system`** table whose column was
  flagged sensitive
- **THEN** the request succeeds without any ClickHouse `ALTER`, and the column is thereafter visible to
  unentitled callers on the query surface — while the same request against a seeded `system`
  table is refused with HTTP 409

#### Scenario: A non-admin caller sees no spelling of a body column

- **WHEN** a caller without `TABLE_SENSITIVE` on the owning table reads
  `GET /v1/queries/entities/schema/dial_usage_log` and
  then queries `"dial_usage_log_payload.request_body"`
- **THEN** the schema carries no body column, and the query is rejected as an unknown field without
  revealing that the column exists
