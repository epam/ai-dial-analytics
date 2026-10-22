## MODIFIED Requirements

### Requirement: Read and list table definitions

The system SHALL expose `GET /v1/tables/{name}` returning a single definition including its current
`columns` (404 if absent) and `GET /v1/tables` returning all definitions — sources and enrichments —
as a response object (not a bare array). Listing SHALL use a stable order. Each list
item SHALL carry a `column_count` field: the number of exposed columns of that table, equal to the
length of the `columns` array `GET /v1/tables/{name}` returns for the same table (`0` for a
definition with no exposed columns yet); `column_count` SHALL NOT appear on the single-get response.
System columns (reserved `_`-prefix) and the hidden grain key SHALL NOT appear in the exposed
`columns` and SHALL NOT be counted by `column_count`. Each exposed column SHALL carry its optional
user-friendly `display_name` (absent/null when unset), its optional identifying `tag` (absent/null
when unset), its `sensitive` flag (default `false` when unset), its `heavy` flag (default `false`
when unset — see "Heavy column metadata"), and — for an `array`-typed column —
its `element_type` (absent/null for non-array columns). Each returned definition SHALL
carry its `system` flag (default `false` when unset) so operators can see which tables are protected
from modification, and its `write` discipline so operators can see how a write to an existing key
behaves. Each returned definition SHALL also carry its `identity_column` and
`version_column` — the pair defining a source's total order for the governed incremental scan — each
absent when the definition has none, so a consumer can tell which column identifies a row, which column
carries its version, and therefore whether the table is scannable at all.

The table-management read surface SHALL be filtered at column granularity by the same per-table
clearance as the query surface (see `authentication`). A `sensitive` column SHALL appear in `columns`,
and SHALL be counted by `column_count`, only for a caller holding `TABLE_SENSITIVE` on that table,
globally or by grant, or in `none` security mode. For every other caller it SHALL be omitted as if it
did not exist. There is no redacted placeholder, and `column_count` SHALL still equal the length of the
`columns` array the same caller receives. An operator auditing or managing sensitivity SHALL therefore
hold the clearance. The definition's structural fields (`ordering_key`, `partition_by`, `grain_key`,
`identity_column`, `version_column`, `ttl`) SHALL NOT be filtered: they are returned as stored, even
where they name a column the caller cannot see.

#### Scenario: Get a definition with its columns

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response is 200 with the definition's `name`, `description`, `type`, `write`, `status`,
  `system` flag, structural fields (`ordering_key`/`partition_by` or
  `source_table`/`grain_key`/`cardinality`), its `identity_column`/`version_column` when it has them,
  and its exposed `columns`

#### Scenario: A seeded source reports its scan metadata

- **WHEN** `GET /v1/tables/dial_usage_log` is called
- **THEN** the response carries `identity_column: "event_id"` and `version_column: "_ingested_at"`, the
  values its migration seeded

#### Scenario: A definition without scan metadata omits both fields

- **WHEN** `GET /v1/tables/{name}` is called for a definition that declares neither column
- **THEN** `identity_column` and `version_column` are absent from the response, and the read succeeds

#### Scenario: Get an unknown definition

- **WHEN** `GET /v1/tables/{name}` is called for a name not in the catalog
- **THEN** the response is 404 with error code `not_found`

#### Scenario: System flag is returned

- **WHEN** `GET /v1/tables/{name}` is called for `dial_usage_log` (a seeded system table) and for a
  user-created table
- **THEN** the seeded table's response carries `system=true` and the user-created table's response
  carries `system=false`

#### Scenario: Array column element type is returned

- **WHEN** a table has a column of `type=array` with `element_type: "string"` (declared via
  `POST /v1/tables/{name}/schema` or via `PATCH add`, or the backfilled
  `dial_usage_log.execution_path`) and `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries `type: "array"` and `element_type: "string"`, while
  non-array columns carry a null/absent `element_type`

#### Scenario: Column display name declared in the schema is returned

- **WHEN** a source's schema (`POST /v1/tables/{name}/schema`) declares a column carrying a
  `display_name` and the table is materialized, then `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries the declared `display_name`, while columns that did not
  declare a display name carry a null `display_name`

#### Scenario: Column tag declared in the schema is returned

