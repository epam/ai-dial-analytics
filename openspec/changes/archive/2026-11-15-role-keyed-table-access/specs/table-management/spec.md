## ADDED Requirements

### Requirement: Column policies follow a rename and refuse an emptying drop

A column policy (see [table-access-control](../table-access-control/spec.md), "Per-role column policies") names
columns, so a schema change SHALL keep every stored policy pointing at the columns it meant and SHALL never widen one.

**Rename.** A `PATCH /v1/tables/{name}/schema` that renames a column SHALL rewrite every entry on the table whose
`columns` lists it to the new name, in the same transaction and the same audit revision as the rename. Renaming a
column of an enrichment SHALL rewrite `<enrichment>.<old>` to `<enrichment>.<new>` in the entries of its source.

**Drop.** A column dropped by `PATCH /v1/tables/{name}/schema`, a column a re-defined schema of a `PENDING` or `FAILED`
table no longer declares (`POST /v1/tables/{name}/schema`), and every column of an enrichment removed by
`DELETE /v1/tables/{enrichment}` SHALL be removed from every `columns` list that names it (as `<enrichment>.<column>`
in the source's entries for an enrichment's column). When removing them would leave any list **empty**, the request
SHALL instead be refused with HTTP 409 and machine code `column_in_column_policy`, naming the columns and the roles
whose lists they would empty, before any DDL is issued or any catalog row changes. A list SHALL never become `null`
through a schema change, because that would show the role every column, and SHALL never become empty, because that
is not a valid policy. Editing or removing those entries first SHALL make the operation possible. A re-defined schema
that omits a column the caller cannot see drops it like any other omitted column, as for an unentitled sensitive
column; the rules above still protect every list that names it.

**Order within one `PATCH`.** A `PATCH` that combines operations SHALL rewrite the lists in the request's canonical
order, against the lists as they stood before the request: first every `drop` prunes the dropped column's name, then
every `rename` rewrites `from` to `to` in the pruned lists. A column `add`ed in the same request is never listed by an
existing entry, even when it reuses a dropped column's name, because the drop already pruned that name. The emptying
refusal SHALL be evaluated on the lists after the prune; a rename only rewrites names a list still holds, so it never
refills a list the prune emptied, and such a request is refused.

These guarantees SHALL hold against a concurrent `PUT /v1/tables/{name}/access/{role}`: the refusal and the rewrite
SHALL be decided on the entries as they stand when the schema change commits, and a `PUT` committed after a rename or
drop SHALL be validated against the columns as they stand then. No interleaving SHALL leave a list empty or naming a
column the table (or, for a qualified name, the enrichment) no longer has, and none SHALL make the refusal arrive
after the `ALTER`: a schema `PATCH` SHALL hold the definition's row lock (and, for an enrichment, its source's) from
before it reads any policy until its catalog changes commit, so the entries it decides on before issuing the `ALTER`
are the entries it commits against.

