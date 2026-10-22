## MODIFIED Requirements

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
`oidc` mode this endpoint SHALL be authorized by `FULL_ADMIN` or a matching `modify` access role
on the table. The system SHALL reject: a type-change of an existing column (422); a drop or rename
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

- **WHEN** a non-`FULL_ADMIN` caller holding a matching `modify` access role on an `ACTIVE` table
  `PATCH`es its schema
- **THEN** the modification is authorized and processed


### Requirement: Delete a table definition

The system SHALL delete a table definition at `DELETE /v1/tables/{name}`, removing its catalog rows
and issuing `DROP TABLE` in ClickHouse (writer credential), returning HTTP 204. Deleting a table SHALL
also remove its access lists (see the `table-access-control` capability).
Deleting a `source` that still has enrichments referencing it SHALL be rejected with HTTP 409 until
those enrichments are deleted. Deleting a table that a **pipeline reads as its input** SHALL likewise
be rejected with HTTP 409, naming the pipelines, until each is deleted or repointed — otherwise a pipeline
would survive pointing at a table that no longer exists and fail on its next run instead of at the moment
the dependency was broken.

This SHALL be **one check across both kinds**, where the two registries needed two. The enrichment registry
asked it as "which rules declare this as their read source" and the aggregate registry as "which pipelines
read this as their input", and after the merge those are the same question of the same column — a declared
read source *is* a pipeline's input. The reason it was stated twice remains true of neither: it is not the
same protection the enrichment check gives a source read through a target enrichment, because an input need
have no enrichment of its own.

A second, distinct check SHALL protect an **enrichment** whose columns a declaration reads through the
grain join without targeting it. That one cannot be answered by the input lookup — it asks which
*declarations* name a column of this enrichment, and the reader's own input is a different table entirely —
so it survives the merge as its own query (see the
[enrichment-registry](../enrichment-registry/spec.md) capability).

That second check SHALL consult **every stored declaration, whatever its kind**. An `aggregate`
declaration reads an enrichment through the same grain join and is broken by the drop in the same way, so
scoping the guard to one kind protected an enrichment only when a declaration of that kind happened to
read it too — which is coincidence, not a guard.

Because the two kinds name their columns in different places, the check SHALL resolve each declaration
through the gate its own kind selects: an `enrich` declaration names them in its bindings, filter,
readiness signal and its evaluator's output expressions, an `aggregate` one in its group keys, its
measures, its filter and each measure's own `where`. Deleting a table that is a pipeline's **target** SHALL be permitted and SHALL remove the
pipeline with it: the pipeline exists only to fill that table.
Deleting an unknown definition SHALL return 404. This endpoint SHALL
require the `FULL_ADMIN` authority in `oidc` mode; per-table access roles SHALL NOT authorize a delete.

**Both dependency checks resolve their answer from stored declarations, and therefore cover complete
declarations only** — the same bound this capability states for `PATCH /v1/tables/{name}/schema`,
carried here because the same under-declaration reaches the same guards through a different verb. Since
a declaration may now be stored before it is complete (see
[pipeline-registry](../pipeline-registry/spec.md), "The declaration gate runs when a write arms the
pipeline, not when it is stored"), an incomplete declaration under-declares both its input and its
enrichment reads: a pipeline registered with a target and nothing else names no input at all, so the
table it will eventually read stays deletable, and one whose governed fragments are half written names
only the enrichment columns already in them.

The bound is stated rather than closed for the reason the schema-patch side gives: closing it would mean
refusing a delete on the strength of a declaration that does not resolve, which is a guard with no way to
compute its own answer. The consequence is bounded on the other side instead — completing such a
declaration afterwards **fails at the declaration gate**, with HTTP 422 naming the table or column that
no longer exists, so the cost is lost authoring work on a pipeline that never ran and never a run against
a table that is gone. A pipeline that was ever armed had a complete declaration, so its input and its
enrichment reads are stored and both guards hold for it exactly as before.

The **target** side is unaffected by any of this, and deliberately so: `target` is required at
registration, so every stored pipeline — draft or armed — names one, and deleting that table still
removes the pipeline with it rather than leaving a draft pointing at nothing.

#### Scenario: Delete drops the ClickHouse table

- **WHEN** `DELETE /v1/tables/{name}` is called for a table with no dependents
- **THEN** the catalog rows are removed, `DROP TABLE` is issued in ClickHouse, and the response is 204

#### Scenario: Delete of a source with enrichments is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` that has enrichments referencing it
- **THEN** the request is rejected with HTTP 409 and no table is dropped

#### Scenario: Delete of a pipeline's declared read source is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` that a pipeline declares as its input, and
  no enrichment references that source
- **THEN** the request is rejected with HTTP 409 naming the pipeline, and no table is dropped

#### Scenario: Delete of an enrichment only an aggregate pipeline reads is blocked

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment whose column an `aggregate` pipeline's group key,
  measure, filter or measure `where` reads, and no `enrich` declaration reads it
- **THEN** the request is rejected with HTTP 409 naming that pipeline, and no table is dropped

#### Scenario: The enrichment becomes deletable once nothing reads it

- **WHEN** the last pipeline reading that enrichment is deleted and the delete is retried
- **THEN** it succeeds — the guard is a dependency, not a lock

#### Scenario: One check answers for both kinds

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` read by an `enrich` pipeline and by an
  `aggregate` pipeline
- **THEN** the request is rejected with HTTP 409 naming both, from one lookup rather than one per kind

#### Scenario: Delete of a pipeline's input table is blocked

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that a pipeline reads as its input
- **THEN** the request is rejected with HTTP 409 naming the pipeline, and no table is dropped

#### Scenario: Delete of a pipeline's target removes the pipeline

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that is a pipeline's target
- **THEN** the table is deleted and its pipeline is removed with it

#### Scenario: Delete removes the table's access

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that has per-table access roles
- **THEN** the table is deleted and its access lists are removed with it

#### Scenario: A table an incomplete declaration will eventually read stays deletable

- **WHEN** `DELETE /v1/tables/{name}` is called for a `source` with no enrichments that the only
  pipeline destined to read it has not yet named as its input, that pipeline being disabled
- **THEN** the delete succeeds, because both dependency checks resolve readers from stored declarations
  and that declaration declares no such read

#### Scenario: Completing that declaration afterwards fails at the gate rather than reading a deleted table

- **WHEN** the author then declares that table as the pipeline's input and arms or previews the pipeline
- **THEN** the request is rejected with HTTP 422 naming the table that does not exist, so the cost of
  the bound is the authoring work and never a run against a table that is gone

#### Scenario: A complete declaration blocks the delete even while disabled

- **WHEN** `DELETE /v1/tables/{name}` is called for a table that a **complete** declaration names as its
  input or whose enrichment column that declaration reads, and that pipeline is disabled
- **THEN** the request is rejected with HTTP 409 naming that pipeline, and no table is dropped —
  completeness, not enablement, is what arms these guards