- **WHEN** a source's schema (`POST /v1/tables/{name}/schema`) declares a column carrying a `tag`
  and the table is materialized, then `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries the declared `tag`

#### Scenario: Column sensitive flag declared in the schema is returned

- **WHEN** a source's schema (`POST /v1/tables/{name}/schema`) declares a column with
  `sensitive: true` and the table is materialized, then `GET /v1/tables/{name}` is called
- **THEN** for a caller holding `TABLE_SENSITIVE` on the table, that column in the response carries
  `sensitive: true`, while columns that did not declare the flag carry `sensitive: false`

#### Scenario: A caller without the clearance does not see the sensitive column

- **GIVEN** a table with three columns, one of them `sensitive`
- **WHEN** a caller holding `TABLE_GET` but not `TABLE_SENSITIVE` on it calls `GET /v1/tables/{name}`
  and `GET /v1/tables`
- **THEN** the single-get `columns` array lists the two non-sensitive columns only, and the list item
  carries `column_count: 2`
- **AND WHEN** a caller holding both calls the same endpoints
- **THEN** it sees all three columns and `column_count: 3`

#### Scenario: Column heavy flag declared in the schema is returned

- **WHEN** a source's schema (`POST /v1/tables/{name}/schema`) declares a column with `heavy: true`
  and the table is materialized, then `GET /v1/tables/{name}` is called
- **THEN** that column in the response carries `heavy: true`, while columns that did not declare the
  flag carry `heavy: false`

#### Scenario: List includes both table types

- **WHEN** `GET /v1/tables` is called
- **THEN** the response object lists every definition, including enrichment definitions and
  `upsert_by_key` sources, in a stable order

#### Scenario: List items carry the exposed-column count

- **WHEN** a source's schema (`POST /v1/tables/{name}/schema`) declares 3 columns and the table
  is materialized, then `GET /v1/tables` is called
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

### Requirement: Per-table mutations honor role-based access

The per-table mutating endpoints SHALL be authorized by the privilege each requires, held globally or
granted on that table as defined by the `table-access-control` capability: row writes
(`POST /v1/tables/{name}/rows`) require `TABLE_WRITE`, and the schema/metadata mutations — define +
materialize (`POST /v1/tables/{name}/schema`), live schema PATCH
(`PATCH /v1/tables/{name}/schema`), and definition update (`PUT /v1/tables/{name}`) — require
`TABLE_MODIFY`. Both schema endpoints SHALL require the same privilege; they SHALL NOT differ in
reachability as they do today.

Anything that touches sensitivity SHALL additionally require the clearance `TABLE_SENSITIVE` on the
table, globally or by grant (see `authentication`). That covers changing a column's `sensitive` flag
in either direction, declaring a column `sensitive` on `add` or `POST .../schema`,
and renaming, dropping or updating a column that is already `sensitive`. `TABLE_GRANT` SHALL NOT be
required for any of it. A mutation that touches no sensitive column and no `sensitive` flag SHALL
remain authorized by `TABLE_MODIFY` alone.

Table create (`POST /v1/tables`) SHALL require `TABLE_CREATE` and delete
(`DELETE /v1/tables/{name}`) SHALL require `TABLE_DELETE`; both are global-only privileges and SHALL
NOT be authorizable by any per-table grant. `system`-owned table protection SHALL take precedence: a
`system` table SHALL reject every modifying operation regardless of any privilege or grant.

#### Scenario: Access role authorizes a row write

- **GIVEN** a non-system table whose `write` access list contains the caller's provider role
- **WHEN** the caller inserts rows
- **THEN** the write succeeds

#### Scenario: A modify role authorizes defining the schema

- **GIVEN** a non-system `PENDING` table whose `modify` access list contains the caller's provider
  role
- **WHEN** the caller `POST`s its schema to `POST /v1/tables/{name}/schema`
- **THEN** the request is authorized and the table is materialized

#### Scenario: A modify grant does not authorize changing sensitivity

- **GIVEN** a non-system table with a `sensitive` column `employee_name` and a non-sensitive column
  `amount`, whose `modify` access list contains the caller's provider role and whose `sensitive` list
  does not
- **WHEN** the caller sends `PATCH /v1/tables/{name}/schema` with
  `{"update":[{"name":"employee_name","sensitive":false}]}`
- **THEN** the response is `422` naming an unknown column `employee_name`, and the column remains
  sensitive
- **AND WHEN** the same caller sends `{"update":[{"name":"amount","sensitive":true}]}`
- **THEN** the response is `403 forbidden` and `amount` stays non-sensitive
- **AND WHEN** the same caller patches only `amount`'s `description`
- **THEN** the request succeeds

#### Scenario: A modify grant with the clearance changes sensitivity

- **GIVEN** the same table, with the caller's provider role also in its `sensitive` list
- **WHEN** the caller clears `sensitive` on `employee_name` and sets it on `amount`
- **THEN** both changes are applied

#### Scenario: Delete is never authorized by an access role

- **WHEN** a caller holding a `modify` grant but not `TABLE_DELETE` deletes the table
- **THEN** the service responds `403 forbidden`

#### Scenario: System table rejects modification despite access roles

- **GIVEN** a `system` table with a `write` access role for the caller's provider role
- **WHEN** the caller inserts rows
- **THEN** the service rejects the modification (system-owned protection)

### Requirement: Modify table schema via PATCH

The system SHALL evolve a **materialized** table's schema at `PATCH /v1/tables/{name}/schema` from a
body carrying `add`, `drop`, `rename`, and `update` column operations and an optional `ttl` member.
The endpoint SHALL be allowed only while the table's `status` is `ACTIVE`; on a `PENDING` or
`FAILED` (not-yet-materialized) table it SHALL be rejected with HTTP 409 (its schema is defined and
materialized via `POST /v1/tables/{name}/schema`). The frozen physical keys (`ordering_key`,
`partition_by`, `grain_key`, `cardinality`) are fixed after materialization, and the table's tag
ordering (`tag_order`) is table catalog metadata set via `PUT /v1/tables/{name}`; none of these
SHALL be members of this body — a frozen-key or `tag_order` member SHALL be rejected with HTTP 422
(this rejection precedes the materialization-state check, so it applies regardless of the table's
status). The `ttl` member is the one mutable physical member of this body.

The `ttl` member SHALL follow merge-patch semantics: **absent or null** SHALL leave the table's
TTL unchanged; a **full object** `{column, interval, unit}` SHALL set or replace it (a
`MODIFY TTL` command); an **empty object `{}`** SHALL remove it (a `REMOVE TTL` command). A
partial `ttl` object (some but not all of `column`/`interval`/`unit`) SHALL be rejected with HTTP
422. Removing when no TTL is stored SHALL be an idempotent no-op: HTTP 200 with no `REMOVE TTL`
command emitted. The TTL `column` SHALL be resolved against the table's columns **after** the
request's own `drop`/`rename`/`add` operations are applied, so a just-added or just-renamed column
MAY be referenced; validation follows the "Table TTL" requirement.

All physical commands of one request SHALL be rendered in the canonical order `drop` → `rename` →
`add` → `ttl` and issued as a **single multi-command ClickHouse `ALTER TABLE` statement** (writer
credential), which ClickHouse validates as a whole: the statement is rejected or applied in its
entirety, so a request with one invalid command SHALL leave the physical table completely
unchanged. On success the Postgres catalog (column mapping and TTL) SHALL be updated in one
transaction. The `update` operation SHALL adjust an existing column's catalog-only metadata — its
`tag`, `display_name`, `sensitive` flag, `heavy` flag, and `description` — and SHALL NOT contribute any
ClickHouse `ALTER` command; a request carrying only `update`s (or only an idempotent TTL removal)
SHALL issue no `ALTER` at all. Each `update` entry declares `{name, tag?, display_name?,
sensitive?, heavy?, description?}`, keyed by the column's current exposed name, and SHALL follow
merge-patch semantics per field: a metadata field that is **absent or null** SHALL leave that
column attribute unchanged; a string field (`tag`, `display_name`, `description`) that is
**present and blank** SHALL clear that attribute; a string field that is **present and non-blank**
SHALL set it (trimmed); and `sensitive`/`heavy`, when **present**, SHALL set that flag to the supplied
boolean. A single `update` entry MAY change several metadata fields at once. Each `add` declares
`{source_name, name, type, element_type?, nullable?, description, display_name?, tag?,
sensitive?, heavy?}` (`sensitive` and `heavy` default to false when omitted; `element_type` is
required iff `type=array`, see "Schema validation"), and an added `type=array` column SHALL
materialize as ClickHouse `Array(<element>)`.

An `update` entry MAY target a just-added or just-renamed column by its resulting exposed name. A
column's `display_name`, `tag`, `sensitive` flag, `heavy` flag, and `description` SHALL all be
preserved across a `rename`. Schema modification SHALL be breaking and immediate (a drop or rename takes effect at
once). A ClickHouse `ALTER` failure SHALL leave the table `ACTIVE` (surfaced via the `ErrorView`
contract, status unchanged) and SHALL NOT transition it to `FAILED` — so a materialized table that
holds data is never reachable by the define-and-materialize `POST .../schema` path (which is
`ACTIVE` → 409); because the statement is single and validated as a whole, a failure leaves no
partially-applied operations, and the catalog is written only after the `ALTER` succeeds. In
`oidc` mode this endpoint SHALL require `TABLE_MODIFY` on the table, held globally or granted on it.

For a caller that does not hold `TABLE_SENSITIVE` on the table, the request SHALL be resolved against
the table's **visible** columns only, the same set `GET /v1/tables/{name}` returns to that caller. A
`sensitive` column SHALL be treated as not existing: a `drop`, a `rename.from` or an `update` naming it
SHALL be rejected as naming an unknown column (422), with the same message as for a column that does
not exist at all. Such a caller SHALL also be refused with `403 forbidden` any `update` that supplies a
`sensitive` member (either value), and any `add` that declares `sensitive: true`. An `add` or
`rename.to` whose name collides with a hidden column SHALL still be rejected (422, already exists), whether it
collides on the exposed name or on the physical one, and before any `ALTER` is issued. That discloses that
the name is taken, and it is the accepted limit of hiding (see `authentication`). The per-table column
limit SHALL count hidden columns too. A `ttl.column` SHALL resolve against the visible columns only, so
naming a hidden column fails as naming an undeclared one. Such a caller SHALL be refused
`403 forbidden` for any `ttl` member (set, replace or remove) while the stored TTL is on a hidden column. A
caller holding the clearance resolves against every column and MAY make any of these changes. Marking a
column sensitive — by patching one or adding one already flagged — on an enrichment SHALL be subject to
the laundering subset rule (see `enrichment-runtime`). The system SHALL reject: a type-change of an existing column (422); a drop or rename
of a system column (reserved `_`-prefix), the grain key, or an ordering-key column (422); a `drop`
of the column the table's stored TTL references (422 — remove or repoint the TTL in a prior
request first, even if the same request also carries a `ttl` member); an `update` of a column that
is not an exposed column of the table after the request's own add/drop/rename operations are
applied (422); a duplicate `update` of the same column within one request (422); and any operation
on a table not in the catalog (404).

The system SHALL also reject a `drop` or a `rename` of a **source** column that any `ACTIVE`
enrichment of that source names as its `grain_key` (422, naming the enrichment). The enrichment join
fires on the shared physical name (`source.grain_key = enrichment.grain_key`, see "Grain-based
enrichment join with enforced cardinality"), the enrichment side of that name is that table's ordering
key and therefore immutable, and a Class-1 pipeline's target is keyed on it — so the operation would leave
the join referencing a column that no longer exists, with no repair available on either side.

**The pipeline dependency guard SHALL resolve readers and writers from stored declarations, and
therefore covers complete declarations only.** Before any DDL is rendered, the endpoint refuses a
`drop` or a `rename` of a column that a pipeline **reads** through its input's grain join, or that
receives an output of the pipeline **writing** the enrichment, with HTTP 409 `conflict` naming those
pipelines (the two refusals are owned by
[enrichment-registry](../enrichment-registry/spec.md) and
[aggregate-pipeline](../aggregate-pipeline/spec.md)). Both sides answer the question by reading what
is stored — the declarations' governed fragments and bound inputs for reads, the derived
`output_bindings` for writes — so a declaration that does not yet name a column does not protect it.
Since a declaration may now be stored before it is complete, **an incomplete declaration
under-declares its reads, and a column it will eventually read stays droppable.**

This is stated as a bound rather than closed, because closing it would mean protecting a column on
the strength of a declaration that does not resolve — a fragment that may not parse, against a read
source that may not be declared yet — which is a guard with no way to compute its own answer. The
consequence is bounded on the other side instead: completing such a declaration afterwards **fails at
the declaration gate**, with HTTP 422 naming the column the read source no longer has, rather than
arming a pipeline that silently reads a column that is gone. The cost of the gap is therefore lost
authoring work on a pipeline that never ran — the author repoints or re-declares — and never a wrong
row. A pipeline that was ever armed had a complete declaration, and from that moment its reads are
stored and the guard holds for it as before. The **same bound applies to `DELETE /v1/tables/{name}`**,
whose input and enrichment dependency checks read the same stored declarations (see "Delete a table
definition"); it is stated on both verbs because an under-declared read is reached by either.

**The write side of the guard stays armed throughout authoring**, and that is not incidental: the
declaration gate's derivation half runs on **every** declaration write, armed or not, and never
replaces a derived `output_bindings` it could not re-derive (see
[enrichment-registry](../enrichment-registry/spec.md), "Static governance gate on arming and
execution"). Were it otherwise, an intermediate save of an edit would erase the record of which
columns the pipeline writes, this guard would stop refusing them, and a `drop` would then be applied
to ClickHouse *before* the catalog transaction that re-derives the mapping — leaving the column gone
from the table and present in the catalog, with no operation that repairs the pair.

**The post-DDL re-derivation of a target's output mapping SHALL follow the pipeline's state.** When a
schema patch adds, drops or renames a column of an enrichment, the pipeline targeting it has its
output mapping re-derived in the catalog transaction that follows the `ALTER`. For a **disabled**
pipeline — a draft, stored without the gate — a mapping that no longer re-derives SHALL NOT refuse the
schema patch: the stored `output_bindings` SHALL be kept, and neither `generation` nor `updated_at`
SHALL move, exactly as a draft save derives best-effort. A disabled pipeline's mapping that **does**
re-derive SHALL be rewritten exactly as an enabled pipeline's is: an `llm` pipeline's `generation` and
`updated_at` SHALL be advanced, and a `sql` pipeline's SHALL NOT. For an **enabled** pipeline the refusal SHALL
stay as the backstop it is: a change that leaves an armed pipeline's mapping unresolvable is refused
with HTTP 422 `pipeline_validation_failed` naming the pipeline, since the pre-DDL guard should already
have refused it and committing a schema the armed pipeline cannot write to is the worse outcome.

The system SHALL reject a `drop` of the column named by the table's scan metadata — its
`identity_column` or its `version_column` (422) — since nothing repoints the pair afterwards, leaving
the table advertising a scan column that no longer exists. A `rename` of either column SHALL be
**allowed**, and the catalog's stored `identity_column`/`version_column` SHALL be updated to the renamed
name in the same transaction as the column mapping — the treatment the TTL column already receives.
The rename SHALL NOT invalidate any scan cursor: a cursor carries the tuple's *values* and the source
entity, not the column names, and `RENAME COLUMN` preserves the data. When a source's `version_column`
is repointed this way, every pipeline whose target enrichment reads that source SHALL have its `generation`
and `updated_at` advanced in the same transaction (see
[enrichment-registry](../enrichment-registry/spec.md)), so a runner detecting change by `generation`
refetches the name its scan pages now carry.

A `rename` of the TTL-referenced column SHALL be allowed: ClickHouse rewrites the physical TTL
expression itself, and the catalog's stored TTL column SHALL be updated to the renamed name in the same
transaction. The `update` operation SHALL carry no general column mutability restriction — system,
grain, and ordering-key columns MAY be tagged, given a display name, marked sensitive, marked heavy, or
described — with one exception: an `update` setting `sensitive: true` on the column named by the table's
`identity_column` or `version_column` SHALL be rejected (422). A sensitive cursor column is what the
schema-define contract already refuses (see "Define and materialize a table schema"), because the scan
projects both cursor columns and carries the identity inside its cursor, so every caller without the
sensitive entitlement would be refused the pipeline; permitting it through `update` would undo after
materialization what declaration enforces.

#### Scenario: Schema PATCH before materialization is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` is called on a `PENDING` or `FAILED` table
- **THEN** the request is rejected with HTTP 409 (define and materialize the schema first via
  `POST .../schema`)

