## ADDED Requirements

### Requirement: Seeded payload enrichment on dial_usage_log

The catalog migrations SHALL seed a third built-in table, `dial_usage_log_payload`, as an
`enrichment` of `dial_usage_log` with `status=ACTIVE`, `system=true` and `grain_key=event_id`, and
SHALL therefore report `cardinality` as `ZERO_OR_ONE` (a derived facet of having a grain key, not a
stored one). It SHALL expose exactly three columns — `request_body`, `response_body`,
`assembled_response` — which SHALL resolve into `dial_usage_log`'s flat schema under the qualified
names `"dial_usage_log_payload.request_body"`, `"dial_usage_log_payload.response_body"` and
`"dial_usage_log_payload.assembled_response"`, by the ordinary grain join (see "Grain-based enrichment
join with enforced cardinality").

**BREAKING**: those five columns SHALL NOT be exposed as columns of `dial_usage_log`. A query naming
any of them as a bare field of `dial_usage_log` SHALL be rejected as an unknown field, exactly as any
other undeclared name is.

The seed SHALL move the five existing `column_mapping` rows rather than re-declare them, so every
per-column attribute — `sensitive`, `heavy`, `nullable`, `description`, `display_name`, `tag` — is
carried over unchanged and continues to be reported by `GET /v1/tables/dial_usage_log_payload` and by
schema discovery under the qualified name. Vacating those ordinals MAY leave gaps in
`dial_usage_log`'s column order; the surviving columns SHALL keep the relative order they had.

The definition's physical columns beyond the three exposed ones — its `event_id` grain key,
`request_time`, `trace_id`, `core_span_id`, and its `_updated_at` version column — SHALL NOT appear in
the exposed schema, matching the existing rule that an enrichment's grain key is a hidden physical
column.

The definition's TTL fields (`ttl_column`, `ttl_interval`, `ttl_unit`) SHALL be unset, because a
seeded system table's retention is owned by its ClickHouse migration and is not mirrored into a catalog
the API could try to modify (see "Table TTL").

#### Scenario: The payload enrichment is present after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** it contains `dial_usage_log_payload` as an `enrichment` with `source_table=dial_usage_log`,
  `status=ACTIVE`, `system=true`, `grain_key=event_id`, and its three exposed columns

#### Scenario: Body columns resolve under their qualified names

- **WHEN** `GET /v1/queries/entities/schema/dial_usage_log` is called by a `FULL_ADMIN` caller
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

- **WHEN** a `FULL_ADMIN` caller reads `GET /v1/tables/dial_usage_log_payload`
- **THEN** all three columns report `sensitive: true` and `heavy: true`, and each keeps the
  description and display name it carried on `dial_usage_log`

#### Scenario: The payload enrichment is not a queryable entity of its own

- **WHEN** a query targets `dial_usage_log_payload` as its entity
- **THEN** the request is rejected as an unknown entity, because only an `ACTIVE` `source` is
  queryable — the body columns are reachable only through their parent's grain join

#### Scenario: The payload enrichment rejects modification

- **WHEN** any of `PUT /v1/tables/dial_usage_log_payload`,
  `POST|PATCH /v1/tables/dial_usage_log_payload/schema`, `DELETE /v1/tables/dial_usage_log_payload`, or
  `POST /v1/tables/dial_usage_log_payload/rows` is called, by any caller including a `FULL_ADMIN`
- **THEN** the request is rejected with HTTP 409 and the system-owned `error` code, and no ClickHouse
  DDL or write is issued

### Requirement: An object column is always non-nullable

An `object` column SHALL resolve to `nullable = false` on every table kind, and a declaration setting
`nullable: true` on one SHALL be refused with a `422` naming the column. This matches the rules
already in force for `array` and `map`, and for the same reason: ClickHouse has no `Nullable(JSON)`,
so the DDL renderer cannot emit the nullability the catalog would otherwise claim.

The divergence this closes SHALL be recorded, because it was silent. An `object` column on an
`ENRICHMENT` previously inherited that kind's nullable-by-default, and an explicit `nullable: true`
was accepted — while the renderer emitted a plain `JSON` either way. The stored flag is what the
enrichment-read rule consults to decide whether an unmatched grain join yields `NULL`, so the guard
was inoperative for exactly the type whose physical form can never satisfy it.

#### Scenario: An enrichment's object column defaults to non-nullable

- **WHEN** an operator creates an `ENRICHMENT` declaring an `object` column with no `nullable` field
- **THEN** the column is stored with `nullable: false`, matching the `JSON` column the DDL renders

#### Scenario: An explicitly nullable object column is refused

- **WHEN** an operator declares an `object` column with `nullable: true`
- **THEN** the request is refused with `422`, naming the column and stating that ClickHouse has no
  `Nullable(JSON)` and that an absent document is the empty document

## MODIFIED Requirements

### Requirement: System-owned table protection

The system SHALL support a table-level `system` boolean flag on each table definition marking it as
owned and managed by ADAS itself (materialized out of band and, for the seeded tables, populated by
the `data-sync` ingest job writing directly to ClickHouse). The flag SHALL be server-controlled
catalog metadata: it SHALL be set only by database migration, SHALL default to `false` for every
definition, and SHALL NOT be settable, clearable, or otherwise mutable through any `/v1/tables`
request. `POST /v1/tables` SHALL ignore any client-supplied `system` value and always persist a
newly created table as `system=false`; no `/v1/tables` update or schema operation SHALL ever change
`system`. "Ignore" SHALL mean *accepted and not honoured*, not *refused*: the flag is echoed by every
table read, so a caller creating a table from one it read back sends it without choosing to, and a
request body that declines fields it does not declare would otherwise reject that body over a value the
server was always going to overwrite. The three seeded tables — the sources `dial_usage_log` and
`rate_analytics`, and the enrichment `dial_usage_log_payload` — SHALL be `system=true` after migration.
The flag is a table facet independent of `type`: it says who owns the definition, not what the
definition is, so a seeded enrichment is protected on exactly the same terms as a seeded source.

A `system` table SHALL be fully readable, listable, and queryable, but SHALL reject every modifying
`/v1/tables` operation — `PUT /v1/tables/{name}` (description/tag_order), `POST /v1/tables/{name}/schema`
(define + materialize), `PATCH /v1/tables/{name}/schema`, `DELETE /v1/tables/{name}`, and
`POST /v1/tables/{name}/rows` — with HTTP 409 and the shared
`ErrorView` contract using a stable `error` code identifying the table as system-owned, without
performing the operation or issuing any ClickHouse DDL or write. The check SHALL apply regardless of
caller role (a `FULL_ADMIN` is refused just as any other caller is).

#### Scenario: Seeded tables are system after migration

- **WHEN** the catalog is read after the system-table-flag migration runs
- **THEN** `dial_usage_log`, `rate_analytics` and `dial_usage_log_payload` carry `system=true`, and
  every other definition carries `system=false`

#### Scenario: Create never sets system

- **WHEN** `POST /v1/tables` is called, including with a body that attempts to supply `system=true`
- **THEN** the created definition is persisted with `system=false`

#### Scenario: Update of a system table is rejected

- **WHEN** `PUT /v1/tables/{name}` is called for a `system` table
- **THEN** the request is rejected with HTTP 409 using the `ErrorView` contract with the
  system-owned `error` code, and nothing is changed

#### Scenario: Defining the schema of a system table is rejected

- **WHEN** `POST /v1/tables/{name}/schema` is called for a `system` table
- **THEN** the request is rejected with HTTP 409, and no schema is stored and no ClickHouse DDL is issued

#### Scenario: Schema PATCH of a system table is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` is called for a `system` table
- **THEN** the request is rejected with HTTP 409, no ClickHouse `ALTER` is issued, and the column
  mapping is unchanged

#### Scenario: Delete of a system table is rejected

- **WHEN** `DELETE /v1/tables/{name}` is called for a `system` table
- **THEN** the request is rejected with HTTP 409, no `DROP TABLE` is issued, and the catalog rows
  remain

#### Scenario: Row write to a system table is rejected

- **WHEN** `POST /v1/tables/{name}/rows` is called for a `system` table
- **THEN** the request is rejected with HTTP 409, no rows are inserted, and no ClickHouse write is
  issued

#### Scenario: System table stays readable and queryable

- **WHEN** `GET /v1/tables/{name}`, `GET /v1/tables`, or a query under `/v1/queries` targets a
  `system` table
- **THEN** the request succeeds exactly as for a non-system table (the flag restricts only the
  modifying `/v1/tables` operations)

### Requirement: Seeded column nullability matches the physical table

The catalog's `nullable` flag on a built-in table SHALL agree with the column's physical
ClickHouse type: a column materialized as `Nullable(T)` SHALL be seeded `nullable = true`, and one
materialized as a bare `T` SHALL be seeded `nullable = false`.

The `dial_usage_log` seed SHALL therefore record as nullable exactly the columns its DDL declares
`Nullable(...)`: `core_parent_span_id`, `parent_deployment`, `response_upstream_uri`, `prompt_tokens`,
`completion_tokens`, `total_tokens`, `cached_prompt_tokens`, `cache_creation_tokens`, `total_price`,
and `deployment_price`.

The `dial_usage_log_payload` seed SHALL record all three of its columns as nullable — they are
`Nullable(String)`, so that "the producer sent nothing" stays distinguishable from "the producer sent
empty". Their nullability is also what makes them readable by a pipeline through the grain join, which
a non-nullable enrichment column is not.

The agreement holds against the table's *current* physical type, not the type it was created with: when
a migration changes a column's nullability, a paired catalog update SHALL follow it in the same change,
so the two never disagree between releases. Moving a column's catalog row between tables SHALL likewise
preserve the flag, since the physical type it describes is unchanged.

The agreement is a requirement rather than a data detail because two independent consumers read the flag
and neither can detect that it is wrong:

- **Discovery publishes it.** A consumer told a column cannot be null, which then returns null, holds a
  schema its own data contradicts.
- **Governance reasons from it.** A rule that decides anything from a column's nullability — such as the
  aggregate pipeline's requirement that a nullable measure land in a nullable target column, or the
  enrichment-read rule that refuses a non-nullable enrichment column — is inert on precisely the
  columns that need it if the catalog understates nullability. That is the quiet direction of the
  error: nothing is rejected, and nothing reports that nothing was checked.

The flag defaults to `FALSE`, so a column seeded without an explicit value claims non-nullability it may
not have. A seed SHALL therefore set it from the DDL rather than relying on the default.

Nullability is fixed at DDL, so aligning the catalog SHALL be a `column_mapping` update only and SHALL
NOT materialize as an `ALTER`. This is the same shape as the seeded `sensitive` flag, and unlike it, the
value is not an operator's to choose — the physical column decides it.

#### Scenario: The seeded flag agrees with the physical column

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log` reports `total_tokens`, `total_price` and the other physically-`Nullable`
  columns with `nullable: true`, and its non-nullable columns with `nullable: false`

#### Scenario: The moved body columns keep their nullability

- **WHEN** the catalog is read after the payload-split migrations run
- **THEN** `dial_usage_log_payload` reports all three of its columns with `nullable: true`, and
  `dial_usage_log` reports none of them at all

#### Scenario: A column made nullable by a later migration

- **WHEN** a ClickHouse migration changes a `dial_usage_log` column from `T` to `Nullable(T)`
- **THEN** the catalog reports that column as `nullable: true` once the catalog migrations of the same
  release have run, without any `ALTER` being issued from the catalog side

#### Scenario: A governance rule keyed on nullability sees the true value

- **WHEN** an aggregate pipeline declares a `sum` over `dial_usage_log.total_tokens` into a non-nullable
  target column
- **THEN** it is rejected, because the catalog reports the input column as nullable — the refusal the
  rule exists for, which an understated flag would have skipped silently

### Requirement: Seeded column sensitivity on the built-in source tables

The catalog migrations SHALL seed the `sensitive` flag on the built-in tables so that columns
carrying caller identity or raw user content are restricted to `FULL_ADMIN` on the query surface by
default (see the `authentication` capability's "Sensitive column authorization" for the enforcement
rule). The seeded sensitive set SHALL be `dial_usage_log_payload.jwt_claims`,
`dial_usage_log_payload.request_tags`, `dial_usage_log_payload.request_body`,
`dial_usage_log_payload.response_body`, `dial_usage_log_payload.assembled_response`, and
`rate_analytics.comment`. Every other seeded column SHALL default to `sensitive = false`.

All five SHALL carry the flag on `dial_usage_log_payload` because that is where they are stored;
re-homing them SHALL NOT relax it. `rate_analytics.comment` SHALL therefore be the only sensitive
SOURCE column left in the seeded catalog — which matters for any surface reporting an entity's
wildcard schema, since an enrichment column is absent from one for every caller regardless of role. Enforcement is unchanged by the move: the flag is applied
when the column enters the query model, and an enrichment column enters it under its qualified name,
so a non-`FULL_ADMIN` caller sees neither spelling and a reference to either fails as an unknown field.

`dial_usage_log_payload.assembled_response` SHALL be seeded sensitive on the same grounds as the two
body columns: it stores the model's answer verbatim, which is user-visible content, and for a streamed
call it is the *only* form in which that answer can be read without reassembling a chunk transcript.
That it is a merged rather than a raw payload changes its shape, not its sensitivity.

`dial_usage_log_payload.request_tags` SHALL be seeded sensitive because it stores the producer's request
`headers` object verbatim — the ingest mapping applies no allowlist or denylist — so it may carry
credentials, cookies, forwarded client addresses, and per-user correlation identifiers. It is
classified by what it stores rather than by its intended "routing metadata" role.

`dial_usage_log.user_hash` SHALL NOT be seeded sensitive: it is a de-identified per-user surrogate
whose purpose is to let non-`FULL_ADMIN` roles run per-user analytics (distinct-user counts, per-user
rollups) without exposing identity.

Sensitivity is catalog metadata, not an immutable property: a `FULL_ADMIN` operator MAY change a
column's flag via `PATCH /v1/tables/{name}/schema`, and the catalog row is thereafter authoritative.
Seeding is applied by a `column_mapping` update only — the flag is never part of the ClickHouse DDL, so
no seeding change materializes as an `ALTER`. The seeded tables are the exception in practice: all
three are `system`, so every modifying `/v1/tables` operation on them is refused and their seeded flags
are changed only by a further migration.

#### Scenario: Seeded sensitivity is present after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log` reports `jwt_claims` and `request_tags` with `sensitive: true` and
  `user_hash` with `sensitive: false`, `dial_usage_log_payload` reports all three of its columns with
  `sensitive: true`, and `rate_analytics` reports `comment` with `sensitive: true`

#### Scenario: Management responses expose the seeded flag unfiltered

- **WHEN** a `FULL_ADMIN` caller reads `GET /v1/tables/dial_usage_log`
- **THEN** the response lists `request_tags` with `sensitive: true` — the `/v1/tables` surface is not
  role-filtered, so an operator can always audit which columns are restricted

#### Scenario: A seeded sensitive column can be unrestricted by an operator

- **WHEN** a `FULL_ADMIN` caller sends `PATCH /v1/tables/{name}/schema` with
  `{"update":[{"name":"<column>","sensitive":false}]}` for a **non-`system`** table whose column was
  flagged sensitive
- **THEN** the request succeeds without any ClickHouse `ALTER`, and the column is thereafter visible to
  non-`FULL_ADMIN` callers on the query surface — while the same request against a seeded `system`
  table is refused with HTTP 409

#### Scenario: A non-admin caller sees no spelling of a body column

- **WHEN** a caller who is not `FULL_ADMIN` reads `GET /v1/queries/entities/schema/dial_usage_log` and
  then queries `"dial_usage_log_payload.request_body"`
- **THEN** the schema carries no body column, and the query is rejected as an unknown field without
  revealing that the column exists

### Requirement: Seeded heavy columns on the built-in source tables

The catalog migrations SHALL seed the `heavy` flag on the built-in tables' raw-payload columns
so that a wildcard projection over the seeded schema is cheap by default. The seeded heavy set SHALL
be `dial_usage_log_payload.request_body`, `dial_usage_log_payload.response_body`, and
`dial_usage_log_payload.assembled_response` — the verbatim LLM request, the verbatim response, and the
producer's merged response, the only seeded columns whose per-row size is unbounded. Every other
seeded column SHALL default to `heavy = false`, including the `object`-typed
`dial_usage_log_payload.jwt_claims` and `dial_usage_log_payload.request_tags` (small claim/header
documents) and `rate_analytics.comment` (a short free-text note). `heavy` and payload ownership SHALL
remain independent: the claim/header pair lives on the enrichment without being heavy.

All three seeded heavy columns are also seeded `sensitive` (see "Seeded column sensitivity on the
built-in source tables"), so for a caller who is not `FULL_ADMIN` they are already absent from the
model and the heavy flag changes nothing.

For a `FULL_ADMIN` caller the flag is now belt-and-braces on a wildcard over `dial_usage_log`: every
heavy seeded column is an enrichment column, and a wildcard never fans out into an enrichment join, so
the three bodies are omitted whether or not they are flagged. The flag remains load-bearing where the
enrichment rule does not reach — a wildcard's expansion is still reported with `heavy` in discovery,
the `validate-sql` heavy-column warning is keyed on it, and an operator flipping it through the API
still changes what a wildcard over a non-seeded table returns.

The claim and header columns are excluded from a wildcard over `dial_usage_log` too, but by the
enrichment rule alone rather than by the heavy flag — which is what shows the two rules are
independent.

#### Scenario: Seeded heavy flags after migration

- **WHEN** the catalog is read after the table-catalog migrations run
- **THEN** `dial_usage_log_payload` reports `request_body`, `response_body`, and `assembled_response`
  with `heavy: true`, and every other column of `dial_usage_log`, `dial_usage_log_payload` and
  `rate_analytics` with `heavy: false`

#### Scenario: Management responses expose the seeded heavy flag

- **WHEN** a `FULL_ADMIN` caller reads `GET /v1/tables/dial_usage_log_payload`
- **THEN** the response lists `response_body` with `heavy: true` alongside its `sensitive: true`, so
  an operator can audit which columns a wildcard omits

#### Scenario: A wildcard omits the merged response

- **WHEN** a `FULL_ADMIN` caller runs a wildcard projection over `dial_usage_log`
- **THEN** no row carries an `assembled_response`, `request_body`, `response_body`, `jwt_claims` or
  `request_tags` key under any spelling — the last two excluded for being enrichment columns despite
  not being heavy — and naming any of them by its qualified name returns its value in full