These rules SHALL hold whoever the caller is, and in `none` mode too, because a policy over a missing column would
be invalid the moment it is enforced. A rename or drop that rewrites any entry SHALL increment, by one, the access
`generation` of the table whose entries it rewrites — the source's for a column of an enrichment or an enrichment's
delete, never the enrichment's (see table-access-control, "Access configuration generation"); one that rewrites none
SHALL leave every generation as it was. The refusal of a column a row policy references (see "A column a row policy
references is protected against drop and rename") SHALL be checked first, so a column referenced by both is refused
`409 column_in_row_policy`. Apart from that, `409 column_in_column_policy` SHALL be checked in no specified order
against the request's other refusals — a schema `PATCH`'s other `422` and `409` checks, a re-define's validation, and
an enrichment delete's `409` for a pipeline that reads it — so a request failing more than one of them may
receive any of their codes; every one of them is decided before any DDL is issued or any catalog row changes.

**Audit.** Every rewrite of a stored list — by a rename, a drop, a re-define or an enrichment delete — SHALL be
recorded in the revision of the operation that caused it, as an `Update` activity of the table **whose entries
changed**: the table itself for its own column, the source for an enrichment's column or an enrichment's delete. That
activity's snapshot carries the rewritten entries. A table whose entries the operation does not change records no
activity for them.

A migration that removes or renames a catalog column outside the API (in raw SQL) SHALL prune or rewrite the
`column_policies` entries that name it in the same migration, so a name left behind never re-attaches to a column
added later under it.

#### Scenario: A rename rewrites the lists that name the column

- **GIVEN** a table at access `generation` 4 on which role `X`'s entry carries `columns` `["a", "b"]`
- **WHEN** a caller holding `TABLE_MODIFY` renames `b` to `c`
- **THEN** `X`'s entry carries `["a", "c"]`, a caller carrying only `X` still sees the renamed column, and the access
  `generation` is 5

#### Scenario: Renaming an enrichment column rewrites the source's lists

- **GIVEN** a source table `usage` created through the API, with an enrichment `deployment_ref`, and an entry on
  `usage` whose `columns` names `deployment_ref.display_name`
- **WHEN** `deployment_ref`'s column `display_name` is renamed to `label`
- **THEN** the entry names `deployment_ref.label`, `usage`'s access `generation` has moved by one, and
  `deployment_ref`'s has not
- **AND** the rename's revision records an `Update` activity for `usage` whose snapshot carries the rewritten
  entry, besides the activities it records for `deployment_ref`

#### Scenario: A drop prunes a list that keeps other columns

- **GIVEN** role `X`'s entry carries `columns` `["a", "b"]`
- **WHEN** a caller holding `TABLE_MODIFY` drops `b`
- **THEN** the drop is applied and `X`'s entry carries `["a"]`

#### Scenario: Dropping a column and adding a new one under its name in one PATCH

- **GIVEN** role `X`'s entry carries `columns` `["a", "c"]`
- **WHEN** a caller holding `TABLE_MODIFY` sends one `PATCH` that drops `c` and adds a new column `c`
- **THEN** the request is applied and `X`'s entry carries `["a"]`, so a caller carrying only `X` does not see the new
  `c`

#### Scenario: Dropping a column and renaming another to its name in one PATCH

- **GIVEN** role `X`'s entry carries `columns` `["a", "b", "c"]`
- **WHEN** a caller holding `TABLE_MODIFY` sends one `PATCH` that drops `c` and renames `b` to `c`
- **THEN** the request is applied and `X`'s entry carries `["a", "c"]`, where `c` is the renamed `b`

#### Scenario: A drop that would empty a list is refused

- **GIVEN** role `X`'s entry carries `columns` `["b"]`
- **WHEN** a caller drops `b`
- **THEN** the request is refused `409 column_in_column_policy` naming `b` and `X`, no `ALTER` is issued, and `X`'s
  entry is unchanged

#### Scenario: Deleting an enrichment that a list depends on is refused

- **GIVEN** an entry on a source whose `columns` names only columns of its enrichment `e`
- **WHEN** a caller deletes `e`
- **THEN** the request is refused `409 column_in_column_policy` and `e` is not dropped
- **AND WHEN** the list also names a column of the source
- **THEN** `e` is deleted and the list keeps only the source's column

#### Scenario: Re-defining a failed table without a listed column

- **GIVEN** a `FAILED` table with columns `a` and `b` and an entry whose `columns` is `["b"]`
- **WHEN** a caller posts the table's schema again without `b`
- **THEN** the request is refused `409 column_in_column_policy`, the stored columns are unchanged, and no `CREATE` is
  issued

#### Scenario: A column referenced by a row policy and a column policy

- **GIVEN** column `project_id`, referenced by role `X`'s row policy and listed alone in role `Y`'s `columns`
- **WHEN** a caller drops `project_id`
- **THEN** the request is refused `409 column_in_row_policy`

#### Scenario: A schema change that touches no list leaves the generation

- **GIVEN** a table at access `generation` 2 whose entries list only `a`
- **WHEN** column `z` is dropped
- **THEN** the access `generation` is still 2

#### Scenario: A drop racing an access PUT never empties a list

- **GIVEN** role `Y` has no entry, and a table has columns `a` and `b`
- **WHEN** a `PUT` storing `columns` `["b"]` for `Y` and a `PATCH` dropping `b` run concurrently
- **THEN** either the `PUT` commits first and the drop is refused `409 column_in_column_policy`, or the drop commits
  first and the `PUT` is refused `422 validation_error` naming `b`; `Y` never holds `[]` or a list naming `b` once `b`
  is gone

## MODIFIED Requirements

### Requirement: Read and list table definitions

The system SHALL expose `GET /v1/tables/{name}` returning a single definition including its current
`columns` (404 if absent) and `GET /v1/tables` returning all definitions — sources and enrichments —
as a response object (not a bare array). Listing SHALL use a stable order. Each list
item SHALL carry a `column_count` field: the number of exposed columns of that table, equal to the
length of the `columns` array `GET /v1/tables/{name}` returns for the same table (`0` for a
definition with no exposed columns yet); `column_count` SHALL NOT appear on the single-get response.
Physical columns the catalog does not register — such as an enrichment's grain key or its alignment
column — SHALL NOT appear in the
exposed `columns` and SHALL NOT be counted by `column_count`; a reserved `_`-prefixed column a seeded
table registers (`dial_usage_log`'s `_ingested_at` and `_source_file`) is exposed and counted like any
other. Each exposed column SHALL carry its optional
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
clearance and column visibility as the query surface (see `authentication`, and
[table-access-control](../table-access-control/spec.md), "Column visibility by caller role"). A `sensitive` column
SHALL appear in `columns`, and SHALL be counted by `column_count`, only for a caller holding `TABLE_SENSITIVE` on that
table, globally or by grant, or in `none` security mode; a column hidden from the caller by a column policy SHALL
appear and be counted for no caller it is hidden from. For every other caller such a column SHALL be omitted as if it
did not exist, and a column that is both sensitive and hidden SHALL be omitted and left uncounted once. There is no
redacted placeholder, and `column_count` SHALL still equal the length of the `columns` array the same caller receives.
An operator auditing or managing sensitivity SHALL therefore hold the clearance. The definition's structural fields
(`ordering_key`, `partition_by`, `grain_key`, `identity_column`, `version_column`, `ttl`) SHALL NOT be filtered: they
are returned as stored, even where they name a column the caller cannot see.

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

#### Scenario: A column-restricted caller's count matches its columns

- **GIVEN** a table with columns `a`, `b`, `c` and `s`, `s` sensitive, and an entry for role `X` with `read` and
  `columns` `["a", "s"]` and no clearance
- **WHEN** a caller carrying only `X` calls `GET /v1/tables/{name}` and `GET /v1/tables`
- **THEN** the single-get `columns` array lists `a` only, and the list item carries `column_count: 1`

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

### Requirement: Table responses carry caller-effective permissions

Each table returned by `GET /v1/tables` (every list item) and `GET /v1/tables/{name}` SHALL carry a
`permissions` object with boolean `get`, `read`, `sensitive`, `write`, `modify`, `rows_restricted` and
`columns_restricted` fields describing what the calling identity may do with that table, computed per the
caller-effective permissions rules of
[table-access-control](../table-access-control/spec.md). All seven fields SHALL always be present
booleans (never null/absent). The object grew from two flags to five with the per-table grants, to
six with row policies, and to seven with column policies; the earlier names keep their spelling and meaning, so a
client reading only those is unaffected. `rows_restricted` reports whether row policies confine the caller's reads of
the table, and `columns_restricted` whether column policies hide any of its columns from the caller.
`sensitive` reports the clearance (see `table-access-control`) and replaces the former `read_sensitive`
flag, which SHALL NOT be emitted.
Mutation responses that return the table representation (create, description update, schema PATCH)
SHALL carry the same object.

#### Scenario: List items carry permissions

- **WHEN** `GET /v1/tables` is called
- **THEN** every list item carries a `permissions` object with all seven boolean fields

#### Scenario: Single get carries permissions

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response carries a `permissions` object with all seven boolean fields

#### Scenario: Create response reports the creator's permissions

- **WHEN** an authorized caller creates a table via `POST /v1/tables`
- **THEN** the 201 response reports the creator's own effective permissions on the new table — `get`,
  `read`, `sensitive`, `write` and `modify` all `true` for a caller holding every table-scoped
  privilege, and `false` for any the caller does not hold, since `TABLE_CREATE` alone confers none of
  them — and `rows_restricted = false` and `columns_restricted = false`, because a new table carries no entry

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

The request SHALL be resolved against the columns **visible** to the caller only, the same set
`GET /v1/tables/{name}` returns to that caller: a column is hidden from it when the column is `sensitive` and the
caller does not hold `TABLE_SENSITIVE` on the table, or when a column policy hides it from the caller, whatever its
clearance (see [table-access-control](../table-access-control/spec.md), "Column visibility by caller role"). A
hidden column SHALL be treated as not existing: a `drop`, a `rename.from` or an `update` naming it
SHALL be rejected as naming an unknown column (422), with the same message as for a column that does
not exist at all. A caller that does not hold `TABLE_SENSITIVE` on the table SHALL also be refused with
`403 forbidden` any `update` that supplies a `sensitive` member (either value), and any `add` that declares
`sensitive: true`. An `add` or
`rename.to` whose name collides with a hidden column SHALL still be rejected (422, already exists), whether it
collides on the exposed name or on the physical one, and before any `ALTER` is issued. That discloses that
the name is taken, and it is the accepted limit of hiding (see `authentication`). The per-table column
limit SHALL count hidden columns too. A `ttl.column` SHALL resolve against the visible columns only, so
naming a hidden column fails as naming an undeclared one. While the stored TTL is on a column hidden from the
caller, any `ttl` member (set, replace or remove) SHALL be refused and the TTL left unchanged: with
`403 column_not_entitled` naming that column when a column policy hides it from the caller, whether or not it is
also sensitive, and otherwise, where it is sensitive and the caller lacks the clearance, with `403 forbidden`. A
caller from which neither the clearance nor a column policy hides any column resolves against every column and MAY
make any of these changes. The system SHALL reject: a type-change of an existing column (422); a drop or rename
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

The system SHALL reject a `rename` of an **enrichment's provenance column** — a column whose physical
name is one of `pipeline_generation`, `model`, `enriched_at`, `group_version`, `truncated` or
`evaluator_version` (see "Provenance columns are injected into an enrichment's schema definition") —
with HTTP 422. The write path stamps provenance by its canonical name and silently skips a name the
target does not carry, so a rename would move the stamped values under the new name while every later
row went unstamped, with no error anywhere. The converse is rejected the same way: a `rename` whose `to`
is a provenance name, which a `drop` in the same or an earlier request may have freed, would have the
pipeline's stamps written over that column's own values. A `drop` of a provenance column SHALL remain allowed: it is
an explicit opt-out of that lineage value (the only way to remove the three columns a Class-1 target
can never populate), and the startup reconciler restores `pipeline_generation`, empty, on a dropped
enrich target. The rule is enrichment-only: a source column that happens to share a provenance name
renames like any other.

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

#### Scenario: An enrichment's provenance column cannot be renamed

- **WHEN** a `PATCH` on an `ACTIVE` enrichment renames a provenance column such as `enriched_at`
- **THEN** the request is rejected with HTTP 422 naming the column, and no ClickHouse `ALTER` is issued

#### Scenario: A column cannot be renamed onto a provenance name

- **WHEN** a `PATCH` on an `ACTIVE` enrichment drops `enriched_at` and renames another column to `enriched_at`
- **THEN** the request is rejected with HTTP 422, and no ClickHouse `ALTER` is issued

#### Scenario: An enrichment's provenance column can be dropped

- **WHEN** a `PATCH` on an `ACTIVE` enrichment drops a provenance column that no pipeline output binds
- **THEN** the column is dropped, and later writes skip that provenance value

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

#### Scenario: A TTL on a policy-hidden column is refused column_not_entitled

- **GIVEN** an `ACTIVE` table whose stored TTL is on a `timestamp` column `c` that a column policy hides from a
  caller holding `TABLE_MODIFY` and `TABLE_SENSITIVE` on the table
- **WHEN** the caller sends `{"ttl":{}}`, and separately a full `ttl` object naming a visible `timestamp` column
- **THEN** each response is `403 column_not_entitled` naming `c`, no `ALTER` is issued, and the TTL is unchanged
- **AND WHEN** `c` is also sensitive and a caller holding `TABLE_MODIFY` but not `TABLE_SENSITIVE`, from which the
  same policy hides `c`, sends `{"ttl":{}}`
- **THEN** the response is still `403 column_not_entitled`, not `403 forbidden`

### Requirement: Catalog reads require a table privilege

The catalog read surface SHALL be authorized per table rather than by application role.
`GET /v1/tables` SHALL require `TABLE_LIST` and SHALL return only the definitions the caller may
see; `GET /v1/tables/{name}` and `GET /v1/tables/{name}/revision/{revision}` SHALL require
`TABLE_GET` on the named table and SHALL respond `404 not_found` when the caller lacks it, so the
listing does not disclose the existence of tables the caller may not see.

A table the caller holds no read-side privilege on SHALL be absent from the listing rather than
present-but-forbidden, and an empty listing SHALL be `200` with no entries rather than `403`.

The read surface SHALL be **filtered at column granularity** by the clearance and by column visibility, as
specified in "Read and list table definitions": `TABLE_GET` alone exposes the non-sensitive column inventory, and
`TABLE_GET` with `TABLE_SENSITIVE` exposes every column with its `sensitive` flag, in each case less the columns a
column policy hides from the caller (see [table-access-control](../table-access-control/spec.md), "Hidden columns
are absent from every read surface"). A revision read (`GET /v1/tables/{name}/revision/{revision}`) SHALL apply the
same filter. It uses the flag each column carried **at that revision** and the caller's clearance **now**, so a
column that was sensitive then stays hidden from an uncleared caller even if it has since been declassified; and it
hides the columns the column policies the table had **at that revision** hide from the caller, an enrichment's
revision by its source's current column policies (see [audit-trail](../audit-trail/spec.md)).

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

- **WHEN** a caller holding no global read privilege and carrying no role with an access entry lists the catalog
- **THEN** the response is `200` with no entries

#### Scenario: A gettable table still shows sensitive column names

- **GIVEN** a table on which the access entry for the caller's provider role carries `get` and `sensitive`, and
  which has a sensitive column
- **WHEN** the caller reads that table's definition, and a revision of it at which the column was
  already sensitive
- **THEN** both responses carry the column with `sensitive: true`
- **AND WHEN** `sensitive` is removed from that role's entry
- **THEN** the sensitive column is absent from both responses

#### Scenario: A revision read hides the columns that revision's column policies hide

- **GIVEN** a table with columns `a` and `b`, on which the caller's role gained an entry carrying `get` and
  `columns` `["a"]` at revision 2, and whose entry at revision 1 carried `get` and no `columns`
- **WHEN** the caller reads revision 2 and revision 1
- **THEN** revision 2 carries `a` only and revision 1 carries `a` and `b`