#### Scenario: A frozen-key member in a PATCH is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` carries `ordering_key`, `partition_by`, `grain_key`, or
  `cardinality`
- **THEN** the request is rejected with HTTP 422 (physical keys are frozen after materialization)

#### Scenario: Dropping a scan-metadata column is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` carries a `drop` of the column named by the table's
  `identity_column` or `version_column`
- **THEN** the request is rejected with HTTP 422 before any `ALTER` is issued, and the table's columns
  and stored scan metadata are unchanged

#### Scenario: Renaming a scan-metadata column repoints the stored pair

- **WHEN** `PATCH /v1/tables/{name}/schema` renames the column named by the table's `identity_column`
  or `version_column`, and no `ACTIVE` enrichment of that source is keyed on it
- **THEN** the rename is applied and the stored `identity_column`/`version_column` reflects the new
  name, so `GET /v1/tables/{name}` returns the renamed column and a subsequent scan of a pipeline over that
  source pages on the renamed column
- **AND** a cursor issued before the rename continues to page correctly, because the cursor carries the
  tuple's values and the source entity rather than the column names

#### Scenario: A column an incomplete declaration will eventually read stays droppable

- **WHEN** `PATCH /v1/tables/{name}/schema` drops an enrichment column while the only pipeline that
  will read it is disabled and its declaration does not yet name it
- **THEN** the drop is accepted, because the guard resolves readers from stored declarations and that
  declaration declares no such read

#### Scenario: Completing that declaration afterwards fails at the gate rather than reading a dropped column

- **WHEN** the author then declares the fragment naming the dropped column and arms or previews the
  pipeline
- **THEN** the request is rejected with HTTP 422 naming the column the read source no longer has, so
  the cost of the gap is the authoring work and never a row derived from a column that is gone

#### Scenario: A complete declaration protects its reads even while disabled

- **WHEN** `PATCH /v1/tables/{name}/schema` drops or renames an enrichment column that a **complete**
  declaration reads through its input's grain join, and that pipeline is disabled
- **THEN** the request is refused with HTTP 409 `conflict` naming that pipeline, and no `ALTER` is
  issued — completeness, not enablement, is what arms this guard

#### Scenario: An intermediate save keeps the written columns protected

- **WHEN** a disabled pipeline whose `output_bindings` were derived is patched with a declaration
  whose outputs do not resolve, and a schema patch then drops one of those bound columns
- **THEN** the drop is refused with HTTP 409 `conflict`, because the declaration write kept the
  derived mapping rather than erasing it

#### Scenario: A schema patch does not fail on a draft whose mapping stops re-deriving

- **GIVEN** a disabled `enrich` pipeline whose outputs do not resolve against its target after a schema
  patch of that target applies
- **WHEN** the catalog transaction re-derives the pipeline's output mapping
- **THEN** the schema patch succeeds, the pipeline's stored `output_bindings` are kept as they were, and
  its `generation` and `updated_at` are unchanged

#### Scenario: A draft whose mapping does re-derive is rewritten as an enabled pipeline's is

- **GIVEN** a disabled `enrich` pipeline whose outputs still resolve against its target after a schema
  patch of that target applies
- **WHEN** the catalog transaction re-derives the pipeline's output mapping
- **THEN** the stored `output_bindings` are replaced by the re-derived mapping
- **AND** an `llm` pipeline's `generation` and `updated_at` are advanced, while a `sql` pipeline's are
  unchanged

#### Scenario: The re-derivation backstop still refuses for an enabled pipeline

- **GIVEN** an enabled `enrich` pipeline whose output mapping a schema patch of its target would leave
  unresolvable, and which the pre-DDL write guard did not catch
- **WHEN** the catalog transaction re-derives the pipeline's output mapping
- **THEN** the schema patch is refused with HTTP 422 `pipeline_validation_failed` naming the pipeline

#### Scenario: A drop or rename of a column an enrichment is keyed on is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` carries a `drop` or a `rename` of a source column that an
  `ACTIVE` enrichment of that source names as its `grain_key`
- **THEN** the request is rejected with HTTP 422 naming that enrichment, before any `ALTER` is issued,
  and the source's columns are unchanged
- **AND** the rejection applies whether or not that column is also the source's `identity_column`

#### Scenario: Marking a scan-metadata column sensitive is rejected

- **WHEN** an `update` entry supplies `sensitive: true` for the column named by the table's
  `identity_column` or `version_column`
- **THEN** the request is rejected with HTTP 422, and the column's `sensitive` flag is unchanged
- **AND** the same `update` supplying a `tag`, `display_name`, `description`, `heavy`, or
  `sensitive: false` for that column is accepted

#### Scenario: A tag_order member in a PATCH is rejected

- **WHEN** `PATCH /v1/tables/{name}/schema` carries a `tag_order` member
- **THEN** the request is rejected with HTTP 422 (tag ordering is table metadata, set via
  `PUT /v1/tables/{name}`)

#### Scenario: Set a TTL on a materialized table

- **WHEN** `PATCH /v1/tables/{name}/schema` carries
  `ttl: {column: "created_at", interval: 90, unit: "day"}` for an `ACTIVE` table where
  `created_at` is a declared non-nullable `timestamp` column
- **THEN** the issued `ALTER` contains `MODIFY TTL toDateTime(created_at) + INTERVAL 90 DAY`, the
  catalog stores the TTL, and the response carries `ttl: {column: "created_at", interval: 90,
  unit: "day"}`

#### Scenario: Replace an existing TTL

- **WHEN** a `PATCH` carries a full `ttl` object for a table that already has a TTL
- **THEN** a `MODIFY TTL` reflecting the new value is issued and the catalog value is replaced

#### Scenario: Remove a TTL with an empty object

- **WHEN** a `PATCH` carries `ttl: {}` for an `ACTIVE` table that has a TTL
- **THEN** the issued `ALTER` contains `REMOVE TTL`, the catalog value is cleared, and a
  subsequent `GET /v1/tables/{name}` omits the `ttl` member

#### Scenario: Removing an unset TTL is an idempotent no-op

- **WHEN** a `PATCH` carries `ttl: {}` for a table that has no stored TTL (and no other physical
  operations)
- **THEN** the response is 200 and no ClickHouse `ALTER` is issued

#### Scenario: Absent ttl member leaves the TTL unchanged

- **WHEN** a `PATCH` carrying only column operations (no `ttl` member) is applied to a table with
  a TTL
- **THEN** the stored TTL and the physical TTL are unchanged

#### Scenario: Partial ttl object is rejected

- **WHEN** a `PATCH` carries a `ttl` object with some but not all of `column`, `interval`, `unit`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Columns and TTL change in one statement

- **WHEN** a `PATCH` carries an `add` of a non-nullable `timestamp` column `event_time` and
  `ttl: {column: "event_time", interval: 30, unit: "day"}`
- **THEN** a single `ALTER` statement carrying both the `ADD COLUMN` and the `MODIFY TTL`
  commands is issued, and the TTL references the just-added column

#### Scenario: One invalid command leaves the whole PATCH unapplied

- **WHEN** a `PATCH` carries a valid `add` and an invalid `ttl` (or any command ClickHouse
  rejects), so the single `ALTER` statement fails
- **THEN** the response is an `ErrorView` error, no command of the statement is applied (the
  column is not added), the table stays `ACTIVE`, and the catalog is unchanged

#### Scenario: Drop of the TTL-referenced column is rejected

- **WHEN** a `PATCH` carries a `drop` of the column the table's stored TTL references
- **THEN** the request is rejected with HTTP 422 (remove or repoint the TTL first), regardless of
  any `ttl` member in the same request

#### Scenario: Rename of the TTL-referenced column keeps catalog and table in sync

- **WHEN** a `PATCH` renames the column the table's stored TTL references
- **THEN** the rename is applied (ClickHouse rewrites the TTL expression), and the catalog's
  stored TTL column reflects the new name, so `GET /v1/tables/{name}` returns the renamed column
  in its `ttl` object

#### Scenario: A failed live ALTER leaves the table ACTIVE

- **WHEN** a `PATCH /v1/tables/{name}/schema` request's single ClickHouse `ALTER` fails on an
  `ACTIVE` table that holds data
- **THEN** the response is an `ErrorView` error, the table's `status` remains `ACTIVE` (it does not
  become `FAILED`), and `POST /v1/tables/{name}/schema` continues to reject it with HTTP 409

#### Scenario: Add columns to a materialized enrichment

- **WHEN** `PATCH /v1/tables/{name}/schema` adds valid columns to an `ACTIVE` enrichment
- **THEN** an `ALTER` statement with an `ADD COLUMN` command per column is issued, the column
  mapping is updated, and the response reflects the new columns

#### Scenario: Add an array column

- **WHEN** `PATCH /v1/tables/{name}/schema` adds a column of `type:"array"`, `element_type:"long"`
  to an `ACTIVE` table
- **THEN** an `ALTER TABLE ... ADD COLUMN ... Array(Int64)` is issued and the column mapping records
  the `array` type with element type `long`

#### Scenario: Drop and rename apply immediately

- **WHEN** `PATCH /v1/tables/{name}/schema` drops one column and renames another on an `ACTIVE` table
- **THEN** a single `ALTER` statement carrying the `DROP COLUMN` and `RENAME COLUMN` commands is
  issued and the column mapping is updated

#### Scenario: Type change is rejected

- **WHEN** a `PATCH` attempts to change the `type` of an existing column
- **THEN** the request is rejected with HTTP 422

#### Scenario: System and grain columns are protected

- **WHEN** a `PATCH` attempts to drop or rename a `_`-prefixed system column or an enrichment's grain
  key
- **THEN** the request is rejected with HTTP 422

#### Scenario: Update changes several metadata fields at once

- **WHEN** a single `update` entry supplies a `tag`, a `display_name`, and `sensitive: true` for an
  existing column
- **THEN** all three attributes are set on that column and no ClickHouse `ALTER` is issued

#### Scenario: Absent metadata field leaves an attribute unchanged

- **WHEN** an `update` entry supplies only a `tag` for a column that already has a `display_name`
- **THEN** the column's `tag` is set and its existing `display_name`, `sensitive` flag, `heavy` flag,
  and `description` are unchanged

#### Scenario: Update toggles the heavy flag without an ALTER

- **WHEN** an `update` entry supplies `heavy: true` for an existing column, and a later request
  supplies `heavy: false` for the same column
- **THEN** the flag is set and then cleared, no ClickHouse `ALTER` is issued by either request, and
  wildcard projections on the query surface follow the current value

#### Scenario: Blank metadata field clears an attribute

- **WHEN** an `update` entry supplies a blank (`""`) `tag`, `display_name`, or `description` for a
  column that currently has that attribute set
- **THEN** that attribute is cleared, and no ClickHouse `ALTER` is issued

#### Scenario: Metadata survives a rename

- **WHEN** a `PATCH` `update`s an existing column's `tag`, `display_name`, `sensitive` flag, `heavy`
  flag, and `description`, then a later `PATCH` renames that column
- **THEN** all five attributes are preserved under the rename, none of which issues a ClickHouse
  `ALTER`

#### Scenario: Update of an unknown column is rejected

- **WHEN** an `update` entry names a column that is not an exposed column of the table (after the
  request's own add/drop/rename operations are applied)
- **THEN** the request is rejected with HTTP 422

#### Scenario: Duplicate update of the same column is rejected

- **WHEN** two `update` entries in one request name the same column
- **THEN** the request is rejected with HTTP 422

#### Scenario: A modify access role authorizes a non-admin schema change

- **WHEN** a caller holding no global privilege but a matching `modify` grant on an `ACTIVE` table
  `PATCH`es its schema
- **THEN** the modification is authorized and processed

#### Scenario: A hidden sensitive column is unknown to a caller without the clearance

- **GIVEN** an `ACTIVE` table with a `sensitive` column `employee_name`, and a caller holding
  `TABLE_MODIFY` but not `TABLE_SENSITIVE` on it
- **WHEN** the caller `PATCH`es `{"drop":["employee_name"]}`, and separately
  `{"rename":[{"from":"employee_name","to":"full_name"}]}`
- **THEN** each request is rejected with 422 naming an unknown column, and the table is unchanged

#### Scenario: Adding a sensitive column needs the clearance

- **WHEN** a caller holding `TABLE_MODIFY` but not `TABLE_SENSITIVE` `PATCH`es an `add` declaring a
  column with `sensitive: true`
- **THEN** the request is rejected with `403 forbidden` and no `ALTER` is issued
- **AND WHEN** a caller holding both sends the same request
- **THEN** the column is added and flagged sensitive

#### Scenario: A name collision with a hidden column is still refused

- **WHEN** a caller without the clearance `add`s a column whose exposed name equals a hidden
  sensitive column's, where the two physical names differ, or `rename`s a visible column onto that name
- **THEN** the request is rejected with 422 as a name that already exists, no `ALTER` is issued, and the
  table is unchanged

#### Scenario: A TTL cannot reach a hidden column

- **GIVEN** an `ACTIVE` table with a hidden sensitive `timestamp` column `birth_date`, and a caller without
  the clearance
- **WHEN** the caller `PATCH`es `{"ttl":{"column":"birth_date","interval":1,"unit":"day"}}`
- **THEN** the request is rejected with 422 as naming an undeclared column, and no `ALTER` is issued
- **AND WHEN** the table's stored TTL is on `birth_date` and the same caller sends `{"ttl":{}}`
- **THEN** the response is `403 forbidden` and the TTL is unchanged

### Requirement: Define and materialize a table schema

The system SHALL expose `POST /v1/tables/{name}/schema` to define the complete physical schema of a
not-yet-materialized table AND materialize it (issue `CREATE TABLE`, flip to `ACTIVE`) in a single
call. The endpoint SHALL be allowed only while the table's `status` is `PENDING` or `FAILED`; on an
already `ACTIVE` (materialized) table it SHALL be rejected with HTTP 409 (a materialized schema evolves
through `PATCH /v1/tables/{name}/schema`).

The request body SHALL carry the table's physical schema: `columns` (the exposed columns, each
`{source_name, name, type, element_type?, nullable?, description?, display_name?, tag?, sensitive?,
heavy?}`)
plus the physical keys for the table's kind — `ordering_key`, optional `partition_by`, and the optional
scan-metadata pair `identity_column`/`version_column` for a
`source`; `grain_key` and optional `cardinality` for an `enrichment` — and an optional `ttl`
declaration `{column, interval, unit}` (see "Table TTL" for its validation and rendering rules).
The `name`, `type`, `write`, and `source_table` (identity/relationship) SHALL NOT appear in this body
(they are set and fixed by `POST /v1/tables`).

`identity_column` and `version_column` are the pair the governed incremental scan pages a source by (see
[enrichment-runtime](../enrichment-runtime/spec.md)).

A `source` MAY declare them, and a source that declares neither is simply **not scannable** — the scan
answers 400 naming the missing configuration (see
[enrichment-runtime](../enrichment-runtime/spec.md)), and every other operation on the table is unaffected.
Declaring them SHALL NOT be required, because the identity half cannot always be declared truthfully: a
rollup's row identity is its whole `ordering_key`, so a source keyed on several columns has no single column
that is unique per row. Requiring one would only ever have produced a declaration naming part of the key,
and a repeated cursor tuple makes keyset paging **skip** rows rather than report anything — the failure the
pair exists to prevent. Such a table SHOULD therefore declare neither member and be scanned, if at all, on
a single-column grain.

When declared, each SHALL be a column the scan can actually page by. The system SHALL reject anything else
with HTTP 422:

- each SHALL reference a column declared in the same request — except that an `upsert_by_key` source MAY
  name the ReplacingMergeTree version `_updated_at`, the engine's own version column, which such a table
  physically carries and an `append` source does not (naming it on an `append` source SHALL be rejected
  with a message naming the write discipline, not merely "not a declared column");
- the `version_column` SHALL be declared `timestamp`: the scan orders and safety-lags a source by this
  column as a millisecond timestamp (`toUnixTimestamp64Milli(version)` compared against `now64(3)`), which
  no other declarable type answers;
- neither SHALL be nullable: a `NULL` makes both the safety-lag predicate and the keyset tuple compare
  `NULL`, so such a row would be silently absent from every page rather than reported;
- neither SHALL be `sensitive`: the scan projects both cursor columns and carries the identity inside its
  cursor, so a rule over that source could be operated only by a caller entitled to sensitive columns.

Those are the properties this endpoint can see, and a source declaring the pair is therefore *shaped* for
the scan — not proven correct for it. That the version column is assigned at ingest, monotonic and never
backdated, and that the identity column is unique per row, remain the declaring caller's promise: they are
physical facts about ingest, and the scan's exactly-once guarantee rests on them. That the promise is
optional is what keeps it meaningful — a caller declares the pair when it holds, rather than to satisfy a
required field.

An `enrichment` SHALL declare NEITHER member (422 for either, naming it): it is keyed on its `grain_key`,
and its row version is the `ReplacingMergeTree` column the platform stamps, so neither name could resolve
to anything the caller declared. An omitted member SHALL leave any stored value unchanged, so re-posting a
`FAILED` table's schema never silently clears one.

The system SHALL validate the schema — both completeness for the table's kind and internal consistency
(see "Schema validation") — before persisting anything or issuing any ClickHouse DDL: a `source` SHALL
have at least one column and a non-empty `ordering_key` whose columns (and `partition_by` column, if
any) are present; an `enrichment` SHALL have a `grain_key` that is a declared column of its
`source_table` and MAY have zero user columns; a `ttl`, when present, SHALL satisfy the "Table TTL"
constraints (declared non-nullable `date`/`timestamp` column, positive `interval`, valid `unit`). An
incomplete or inconsistent schema SHALL be rejected with HTTP 422, leaving the definition untouched
(still `PENDING`/`FAILED`, nothing persisted).

On a valid schema the system SHALL persist the columns + keys (and the `ttl`, when declared) and
issue a single ClickHouse `CREATE TABLE` (writer credential), with the engine selected from the
definition's `type` and `write` discipline (see "Table write discipline") — an `append` `source` as
`MergeTree ORDER BY (ordering_key)`, an `upsert_by_key` `source` as
`ReplacingMergeTree(_updated_at) ORDER BY (ordering_key)`, an `enrichment` as
`ReplacingMergeTree(_updated_at)` with `ORDER BY (grain_key)` carrying its grain-key and system
columns (with `PARTITION BY` when a source's `partition_by` is set) — every version-collapsing engine
rendering its version column as `_updated_at DateTime64(3) DEFAULT now64(3)` so that every
write is stamped server-side and duplicate writes for the same key collapse to the latest on a
`final=1` read — an ordering at the stamp's millisecond resolution, so writes of one key landing inside
a single millisecond tie and a tie leaves the surviving row unspecified (see `data-sync`'s "Supported
ClickHouse server version" for the client setting this depends on) — with a `TTL` clause appended when a `ttl` is declared — and flip
the definition to `status=ACTIVE`, returning the materialized definition. A `type=array` column SHALL
materialize as ClickHouse `Array(<element>)`. A `type=timestamp` column SHALL materialize as
`DateTime64(3, 'UTC')` — the zone SHALL be declared rather than left to the ClickHouse server's, because
the read path converts the zoneless value the driver surfaces to epoch milliseconds at UTC, and a time
bucket materialized into such a column would otherwise re-bucket on read in the server's zone rather than
the one it was computed in. If the `CREATE TABLE` fails or its outcome is ambiguous
(e.g. a timeout under which ClickHouse may still create the table), the system SHALL issue a
best-effort `DROP TABLE IF EXISTS`, set the definition to `status=FAILED`, and return an error using
the `ErrorView` contract without leaking ClickHouse internals; a `FAILED` table MAY be re-posted
(with its full schema) to retry. In `oidc` mode the endpoint SHALL require `TABLE_MODIFY`, held
globally or granted on the table, and a `system`-owned table SHALL reject it with HTTP 409 whatever the
caller holds. Declaring any column `sensitive` here SHALL additionally require `TABLE_SENSITIVE` on the
table, globally or granted on the `PENDING` table. Otherwise the request SHALL be rejected with
`403 forbidden` and nothing is materialized. This is the same rule as on `add` (see "Modify table
schema via PATCH"): anything that touches sensitivity needs the clearance, and it keeps an author from
creating a column it could not then see.

#### Scenario: Defining a sensitive column needs the clearance

- **GIVEN** a `PENDING` table whose `modify` list contains the caller's provider role and whose
  `sensitive` list does not
- **WHEN** the caller `POST`s a schema declaring one column `sensitive: true`
- **THEN** the response is `403 forbidden`, and the table stays `PENDING` with no physical table created
- **AND WHEN** the caller's role is added to the table's `sensitive` list and the request is repeated
- **THEN** the table is materialized with that column flagged sensitive

#### Scenario: Define and materialize a source

- **WHEN** `POST /v1/tables/{name}/schema` is called on a `PENDING` `append` source with `columns` and
  a non-empty `ordering_key` referencing declared columns
- **THEN** a single `CREATE TABLE ... ENGINE = MergeTree ORDER BY (...)` is issued, the definition
  becomes `status=ACTIVE`, and the response reflects the materialized schema

#### Scenario: Define and materialize an upsert-by-key source

- **WHEN** `POST /v1/tables/{name}/schema` is called on a `PENDING` `upsert_by_key` source with
  `columns` and a non-empty `ordering_key`
- **THEN** a single `CREATE TABLE ... ENGINE = ReplacingMergeTree(_updated_at) ORDER BY
  (ordering_key)` is issued carrying the `_updated_at` version column, and the definition becomes
  `status=ACTIVE`

#### Scenario: A source declares its scan metadata

- **WHEN** the same request also carries `identity_column` and `version_column`, each naming a column it
  declares
- **THEN** both are persisted on the definition, returned by `GET /v1/tables/{name}`, and a rule over that
  source scans it — with no migration in between

#### Scenario: A source declaring no scan metadata materializes and is not scannable

- **WHEN** `POST /v1/tables/{name}/schema` is called on a `PENDING` source with `columns` and a valid
  `ordering_key` but neither scan column — or only one of the two
- **THEN** the table materializes and becomes `ACTIVE`, `GET /v1/tables/{name}` omits whichever member was
  not declared, and a scan of a rule over that source is refused with the 400 that names the missing
  scan-metadata configuration

#### Scenario: An upsert-by-key source may declare _updated_at as its version

- **WHEN** an `upsert_by_key` source's schema request carries `version_column: "_updated_at"`
- **THEN** the value is persisted and returned by `GET /v1/tables/{name}`
- **AND** the same `version_column` on an `append` source is rejected with HTTP 422, naming the write
  discipline as the reason (it carries no `_updated_at`)

#### Scenario: A scan column the scan could not page by is rejected

- **WHEN** a source's schema request declares a `version_column` that is not a `timestamp` column, or
  either member naming a column declared `nullable: true`, or either member naming a `sensitive` column
- **THEN** the request is rejected with HTTP 422 naming the field and the property that disqualifies it, no
  ClickHouse DDL is issued, and the definition remains `PENDING`/`FAILED`

#### Scenario: A scan-metadata column absent from the declared columns is rejected

- **WHEN** `POST /v1/tables/{name}/schema` carries an `identity_column` or `version_column` naming a
  column the request does not declare
- **THEN** the request is rejected with HTTP 422 naming that column, no ClickHouse DDL is issued, and the
  definition remains `PENDING`/`FAILED`

#### Scenario: Neither scan column is accepted for an enrichment

- **WHEN** `POST /v1/tables/{name}/schema` carries an `identity_column`, or a `version_column`, for an
  `enrichment`
- **THEN** the request is rejected with HTTP 422 naming the member — the identity because the table is
  keyed on its `grain_key`, the version because the platform stamps it — and nothing is persisted

#### Scenario: Define a source with partitioning

- **WHEN** the request includes a `partition_by` naming a declared column and a time granularity
- **THEN** the rendered `CREATE TABLE` includes a matching `PARTITION BY` expression

#### Scenario: Define a source with a TTL

- **WHEN** `POST /v1/tables/{name}/schema` is called on a `PENDING` source with a valid `ttl`
  declaration `{column: "event_date", interval: 6, unit: "month"}` where `event_date` is a declared
  non-nullable `date` column
- **THEN** the rendered `CREATE TABLE` ends with `TTL event_date + INTERVAL 6 MONTH`, the catalog
  stores the TTL, and the materialized definition carries the `ttl` object

#### Scenario: Invalid TTL declaration keeps the table un-materialized

- **WHEN** `POST /v1/tables/{name}/schema` carries a `ttl` whose column is not declared, is
  nullable, or is not a `date`/`timestamp` column
- **THEN** the request is rejected with HTTP 422, no ClickHouse DDL is issued, and the definition
  remains `PENDING`/`FAILED`

#### Scenario: Define a source with an array column

- **WHEN** the request includes a column of `type=array` with `element_type: "string"`
- **THEN** the rendered `CREATE TABLE` declares that column as `Array(String)`

#### Scenario: Define and materialize an enrichment

- **WHEN** `POST /v1/tables/{name}/schema` is called on a `PENDING` enrichment with a `grain_key` that
  is a declared column of its `source_table` (and zero or more user `columns`)
- **THEN** a single `CREATE TABLE ... ENGINE = ReplacingMergeTree(_updated_at) ORDER BY (grain_key)`
  is issued carrying its grain-key and system columns plus any user columns, and the definition
  becomes `status=ACTIVE`

#### Scenario: Enrichment version column carries a default

- **WHEN** an enrichment table is materialized
- **THEN** its `_updated_at` column is rendered `DateTime64(3) DEFAULT now64(3)`, so a write that
  omits `_updated_at` still receives an increasing version stamped by ClickHouse

#### Scenario: Incomplete schema is rejected and stays pending

- **WHEN** `POST /v1/tables/{name}/schema` is called on a source with no columns or an empty
  `ordering_key`, or on an enrichment with no `grain_key`
- **THEN** the request is rejected with HTTP 422, no ClickHouse `CREATE TABLE` is issued, and the
  definition remains `status=PENDING`

#### Scenario: Inconsistent schema is rejected

- **WHEN** `POST /v1/tables/{name}/schema` declares an `ordering_key` column not present in `columns`,
  or an enrichment `grain_key` that is not a column of its `source_table`
- **THEN** the request is rejected with HTTP 422 and nothing is persisted

#### Scenario: Define on a materialized table is rejected

- **WHEN** `POST /v1/tables/{name}/schema` is called on an `ACTIVE` table
- **THEN** the request is rejected with HTTP 409 (use `PATCH` to evolve a live table)

#### Scenario: ClickHouse failure marks the table FAILED

- **WHEN** the ClickHouse `CREATE TABLE` fails during the define-and-materialize call
- **THEN** the system issues a best-effort `DROP TABLE IF EXISTS`, sets the definition to
  `status=FAILED`, and the response is an error using the `ErrorView` contract with no raw ClickHouse
  internals

### Requirement: Table responses carry caller-effective permissions

Each table returned by `GET /v1/tables` (every list item) and `GET /v1/tables/{name}` SHALL carry a
`permissions` object with boolean `get`, `read`, `sensitive`, `write` and `modify`
fields describing what the calling identity may do with that table, computed per the
caller-effective permissions rules of
[table-access-control](../table-access-control/spec.md). All five fields SHALL always be present
booleans (never null/absent). The object grew from two flags to five with the per-table grants, and the
two original names keep their spelling and meaning, so a client reading only those is unaffected.
`sensitive` reports the clearance (see `table-access-control`) and replaces the former `read_sensitive`
flag, which SHALL NOT be emitted.
Mutation responses that return the table representation (create, description update, schema PATCH)
SHALL carry the same object.

#### Scenario: List items carry permissions

- **WHEN** `GET /v1/tables` is called
- **THEN** every list item carries a `permissions` object with all five boolean fields

#### Scenario: Single get carries permissions

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response carries a `permissions` object with all five boolean fields

#### Scenario: Create response reports the creator's permissions

- **WHEN** an authorized caller creates a table via `POST /v1/tables`
- **THEN** the 201 response reports the creator's own effective permissions on the new table — all
  five `true` for a caller holding every table-scoped privilege, and `false` for any the caller does
  not hold, since `TABLE_CREATE` alone confers none of them

### Requirement: Seeded column sensitivity on the built-in source tables

The catalog migrations SHALL seed the `sensitive` flag on the built-in tables so that columns
carrying caller identity or raw user content are restricted to a caller holding the clearance
`TABLE_SENSITIVE` on the table that owns them, globally or granted on that table, alongside the verb
it uses. The restriction applies by
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
  `user_hash` with `sensitive: false`, `dial_usage_log_payload` reports all three of its columns with
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

### Requirement: Heavy column metadata

Each exposed column SHALL carry a boolean `heavy` flag, default `false`, marking the column as
expensive to transfer (a large payload/blob column). Like `tag`, `display_name`, and `sensitive`, it
SHALL be **catalog-only metadata**: it SHALL NOT appear in any ClickHouse DDL, SHALL NOT be validated
against the physical column type, and changing it SHALL move no data and issue no `ALTER`.

The flag SHALL be declarable wherever column metadata is declared — a `columns` entry of
`POST /v1/tables/{name}/schema` and an `add` entry of `PATCH /v1/tables/{name}/schema` (defaulting to
`false` when omitted) — and changeable on a materialized table through the `PATCH` `update`
merge-patch. It SHALL be returned in the column payloads of `GET /v1/tables/{name}` unfiltered by
role, and preserved across a `rename`. The flag SHALL be settable on any
exposed column, including a system, grain, or ordering-key column.

`heavy` SHALL be a **cost hint, not an access control**: it SHALL NOT hide the column from any
caller and SHALL NOT restrict any clause. Its only effect is on the query surfaces' wildcard
projection (see the `structured-query` and `sql-query-translation` capabilities): a heavy column is
omitted from a wildcard expansion and returned normally when named explicitly. Confidentiality stays
governed exclusively by `sensitive`.

#### Scenario: Heavy declared at schema definition

- **WHEN** a `POST /v1/tables/{name}/schema` body declares one column with `heavy: true` and the
  others without the member
- **THEN** the table materializes with no reference to heaviness in the issued `CREATE TABLE`, and
  the catalog records `heavy: true` for that column and `false` for the rest

#### Scenario: Heavy toggled on a materialized table

- **WHEN** `PATCH /v1/tables/{name}/schema` sends `{"update": [{"name": "payload", "heavy": true}]}`
  on an `ACTIVE` table
- **THEN** the response is 200, no ClickHouse `ALTER` is issued, and `GET /v1/tables/{name}` reports
  that column with `heavy: true`

#### Scenario: Heavy is settable on a protected column

- **WHEN** an `update` entry marks an ordering-key column or a `_`-prefixed system column `heavy: true`
- **THEN** the request succeeds — the column-mutability restrictions apply to `drop`/`rename`, not to
  metadata

#### Scenario: Heavy does not restrict visibility

- **WHEN** a column is marked `heavy: true` and `sensitive: false`, and a caller holding `TABLE_READ`
  but not `TABLE_SENSITIVE`
  reads the query schema and then queries that column by name
- **THEN** the column is present in the discovered schema and the query returns its values — the flag
  changes only what a wildcard expands to

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
built-in source tables"), so for a caller without `TABLE_SENSITIVE` on the owning table they are
already absent from the
model and the heavy flag changes nothing.

For an entitled caller the flag is now belt-and-braces on a wildcard over `dial_usage_log`: every
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

- **WHEN** a caller holding `TABLE_GET` reads `GET /v1/tables/dial_usage_log_payload`
- **THEN** the response lists `response_body` with `heavy: true` alongside its `sensitive: true`, so
  an operator can audit which columns a wildcard omits

#### Scenario: A wildcard omits the merged response

- **WHEN** an entitled caller runs a wildcard projection over `dial_usage_log`
- **THEN** no row carries an `assembled_response`, `request_body`, `response_body`, `jwt_claims` or
  `request_tags` key under any spelling — the last two excluded for being enrichment columns despite
  not being heavy — and naming any of them by its qualified name returns its value in full

### Requirement: Catalog reads require a table privilege

The catalog read surface SHALL be authorized per table rather than by application role.
`GET /v1/tables` SHALL require `TABLE_LIST` and SHALL return only the definitions the caller may
see; `GET /v1/tables/{name}` and `GET /v1/tables/{name}/revision/{revision}` SHALL require
`TABLE_GET` on the named table and SHALL respond `404 not_found` when the caller lacks it, so the
listing does not disclose the existence of tables the caller may not see.

A table the caller holds no read-side privilege on SHALL be absent from the listing rather than
present-but-forbidden, and an empty listing SHALL be `200` with no entries rather than `403`.

The read surface SHALL be **filtered at column granularity** by the clearance, as specified in "Read
and list table definitions": `TABLE_GET` alone exposes the non-sensitive column inventory, and
`TABLE_GET` with `TABLE_SENSITIVE` exposes every column with its `sensitive` flag. A revision read
(`GET /v1/tables/{name}/revision/{revision}`) SHALL apply the same filter. It uses the flag each column
carried **at that revision** and the caller's clearance **now**, so a column that was sensitive then
stays hidden from an uncleared caller even if it has since been declassified.

#### Scenario: The listing is caller-filtered

- **GIVEN** two tables, one of which grants `read` to the caller's provider role
- **WHEN** a caller holding no global read privilege lists the catalog
- **THEN** the response is `200` and contains only the granted table

#### Scenario: An unreadable definition is not found

- **WHEN** that caller requests the other table by name
- **THEN** the response is `404 not_found` and does not reveal that the table exists

#### Scenario: A revision read honours the same privilege

- **WHEN** that caller requests a revision of the other table
- **THEN** the response is `404 not_found`

#### Scenario: A caller with no grants sees an empty listing

- **WHEN** a caller holding no global read privilege and named in no access list lists the catalog
- **THEN** the response is `200` with no entries

#### Scenario: A gettable table still shows sensitive column names

- **GIVEN** a table whose `get` and `sensitive` lists contain the caller's provider role and which has
  a sensitive column
- **WHEN** the caller reads that table's definition, and a revision of it at which the column was
  already sensitive
- **THEN** both responses carry the column with `sensitive: true`
- **AND WHEN** the caller's role is removed from the table's `sensitive` list
- **THEN** the sensitive column is absent from both responses
