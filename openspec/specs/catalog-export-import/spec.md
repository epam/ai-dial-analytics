# catalog-export-import Specification

## Purpose

This capability defines catalog promotion between ADAS environments: `POST /v1/catalog/export` writes a versioned
bundle of user tables, their columns, and pipelines with their dependency closure, and
`POST /v1/catalog/import` validates such a bundle and recreates it on another environment. Each call has a
preview twin, and the API shape mirrors deployment manager's config export/import. Import never changes an existing
object's definition, columns or pipeline: system tables travel by name as required system tables whose definitions are
checked, never written, and pipelines always land disabled. On request (`include_access`, with `TABLE_GRANT`) the
bundle also carries the covered tables' access entries, system tables included, and the import upserts them per role. Both import calls share one validation pass, which runs the real create services in a
rolled-back transaction. The surrounding operator sequence is in `docs/catalog-promotion.md`.
## Requirements
### Requirement: Catalog export endpoint

`POST /v1/catalog/export` SHALL return a catalog bundle of the components named in its `ExportRequest` body, as `application/json`
with the header `Content-Disposition: attachment; filename="adas-catalog-<timestamp>.json"`.

The body SHALL be `{ "components": [ { "type": "table" | "pipeline", "name": "<name>" } ], "include_access": <boolean> }`, where
`include_access` is optional and defaults to `false` (see "Access export"). An empty or absent `components` list
SHALL export every exportable object: every ACTIVE table that is neither system-flagged nor an OTLP landing table, and every pipeline
that does not read an OTLP landing table. An unknown `type` SHALL be `400`. A named table that does not exist SHALL be `404` with
`error` `not_found`, and a named pipeline that does not exist SHALL be `404` with `error` `unknown_pipeline`. Export SHALL write
nothing.

#### Scenario: Export everything

- **WHEN** `POST /v1/catalog/export` is called with `{}` on an environment holding user tables, an OTLP sink and pipelines
- **THEN** the response is `200` with an attachment bundle holding every ACTIVE user table and every pipeline that reads no OTLP
  landing table, and no OTLP landing table appears under `tables`

#### Scenario: Unknown component name

- **WHEN** the body lists `{ "type": "pipeline", "name": "does_not_exist" }`
- **THEN** the response is `404` with `error` `unknown_pipeline`

#### Scenario: Unknown component type

- **WHEN** the body lists `{ "type": "saved_query", "name": "q" }`
- **THEN** the response is `400` with `error` `bad_request`

### Requirement: Export dependency closure

The export SHALL include the dependency closure of the selected components, computed in both directions: a selected table SHALL pull
in its `source_table` and every pipeline that targets it; a selected pipeline SHALL pull in its inputs and its target; and both SHALL
pull in every enrichment a pipeline reads, including enrichments referenced only as `"<enrichment>.<column>"` tokens inside filter
and measure expressions, or inside an `aggregate` pipeline's `sql` statement. With `include_access` true, a table's access SHALL
also pull in every enrichment that a kept entry's column policy names as `<enrichment>.<column>`, as "Access export" defines. Apart
from that, a system table, selected or reached, SHALL never be expanded: it pulls in neither its `source_table` nor any pipeline, and
is written by name under `required_system_tables` (see "Excluded and system tables on export").

For an `aggregate` pipeline in SQL mode, its inputs SHALL be the input table derived from the statement, and the enrichments it reads
SHALL be found by scanning the stored statement for qualified tokens — the same scan the registry's dependency guards use — rather than
by parsing it. A statement that does not parse SHALL therefore not make its pipeline `unresolvable`: a disabled draft whose `sql` cannot
yet be read is exported with whatever its tokens reference, and with no input when none could be derived.

#### Scenario: Enrichment pulls in its source

- **WHEN** the selection is only the enrichment table `usage_sentiment`
- **THEN** the bundle also holds its source table and the pipeline that targets `usage_sentiment`

#### Scenario: Enrichment referenced only inside a filter

- **WHEN** the selection is an aggregate pipeline whose only reference to enrichment `usage_sentiment` is the filter token
  `"usage_sentiment.sentiment"`
- **THEN** the bundle holds `usage_sentiment` and the pipeline that targets it

#### Scenario: Enrichment referenced only inside an SQL statement

- **WHEN** the selection is an aggregate pipeline in SQL mode whose statement reads `dial_usage_log` and references enrichment
  `usage_sentiment` only as `"usage_sentiment.sentiment"` in its select list
- **THEN** the bundle holds `usage_sentiment` and the pipeline that targets it, and `required_system_tables` names `dial_usage_log`

#### Scenario: An unparseable SQL draft is exported, not skipped

- **WHEN** an export of everything reaches a disabled aggregate pipeline whose stored `sql` does not parse
- **THEN** the pipeline is in the bundle with its `sql` verbatim, and is not listed under `skipped` with `reason` `unresolvable`

#### Scenario: A selected system enrichment is not expanded

- **WHEN** the body selects only `{ "type": "table", "name": "dial_usage_log_payload" }` with `include_access: true`
- **THEN** `dial_usage_log_payload` is listed under `required_system_tables`, and `dial_usage_log` is not required

### Requirement: Export preview

`POST /v1/catalog/export/preview` SHALL take the same body as export and SHALL return the resolved object list without the bundle, and
SHALL write nothing.

Each entry SHALL carry `type`, `name`, `description` and `reason`. `reason` SHALL be `selected`, the dependency that pulled the
object in (for example `source_table of usage_sentiment`, or `column policy of dial_usage_log` for an enrichment an access entry
pulled in), or `access` for a system table that is there
only because exporting everything with access covers it (see "Access export"). The response SHALL also list the `required_system_tables` the bundle would
require and the `skipped` objects. With `include_access` true it SHALL also return `access` (see "Access
export"). Selection errors SHALL be the same as export's.

#### Scenario: Preview shows why a dependency was added

- **WHEN** the export preview selects only enrichment `usage_sentiment`, whose source is `usage_base`
- **THEN** the response lists `usage_sentiment` with `reason` `selected` and `usage_base` with `reason`
  `source_table of usage_sentiment`

### Requirement: Excluded and system tables on export

The export SHALL never write a system table (`TableDefinitionEntity.system`) as a table; it SHALL write under `required_system_tables`,
a list of names with no columns, the name of each system table the closure reaches and, when `include_access` is true, of each system
table whose access the bundle carries or that the selection names (see "Access export"). An explicit selection that names a system
table SHALL be `422` with `error` `catalog_export_invalid` unless `include_access` is true: a system table's definition is required,
never exported, and only its access may be selected.

OTLP landing tables (`otel_<sink>_<signal>`) SHALL be excluded. PENDING and FAILED tables SHALL be excluded. When exporting
everything, each excluded table SHALL be listed under `skipped` with `reason` `otlp`, `pending` or `failed`, a pipeline whose
declaration no longer resolves SHALL be listed with `reason` `unresolvable` (and named in a `422` on explicit selection), and every object that needs
one (an enrichment of it, a pipeline reading or targeting it) SHALL be excluded too and listed with `reason` `dependency_skipped`, or
`otlp` for a pipeline that reads an OTLP landing table. An explicit selection that names or whose closure reaches an OTLP landing table,
a pipeline that reads one, or a PENDING or FAILED table SHALL be `422` with `error` `catalog_export_invalid`.

#### Scenario: System table becomes a required system table

- **WHEN** the closure reaches an enrichment of `dial_usage_log`
- **THEN** `required_system_tables` is `["dial_usage_log"]`, and `dial_usage_log` is not under `tables`

#### Scenario: Explicit system-table selection refused without access

- **WHEN** the body selects the table `dial_usage_log` and does not set `include_access`
- **THEN** the response is `422` with `error` `catalog_export_invalid`

#### Scenario: Explicit OTLP selection refused

- **WHEN** the body selects the table `otel_app_logs`
- **THEN** the response is `422` with `error` `catalog_export_invalid`

#### Scenario: Pending table skipped

- **WHEN** an unselected-everything export runs while a table is PENDING
- **THEN** that table is absent from `tables` and listed under `skipped` with `reason` `pending`

### Requirement: Export authorization

Export SHALL require `REGISTRY_READ` at the endpoint, and SHALL check each selected table with the table-access evaluator for
`TABLE_GET`. With `include_access` true, both `/export` and `/export/preview` SHALL also require global `TABLE_GRANT`, checked after
`REGISTRY_READ` and before the selection is resolved, because the entries and the preview's `access` items disclose which provider
roles exist; a caller without it SHALL receive `403` with `error` `forbidden`, even when the selection names an unknown object.

A selected table, or one its closure reaches, that the caller may not get SHALL be `404` with `error` `not_found`; with no selection
such tables, and every object that needs one, SHALL be silently left out. A bundle table with a sensitive column the caller may not
see SHALL refuse the export with `403` and `error` `sensitive_column_not_entitled`, and one with a column a column policy hides from
the caller (see [table-access-control](../table-access-control/spec.md), "Column visibility by caller role") SHALL refuse it with
`403` and `error` `column_not_entitled`, whether it was selected, reached by the closure or exported as part of everything; columns
are never silently dropped. A column that is both SHALL be refused `column_not_entitled`, and so SHALL a bundle in which one table
has a column hidden by a column policy and another a sensitive column the caller may not see: every bundle table is checked for
policy-hidden columns before any is checked for the clearance. An enrichment is gettable exactly when its
source is, or through its own `modify` (see [table-access-control](../table-access-control/spec.md)), so exporting everything
includes the enrichments of every source the caller may get, and one carrying a sensitive column the caller may not see — because it
lacks the source's clearance — or a column the source's column policies hide from the caller refuses the export as above. Without
`include_access` the bundle SHALL NOT carry access: no table's access entries — privileges, row policies or column policies — are
exported, whatever the caller holds, because access is each environment's own policy unless an export asks for it. When security is
not enforced, every check in this requirement SHALL be skipped.

#### Scenario: Sensitive column the caller cannot see

- **WHEN** a caller with `REGISTRY_READ` and `TABLE_GET` on `t`, but no sensitive clearance on it, exports `t`, which has a sensitive column
- **THEN** the response is `403` with `error` `sensitive_column_not_entitled`

#### Scenario: Column hidden by a column policy

- **WHEN** a caller with `REGISTRY_READ` and `get` on `t`, whose only governing role's `columns` on `t` omits column `c`, exports `t`
- **THEN** the response is `403` with `error` `column_not_entitled`

#### Scenario: A policy-hidden column wins over a sensitive column in another table

- **WHEN** a caller exports a bundle in which table `t1` has a sensitive column it may not see and table `t2` a column a column
  policy hides from it, whichever table is visited first
- **THEN** the response is `403` with `error` `column_not_entitled`

#### Scenario: Table the caller cannot list

- **WHEN** the caller selects a table it may not get
- **THEN** the response is `404` with `error` `not_found`

#### Scenario: Grants never exported

- **WHEN** a caller holding `TABLE_GRANT` exports, without `include_access`, a table that has access entries, row policies and column
  policies
- **THEN** the bundle carries no `access` member, and the table's entry carries none of them

#### Scenario: Exporting access needs TABLE_GRANT

- **WHEN** a caller holding `REGISTRY_READ` and `get` on `t`, but not `TABLE_GRANT`, calls `/export` or `/export/preview` for `t`
  with `include_access: true`
- **THEN** the response is `403` with `error` `forbidden`

#### Scenario: The TABLE_GRANT check precedes the selection

- **WHEN** a caller holding `REGISTRY_READ` but not `TABLE_GRANT` exports `{ "type": "pipeline", "name": "does_not_exist" }` with
  `include_access: true`
- **THEN** the response is `403` with `error` `forbidden`, not `404`

#### Scenario: Security mode none

- **WHEN** security mode is `none` and a caller with no claims exports everything
- **THEN** the response is `200`

#### Scenario: An enrichment of a gettable source joins the export

- **GIVEN** a caller holding `REGISTRY_READ` and `get` on a source with no sensitive column, without the source's
  clearance, and an enrichment of that source with a sensitive column, on which the caller holds nothing
- **WHEN** the caller exports everything
- **THEN** the response is `403` with `error` `sensitive_column_not_entitled`, naming the enrichment

### Requirement: Bundle format and version compatibility

The bundle SHALL carry `format: "adas-catalog"`, an integer `format_version` (currently `4`), `exported_at`, `skipped`,
`required_system_tables`, `tables` (each `{ table, schema }` in request shape), `pipelines` (each
`{ pipeline, exported_enabled }`) and, when exported with `include_access`, `access` (see "Access export").

The bundle SHALL carry no provenance of the exporting ADAS beyond `exported_at`. A bundle SHALL import into the ADAS version that
exported it or any newer one. `format_version` SHALL be raised whenever the shape of any bundle member changes, including a new enum value.

Version `2` adds the `aggregate` declaration's `sql` member. An export SHALL always write the current version, whether or not any
pipeline it carries uses `sql`, so a bundle never claims a version by its content. An ADAS that reads only version `1` therefore refuses
every bundle a version-`2` ADAS writes with the readable `catalog_bundle_version_unsupported` refusal below, rather than the strict
binding's unknown-field `400` that a bundle carrying `sql` under version `1` would meet. A version-`1` bundle SHALL still import into
a version-`2` ADAS unchanged: it carries no `sql`, and every pipeline in it is in form mode.

Version `3` adds an `enrich` transform's `route` with the `dial_app_route` preset, and a `group` pipeline's
`advanced.input_token_budget`. As with version `2`, every export SHALL write `3` whatever its pipelines use, so an ADAS that reads
only version `2` refuses the bundle with `catalog_bundle_version_unsupported` rather than an unknown-field `400`. A version-`1` or
version-`2` bundle SHALL still import into a version-`3` ADAS unchanged: neither carries those members.

Version `4` adds the top-level `access` member. As with the earlier versions, every export SHALL write `4` whether or not it carries
`access`, so an ADAS that reads only version `3` refuses the bundle with `catalog_bundle_version_unsupported` rather than an
unknown-field `400`. A version-`1`, `2` or `3` bundle SHALL still import into a version-`4` ADAS unchanged: none carries `access`.

Import SHALL parse in two stages. Stage 1 SHALL read only `format` and `format_version`, leniently: a wrong or missing `format` SHALL
be `400`, and a `format_version` above the highest supported SHALL be `422` with `error` `catalog_bundle_version_unsupported`, whose
message names the bundle's version. Stage 2 SHALL bind the whole bundle strictly: unreadable JSON, an unknown field at any depth,
an unknown enum value, or a constraint violation SHALL be `400`.

#### Scenario: Bundle from a newer ADAS

- **WHEN** a bundle with `format_version: 5` that also carries unknown fields is uploaded to an ADAS that supports version 4
- **THEN** the response is `422` with `error` `catalog_bundle_version_unsupported`, and the message names version 5

#### Scenario: A bundle carrying sql is refused readably by an older ADAS

- **WHEN** a `format_version: 2` bundle holding an aggregate pipeline with `sql` is uploaded to an ADAS that supports only version 1
- **THEN** the response is `422` with `error` `catalog_bundle_version_unsupported` naming version 2, not a `400` naming `sql`

#### Scenario: A bundle carrying an input token budget is refused readably by an older ADAS

- **WHEN** a `format_version: 3` bundle holding a group pipeline with `advanced.input_token_budget` is uploaded to an ADAS that
  supports only version 2
- **THEN** the response is `422` with `error` `catalog_bundle_version_unsupported` naming version 3, not a `400` naming
  `input_token_budget`

#### Scenario: A bundle carrying access is refused readably by an older ADAS

- **WHEN** a `format_version: 4` bundle holding an `access` member is uploaded to an ADAS that supports only version 3
- **THEN** the response is `422` with `error` `catalog_bundle_version_unsupported` naming version 4, not a `400` naming `access`

#### Scenario: A version 1 bundle still imports

- **WHEN** a `format_version: 1` bundle exported before the bump is uploaded to an ADAS that supports version 4
- **THEN** it is parsed and planned as before, and each of its aggregate pipelines is imported in form mode

#### Scenario: Unknown nested field

- **WHEN** a `format_version: 3` bundle carries an unknown field inside a pipeline's transform
- **THEN** the response is `400`

#### Scenario: Unknown field in an access entry

- **WHEN** a `format_version: 4` bundle carries an access entry with a member other than `privileges`, `rows` and `columns`
- **THEN** the response is `400`

#### Scenario: Missing required nested field

- **WHEN** a `format_version: 3` bundle has a table entry whose `table.name` is blank
- **THEN** the response is `400`

### Requirement: Columns travel as stored

The bundle SHALL carry each table's columns exactly as stored, provenance columns included, with `nullable`, `sensitive` and `heavy`
written explicitly, the stored write mode (not the effective one), and ordinals in stored order. A source's define-schema
`identity_column` and `version_column` SHALL travel as stored.

An enrichment that never declared provenance columns SHALL come back on import with the injected provenance columns added, and those
columns SHALL count toward `max-columns-per-table`. Fields that define-schema or create refuses for an enrichment (`write`,
`identity_column`, `version_column`, `partition_by`, `ordering_key` and the hidden grain column) SHALL NOT be emitted for one.

#### Scenario: Self-declared provenance round-trips

- **WHEN** an enrichment that declares its own provenance columns, with custom descriptions and tags, is exported and imported
- **THEN** `GET /v1/tables/{name}` on the target matches the source, descriptions, tags and order included

#### Scenario: Undeclared provenance is injected

- **WHEN** an enrichment that declared no provenance columns is exported and imported
- **THEN** the imported table carries the declared columns plus the injected provenance columns

### Requirement: Pipelines travel in source projection

The bundle SHALL carry each pipeline as its `view=source` projection, without `generation`, `state`, `enabled`, timestamps, `notes`
and runtime state, and with `exported_enabled` recording whether it was enabled on the source.

A transform declared with legacy `output_vars` SHALL be carried and imported with `output_vars` unchanged, never converted to
`outputs`. An absent `transform.inputs` SHALL stay absent.

An `aggregate` pipeline in SQL mode SHALL be carried with its `sql` exactly as stored — the text the author sent, never a re-rendered
statement — and without `group_by`, `measures` or `filter`, and SHALL be imported with the same text. The registration path the import
uses stores `sql` as a disabled write does, so a statement that does not parse on the target still lands, disabled, as the draft it was.
Its `inputs` SHALL carry the stored input table — the one the source derived from the statement when `inputs` was not declared —
since the registry keeps no record of whether it was declared; the target accepts it because it equals the statement's source, and
derives the input table from the statement when `inputs` is absent, as registration does.

#### Scenario: Legacy output_vars preserved

- **WHEN** a pipeline whose transform declares `output_vars` is exported and imported
- **THEN** the imported pipeline's `view=source` projection declares the same `output_vars` and no `outputs`

#### Scenario: An SQL declaration round-trips verbatim

- **WHEN** an aggregate pipeline declared with an `sql` statement carrying comments, a `WITH` clause and its author's line breaks is
  exported and imported
- **THEN** the imported pipeline's `view=source` projection carries the same `sql`, character for character, and no `group_by`,
  `measures` or `filter`, and it is disabled

### Requirement: Import preview and apply

`POST /v1/catalog/import/preview` and `POST /v1/catalog/import` SHALL each take `multipart/form-data` with a `file` part (the bundle as
exported), and SHALL accept an optional boolean query parameter `acknowledge_reused_names` (default `false`).

`/import/preview` SHALL write nothing and SHALL return `200` with an `ImportPreview` whenever the bundle parses, problems included.
`/import` SHALL run the same validation pass, and only if it finds no problem SHALL apply the bundle and return `200` with an
`ImportResult`, also when an object fails during apply and compensation runs (`outcome` `rolled_back` or `rollback_failed`). Under
`FAIL_IF_EXISTS`, an existing object SHALL make `/import` answer `409` even when the pass would also find other problems; `/import`
checks collisions after authorization and before the validation pass. A missing, non-integral or below-1 `format_version`, a
non-JSON file, a duplicate JSON key or a null list element SHALL be `400` `bad_request`.
A missing `file` part SHALL be `400`. An `options` part with an unknown field SHALL be `400`.

#### Scenario: Preview writes nothing

- **WHEN** `/import/preview` is called with a valid bundle on an empty target
- **THEN** the response is `200`, every entry has `import_action` `CREATE`, and no table, column or pipeline exists afterwards

#### Scenario: Missing file part

- **WHEN** `/import` is called without a `file` part
- **THEN** the response is `400`

#### Scenario: Validation failure on apply

- **WHEN** `/import` is called with a bundle that the validation pass reports a problem for
- **THEN** the response is `422` with `error` `catalog_import_invalid`, the message points to `/import/preview`, and nothing is written

### Requirement: Resolution policy

Both import calls SHALL require the query parameter `resolution_policy`, with deployment manager's values `FAIL_IF_EXISTS`,
`SKIP_IF_EXISTS` and `OVERWRITE`. Import SHALL never mutate an existing table definition, column or pipeline, apart from the access
entries a table definition stores: an import called with `include_access=true` writes those onto existing tables as "Access import"
defines, under either policy. An access entry is not a bundle object for this requirement, so an existing entry never makes `FAIL_IF_EXISTS` conflict.

- `FAIL_IF_EXISTS`: any bundle object that already exists on the target SHALL make `/import` answer `409` with `error`
  `catalog_import_conflict`, writing nothing.
- `SKIP_IF_EXISTS`: existing objects SHALL be kept unchanged, apart from their access, and the rest imported; later bundle objects SHALL be validated against
  the existing object, not the bundle's version of it.
- `OVERWRITE`: SHALL be `422` with `error` `resolution_policy_unsupported` on both calls.

A missing `resolution_policy` SHALL be `400`. A bundle re-run under `SKIP_IF_EXISTS` after a partial failure SHALL converge on the
bundle's objects.

#### Scenario: Second promotion fails under FAIL_IF_EXISTS

- **WHEN** a bundle already imported is imported again with `resolution_policy=FAIL_IF_EXISTS`
- **THEN** the response is `409` with `error` `catalog_import_conflict`, and nothing is written

#### Scenario: Second promotion skips under SKIP_IF_EXISTS

- **WHEN** the same bundle is imported again with `resolution_policy=SKIP_IF_EXISTS`
- **THEN** every entry is `SKIP` with `differs: false`, no revision is written, and the response is `200`

#### Scenario: Only the new object is created

- **WHEN** the bundle is re-exported with one new pipeline and imported with `SKIP_IF_EXISTS`
- **THEN** only that pipeline has `import_action` `CREATE` and is created

#### Scenario: OVERWRITE refused

- **WHEN** either import call passes `resolution_policy=OVERWRITE`
- **THEN** the response is `422` with `error` `resolution_policy_unsupported`

#### Scenario: Missing policy

- **WHEN** either import call omits `resolution_policy`
- **THEN** the response is `400`

#### Scenario: Existing access does not conflict

- **GIVEN** a bundle that requires `dial_usage_log` and carries access for it, and a target whose `dial_usage_log` already has entries
- **WHEN** it is imported with `resolution_policy=FAIL_IF_EXISTS` and `include_access=true`, and no bundle table or pipeline exists
  on the target
- **THEN** the response is `200` with `outcome` `completed`, and each `dial_usage_log` access entry has `import_action` `CREATE`,
  `REPLACE` or `SKIP`

### Requirement: Import entries

`ImportPreview` and `ImportResult` SHALL group entries by type (`required_system_tables`, `tables`, `pipelines`, and `access`, whose entries
"Access import entries" defines), each object entry being
`{ name, import_action, prev, next, differs, metadata_only, problems[] }`. The rules below are about object
entries; an access entry is not an object.

- `import_action` SHALL be `CREATE` (new), `SKIP` (exists under `SKIP_IF_EXISTS`, or a required system table the target has)
  or `FAIL` (exists under `FAIL_IF_EXISTS`, or fails validation under either policy, including a required system table the target lacks
  and a pipeline whose target is held by a differently named pipeline).
- `prev` SHALL be the target's existing object re-exported in bundle shape, or null when new; `next` SHALL be the bundle's object,
  kept on `SKIP` entries too. `prev` SHALL follow the export's visibility rules for the caller: sensitive columns only with the
  clearance, no column a column policy hides from the caller (see [table-access-control](../table-access-control/spec.md),
  "Column visibility by caller role"), and null for a table the caller may not get or a pipeline without
  `REGISTRY_READ`. `differs` is still computed from the full objects, so it MAY be `true` when only a column omitted from `prev`
  differs, as for a sensitive column the caller cannot see.
- An existing user table that is PENDING or FAILED SHALL be `FAIL` under either policy, telling the operator to delete it first; a
  bundle table whose name is a system table on the target SHALL be `FAIL`. An object that depends on a refused object (an enrichment
  of a refused source, a pipeline reading or targeting one) SHALL be `FAIL` naming that dependency.
- `differs` SHALL compare the serialized `prev` and `next`, excluding `enabled`, state and timestamps; `metadata_only` SHALL be true
  when only descriptions, display names or tags differ.
- Pipeline entries SHALL also carry `armable`, `arm_problems[]` and `reused_name`.
- `ImportResult` entries SHALL add `status`: `created`, `skipped`, `rolled_back`, `rollback_failed` or `failed`. An object not
  attempted because the import stopped earlier SHALL be `skipped` with a problem naming where it stopped.
- `import_action` SHALL be written in upper case, as deployment manager writes it.

The top level SHALL carry `env_specific[]` and, on `ImportPreview`, `validation_errors[]` (bundle-level problems: limits, a name
listed twice, a name listed both as a table and as a required system table, and the bundle-level access problems of "Access import"). `ImportResult` SHALL also carry `import_id` and `outcome`:
`completed`; `rolled_back` when an object or the access step failed and compensation removed everything it had created; or `rollback_failed` when any
entry ended `rollback_failed`.

#### Scenario: Differing object under either policy in preview

- **WHEN** `/import/preview` runs with a bundle whose table `t` differs from the target's `t`, under each policy
- **THEN** both responses are `200`, and the `t` entry carries `prev`, `next` and `differs: true`

#### Scenario: Description-only difference

- **WHEN** the only difference between `prev` and `next` is a column description
- **THEN** the entry has `differs: true` and `metadata_only: true`

#### Scenario: A hidden column is omitted from prev

- **GIVEN** a target table `t` with columns `a` and `c`, and a caller holding `TABLE_CREATE` and `get` on `t` whose only
  governing role's `columns` on `t` is `["a"]`
- **WHEN** the caller runs `/import/preview` with a bundle carrying `t`
- **THEN** the `t` entry's `prev` lists column `a` and not `c`

### Requirement: Shared validation pass

Both import calls SHALL run one validation pass that executes the real table create, define-schema and pipeline create
services inside a single Postgres transaction that is always rolled back, with ClickHouse DDL replaced by a no-op, and then runs the
arm-time pipeline checks on each pipeline, once every pipeline is created and, when the import is called with
`include_access=true` and no access entry is `FAIL`, every access entry of the bundle is applied in the same transaction (see
"Access import"), to fill `armable` and `arm_problems`. `armable`, `arm_problems` and each access entry's `ineffective_grants` SHALL
therefore describe the target as the import would leave it, its access included. When an access entry is `FAIL` before the access
is applied (an entry the checks refuse, or a bundle-level problem), none SHALL be applied: arming SHALL still run, against the target
without the bundle's access. When applying the access inside the pass fails, every access entry SHALL be `FAIL` and every pipeline
SHALL carry the `not checked` arm problem instead of being armed, as "Access import" defines. In both cases every access entry's
`ineffective_grants` SHALL be empty. The pass SHALL run in its own transaction, never a caller's, and SHALL tag its log lines with `catalog_import_validation=true`
in the MDC, since the services it calls log registrations and arms that are rolled back. A SQL-level failure while arming SHALL leave
the remaining pipelines with a `not checked` arm problem.

The pass SHALL check identifier rules, reserved names, `max-tables` against the total after import, and `max-columns-per-table`
including injected provenance columns. It SHALL list every Java-side refusal; a SQL-level failure SHALL stop the pass at that object
and be reported on it.

#### Scenario: Armable reported on a fresh target

- **WHEN** `/import/preview` runs a bundle of a source table, an enrichment, an aggregate target, an enrich pipeline and an aggregate
  pipeline on an empty target
- **THEN** each pipeline entry reports `armable: true`, and nothing is written

#### Scenario: Missing target column

- **WHEN** a skipped existing target table lacks a column a bundle pipeline requires
- **THEN** that pipeline reports `armable: false` with an `arm_problems` entry naming the column

#### Scenario: Table limit counted on the total

- **WHEN** the target holds `max-tables - 1` tables and the bundle creates two
- **THEN** `validation_errors` reports the limit and `/import` is `422` with `error` `catalog_import_invalid`

#### Scenario: An imported row policy that confines the importer is reported at arm time

- **GIVEN** a bundle holding a pipeline that reads table `t`, and whose `access` gives a role the importing caller carries a row policy
  on `t` that confines the caller's reads of `t`
- **WHEN** `/import/preview` runs with `include_access=true`
- **THEN** the pipeline entry reports `armable: false` with an `arm_problems` entry saying that a row policy confines the caller's
  reads of `t`, and nothing is written

#### Scenario: Arming runs without the access when an access entry fails

- **GIVEN** the same bundle, with a second access entry the target cannot take
- **WHEN** `/import/preview` runs with `include_access=true`
- **THEN** that entry is `FAIL`, the pipeline entry reports `armable: true` with no `arm_problems` entry about a row policy, and every
  access entry's `ineffective_grants` is empty

### Requirement: Import authorization

Import SHALL require `TABLE_CREATE` at both endpoints, and because the create services are called in-process it SHALL repeat the
controller gates programmatically: global `TABLE_MODIFY` when the bundle creates any table, `REGISTRY_ADMIN` when it creates any
pipeline, and global `TABLE_SENSITIVE` when any created column is sensitive.

A missing privilege SHALL be `403` with `error` `forbidden`. Compensation SHALL delete only objects this import created, without a
`TABLE_DELETE` check. When security is not enforced, every check in this requirement SHALL be skipped.

#### Scenario: Missing TABLE_MODIFY

- **WHEN** a caller with `TABLE_CREATE` but not `TABLE_MODIFY` imports a bundle that creates a table
- **THEN** the response is `403` with `error` `forbidden`, and nothing is written

#### Scenario: Security mode none

- **WHEN** security mode is `none` and a caller with no claims imports a valid bundle
- **THEN** the import succeeds

### Requirement: Re-used pipeline name guard

Import SHALL mark a pipeline entry `reused_name: true` when `pipeline_aud` holds a delete revision for that name and the pipeline was
ever enabled under it (a pipeline that never ran has no runner state, so compensation's own deletes never trip it), and `/import` SHALL
refuse such a bundle with `422` `catalog_import_invalid` unless the query parameter `acknowledge_reused_names` is true.

The guard exists because the Enrichment Runner keys its state by pipeline name. It detects a recreated pipeline by its new
`created_at` (see [pipeline-registry](../pipeline-registry/spec.md)) and resets the scan cursor, group state, queued work and active
backfills it held under the name, but it keeps the deleted pipeline's dead-letter items and same-day spend counters. Those still
carry over: an operator requeue of an old dead letter replays the deleted pipeline's payload into the new target, and the old
pipeline's spend for the day counts toward the new one's daily limit. The acknowledgement accepts that.

#### Scenario: Refused without acknowledgement

- **WHEN** the target once had a pipeline `p` that was deleted, and a bundle creating `p` is imported without the acknowledgement
- **THEN** the response is `422` with `error` `catalog_import_invalid`, and the preview entry for `p` shows `reused_name: true`

#### Scenario: Accepted with acknowledgement

- **WHEN** the same import passes `acknowledge_reused_names=true`
- **THEN** `p` is created

### Requirement: Pipelines are imported disabled

Import SHALL create every pipeline disabled, whatever its `exported_enabled`. Arming SHALL remain a separate `PATCH enabled=true`,
which re-runs the arm-time validation; the import preview SHALL be recommended before arming and SHALL NOT be enforced.

#### Scenario: Enabled source pipeline lands disabled

- **WHEN** a pipeline exported with `exported_enabled: true` is imported
- **THEN** `GET /v1/pipelines/{name}` on the target reports `enabled: false`, and `PATCH enabled=true` then succeeds

### Requirement: Best-effort compensation

`/import` SHALL apply each object as its own committed step and, on the first failure, SHALL delete what this import created, in
reverse order (pipelines, then enrichments, then sources). Compensation is best effort, not a guarantee.

Before deleting a table, import SHALL check that any pipeline targeting it was created by this import; otherwise it SHALL leave the
table and mark it `rollback_failed`. A crash mid-import, or a ClickHouse drop that succeeds before the catalog delete fails, MAY leave
orphans; re-running the bundle with `SKIP_IF_EXISTS` SHALL recover a missing object, and SHALL refuse a left-behind PENDING or FAILED
table until it is deleted. Each created object SHALL be written in its own audit revisions (table create, schema) under the
importing user, and the import SHALL log one structured `catalog_import` event carrying an `import_id`, the bundle hash and the object
list. Every audit revision the import writes — creates and compensation deletes alike — SHALL be tagged with that same `import_id`,
which the activity feed returns as `importId` (see the `audit-trail` capability), so what an import did, including what a crash
mid-import left behind, is recoverable from the feed and not only from the log.

#### Scenario: DDL failure on the second table

- **WHEN** ClickHouse DDL fails while creating the second table of a bundle
- **THEN** the first table is gone from both the catalog and ClickHouse, the `ImportResult` marks it `rolled_back`, and re-running
  with `SKIP_IF_EXISTS` after the cause is removed creates both

#### Scenario: Import revisions carry the import id

- **WHEN** an import completes and returns `import_id` `X`
- **THEN** every activity recorded for the objects it created carries `importId` `X`

### Requirement: Environment-specific values reported

The import preview and result SHALL list under `env_specific` every environment-specific value in the bundle: pipeline
`transform.model`, `advanced.rate_rpm`, cron triggers, absolute date literals in filters and in an `aggregate` pipeline's `sql`, and
table `ttl`. Import SHALL NOT validate `transform.model` against the target. A date literal in `sql` SHALL be reported under the field
`sql`, found by the same literal scan as a filter's, so a statement's `WHERE request_time >= '2026-08-05'` is as visible to the operator
as the same predicate written as a filter.

#### Scenario: Model listed

- **WHEN** a bundle pipeline declares `transform.model: gemini-2.5-flash-lite`
- **THEN** `env_specific` holds an entry naming that pipeline, the field `transform.model` and the value

#### Scenario: Date literal in an SQL statement listed

- **WHEN** a bundle aggregate pipeline's `sql` carries `WHERE request_time >= '2026-08-05'`
- **THEN** `env_specific` holds an entry naming that pipeline, the field `sql` and the value `2026-08-05`

### Requirement: Upload size limit

The import endpoints SHALL refuse a multipart upload larger than the configured limits (`CATALOG_IMPORT_MAX_FILE_SIZE`,
`CATALOG_IMPORT_MAX_REQUEST_SIZE`) with `413` and `error` `payload_too_large`.

#### Scenario: Oversized bundle

- **WHEN** an uploaded `file` exceeds `CATALOG_IMPORT_MAX_FILE_SIZE`
- **THEN** the response is `413` with `error` `payload_too_large`

### Requirement: Post-import runbook

The project SHALL document the promotion runbook in `docs/catalog-promotion.md`: review `env_specific` (a model must exist on the
target, because nothing checks it at arm time); enable enrichment-producing pipelines before the aggregates that read them; preview and
then arm each pipeline; expect aggregates to rebuild from zero; run any data-sync backfill separately, since runner state does not
travel; and, when promoting access, review every access entry's roles and row-policy values in the import preview
and the export preview's `left_behind`, handle the bundle as access configuration, and remove a role on the target with
`DELETE /v1/tables/{name}/access?role=`, since an import never removes one.

#### Scenario: Runbook present

- **WHEN** the change ships
- **THEN** `docs/catalog-promotion.md` exists and covers each of the six steps

### Requirement: An SQL declaration's import dependencies are read from its statement

Import SHALL resolve an `aggregate` pipeline's dependencies in SQL mode from its `sql`: the input table its `FROM` names, plus every
enrichment it references as a `"<enrichment>.<column>"` token, alongside its `target` and any `inputs` it declares. These are the
tables the pipeline cannot be created or armed without, and they SHALL drive every rule that asks for a pipeline's dependencies exactly
as a form-mode declaration's do: a pipeline depending on a refused object is `FAIL` naming that dependency, a dependency on a system
table is checked as a required system table, and the enrichments it reads are created before it under the import order.

Without this an SQL-mode pipeline, which usually declares no `inputs` and carries its enrichment references inside one string, would
look to the planner as if it depended on its target alone, and would be planned `CREATE` over a refused source, failing at apply rather
than in the preview.

The enrichment references SHALL be found by the qualified-token scan the export closure uses, which needs no parse. The input table
SHALL be read from the statement where it can be; a statement that does not parse SHALL contribute its tokens only and SHALL NOT by
itself make the pipeline `FAIL`, because the pipeline imports disabled and its own arming is what asks for a readable statement.

A name the statement's `FROM` reads SHALL count as a dependency only when it names a table the bundle carries or lists (under `tables`
or `required_system_tables`) or a table that exists on the target environment. Any other `FROM` name, a CTE's own name for instance,
SHALL be left to the arm gate: it SHALL NOT make the pipeline `FAIL` at import, and the validation pass reports it through `armable`
and `arm_problems`.

Every name the statement contributes — a `FROM` table, a CTE name, an enrichment token — SHALL be compared lowercased, quoted or not,
because the SQL front end resolves names case-insensitively: `FROM "Dial_Usage_Log"` reads `dial_usage_log`, and
`"Usage_Sentiment.sentiment"` references `usage_sentiment`.

#### Scenario: A pipeline whose statement reads a refused source fails in preview

- **WHEN** `/import/preview` runs with a bundle whose table `usage_base` is refused and whose SQL-mode aggregate pipeline declares no
  `inputs` and reads `usage_base` in its statement
- **THEN** the pipeline entry is `FAIL` naming `usage_base` as its dependency

#### Scenario: An enrichment referenced only in the statement is a dependency

- **WHEN** a bundle's SQL-mode aggregate pipeline references `"usage_sentiment.sentiment"` only inside its `sql`, and `usage_sentiment`
  is refused
- **THEN** the pipeline entry is `FAIL` naming `usage_sentiment`

#### Scenario: A system input read by the statement is a required system table

- **WHEN** a bundle's SQL-mode aggregate pipeline reads `dial_usage_log` in its statement and declares no `inputs`, the bundle lists
  `dial_usage_log` under `required_system_tables`, and the target environment lacks `dial_usage_log`
- **THEN** the pipeline entry is `FAIL` naming `dial_usage_log`, as it is for a form-mode pipeline declaring it as `inputs`

#### Scenario: A quoted mixed-case name is matched case-insensitively

- **WHEN** a bundle's SQL-mode aggregate pipeline reads `FROM "Dial_Usage_Log"`, the bundle lists `dial_usage_log` under
  `required_system_tables`, and the target environment lacks `dial_usage_log`
- **THEN** the pipeline entry is `FAIL` naming `dial_usage_log`

#### Scenario: A FROM name the bundle and the target do not know is left to the arm gate

- **WHEN** a bundle's SQL-mode aggregate pipeline reads `usage_missing` in its `FROM`, the bundle neither carries nor lists
  `usage_missing`, and the target environment has no such table
- **THEN** the pipeline entry is not `FAIL` on account of `usage_missing`, and it reports `armable: false` with an `arm_problems`
  entry

#### Scenario: An unparseable statement does not fail the import by itself

- **WHEN** a bundle's SQL-mode aggregate pipeline carries an `sql` that does not parse, and every table its tokens reference is
  importable
- **THEN** the pipeline entry is `CREATE`, it is imported disabled with its `sql` verbatim, and its `armable` is `false`

### Requirement: Instance settings do not travel

A bundle SHALL NOT carry instance settings (see [instance-settings](../instance-settings/spec.md)): neither
the `app-routes` allowlist nor `sessions-source`. They configure the instance a bundle is imported into, not
a table or a pipeline. The import validation pass SHALL arm each imported pipeline against the target's own
settings, so a `dial_app_route` pipeline whose `(model, route)` pair the target's `app-routes` setting does
not list SHALL be reported `armable: false`, with an `arm_problems` entry naming `app-routes`. The import
SHALL still create that pipeline, disabled like every other. Allowing the pair on the target makes it
armable without a new import.

#### Scenario: An imported route pipeline whose pair is not allowed is not armable

- **GIVEN** a bundle holding a `dial_app_route` pipeline declaring `(insights-app, insights)`, and a target
  whose `app-routes` setting does not list that pair
- **WHEN** `/import/preview` runs the bundle
- **THEN** the pipeline entry reports `import_action` `CREATE`, `armable: false` and an `arm_problems` entry
  naming `app-routes`

#### Scenario: Allowing the pair on the target makes it armable

- **GIVEN** the same bundle, after `PUT /v1/settings/app-routes` lists `(insights-app, insights)` on the target
- **WHEN** `/import/preview` runs the bundle again
- **THEN** the pipeline entry reports `armable: true`

### Requirement: Required system tables are checked, their definitions never written

Import SHALL check each required system table by name against the target: the table SHALL exist, be system-flagged and ACTIVE, and the
entry SHALL be `SKIP` when it is and `FAIL` under either policy when it is not. Its entry SHALL carry `name`, `import_action` and
`problems` only.

Import SHALL NOT compare a required system table's columns: a pipeline that reads a column the target lacks SHALL be reported through
the validation pass's `armable: false` and `arm_problems`. Import SHALL NOT write a system table's definition. Without
`include_access=true` import SHALL NOT write, compare or report grants on a system table; with it, the system table's access is
imported as "Access import" defines, and an access entry on a required system table whose own entry is `FAIL` SHALL be `FAIL` naming it.

#### Scenario: System-table grants untouched

- **WHEN** a bundle that requires `dial_usage_log` is imported with `SKIP_IF_EXISTS` and without `include_access`
- **THEN** the `dial_usage_log` entry is `SKIP`, and the target's `dial_usage_log` grants are byte-identical before and after

#### Scenario: Missing system table

- **WHEN** the bundle requires a system table the target does not have
- **THEN** that entry is `FAIL` with a problem saying it does not exist, and `/import` is `422` with `error` `catalog_import_invalid`

#### Scenario: Missing column surfaces at arm time

- **WHEN** a bundle pipeline reads a `dial_usage_log` column the target's `dial_usage_log` lacks
- **THEN** the `dial_usage_log` entry is `SKIP`, and the pipeline entry reports `armable: false` with an `arm_problems` entry naming
  the column

### Requirement: Import order and access

Import SHALL apply objects in dependency order: source tables, then enrichment tables, then `enrich` pipelines, then `aggregate`
pipelines, and then, when called with `include_access=true`, access (see "Access import"). Without `include_access` import SHALL write
no access: every table it creates has no access entry, so until an operator grants access with
`PUT /v1/tables/{name}/access?role=<role>` only callers holding the global privileges can see or read a created source. A created
enrichment has no read-side access of its own: it is readable at once by every caller that may read its source, which may be a table
the target already had and had granted, subject to that source's column policies (see
[table-access-control](../table-access-control/spec.md)).

#### Scenario: Imported table has no grants

- **WHEN** a table that has access entries on the source is imported without `include_access`, whether or not it was exported with it
- **THEN** `GET /v1/tables/{name}/access` on the target returns an empty `roles` object

#### Scenario: An imported enrichment of an existing source follows its grants

- **GIVEN** a target on which `analytics-viewers`' entry on `dial_usage_log` carries `read` with no column policy
- **WHEN** a bundle creating an enrichment of `dial_usage_log` is imported
- **THEN** a caller carrying only `analytics-viewers` reads the new enrichment's non-sensitive columns at once, and
  `GET /v1/tables/{name}/access` on the enrichment returns an empty `roles` object

### Requirement: Access export

With `include_access` true, the bundle SHALL carry a top-level `access` object: one member per covered table, keyed by table name, each
an object with one member per role key (a raw provider role or `*`), holding that role's entry `{ "privileges": [...], "rows": ..., "columns":
... }` in the shape `GET /v1/tables/{name}/access` returns it: `privileges` de-duplicated in the order `get`, `read`, `sensitive`,
`write`, `modify`, `rows` as `{ "filter": <filter node> }` or `null`, and `columns` as a sorted list or `null`; `rows` and `columns`
SHALL always be present, as `null` when the role has none. Tables and roles SHALL be sorted by name. The member SHALL be present,
possibly `{}`, whenever `include_access` was true, and absent otherwise.

A **candidate** table SHALL be every table under `tables`, and every system table that the selection names, that the closure of the objects
the bundle keeps reaches (for example, the input of a selected pipeline) or, when exporting everything, that the caller may get.

A column policy names an enrichment's column as `<enrichment>.<column>`, and that enrichment's source is always the entry's table.
Every enrichment that a candidate's entry names this way, once steps 1 to 3 below have kept that entry, SHALL join the closure with
the reason `column policy of <table>`, and SHALL pull in what it pulls in as any other closure table: its `source_table`, the pipeline
that targets it and, through that pipeline, its inputs. This SHALL apply to system-table candidates too, which are otherwise never
expanded. The edge SHALL NOT make the candidate need the enrichment: when exporting everything skips the enrichment, the candidate and
every other object stay, and only the entry is left behind (step 4). Such an enrichment is gettable whenever its source is, and it
meets every other rule a table the closure reaches meets: under a selection, a PENDING or FAILED one SHALL make the export `422` with
`error` `catalog_export_invalid`, and one with a column the caller may not see SHALL refuse the export as "Export authorization"
defines. A system enrichment named this way SHALL be listed under `required_system_tables`, as every system table the closure reaches
is, and never exported; it stays listed even when step 4 later leaves the entry behind. An entry that steps 1 to 3 leave behind pulls
nothing in. Every `<enrichment>.<column>` a carried entry names is therefore an enrichment under `tables` or under
`required_system_tables`, so an import into a target that has the same system tables and none of the bundle's tables meets no missing
enrichment.

The bundle SHALL carry only what a `PUT /v1/tables/{name}/access?role=` on the exporting ADAS would accept now. Each stored entry of a
candidate SHALL be reduced in this order, and every part removed SHALL be **left behind**:

1. an enrichment's entry keeps only `write` and `modify`, leaving behind `get`, `read`, `sensitive`, `rows` and `columns`;
2. a `*` entry, and every entry of a system table, leaves behind `write` and `modify`;
3. a non-empty entry that the body rules of a `PUT` (see [table-access-control](../table-access-control/spec.md)) still refuse, such as
   one stored for a role that is now a bundle or privilege name, has what remains of it left behind;
4. an entry whose column policy names `<enrichment>.<column>` for an enrichment listed under the bundle's `skipped` has what remains of
   it left behind. Only exporting everything skips objects, and steps 1 to 3 depend only on the entry and the exporting ADAS's catalog,
   so this is the one step that needs the closure, and it runs once the closure is known;
5. an entry left with no privilege and no policy is omitted.

A **covered** table SHALL be a candidate that has at least one entry after this reduction. The bundle SHALL carry the portable entries
of every covered table and nothing for any other candidate. A system enrichment is never covered, since a `PUT` refuses both its read side and its `write` and `modify`. A
system table SHALL contribute only its access, never its definition, and SHALL be listed under `required_system_tables` whenever it is
covered, named by the selection or reached by the closure. In the export preview, a system table under `required_system_tables` that
is there only because exporting everything covers it SHALL carry the reason `access`.

With `include_access` true, the export preview SHALL also return `access`: one item per candidate that is covered or has something left
behind, sorted by table, each `{ table, roles: [<role>...], left_behind: [ { role, entry, error } ] }`. `roles` lists, sorted, the roles
the bundle carries for the table. `left_behind` holds, sorted by role and then in step order, at most one item per role and step. In
`entry` it gives, in entry shape with `rows` and `columns` always present, the part of that role's stored entry the step removed. In
`error` it gives `dependency_skipped` for step 4, and otherwise the code the `PUT`'s checks of the body give that part, in the `PUT`'s
order and with no generation, equal-entry or conflict check:
for example `enrichment_follows_source` for an enrichment's read side, `table_is_system` for `write` or `modify` on a system table, and
`validation_error` for a `*` entry's `write` or `modify` on another table or for a refused role. Without `include_access` the preview
SHALL carry no `access` member. Only the export preview reports what was left behind: neither the bundle nor the import mentions it.

#### Scenario: Access travels with a table

- **GIVEN** a table `t` on which `analytics-viewers` carries `read` with a row policy and `finance-owners` carries `read` and
  `sensitive` with `columns` `["a", "b"]`
- **WHEN** a caller holding `REGISTRY_READ`, `get` on `t` and `TABLE_GRANT` exports `t` with `include_access: true`
- **THEN** `access.t` holds both entries exactly as `GET /v1/tables/t/access` returns them, and `t` is under `tables` as without access

#### Scenario: Exporting everything covers system tables

- **GIVEN** `dial_usage_log` carries an entry for `analytics-viewers`, and no bundle table or pipeline depends on it
- **WHEN** a caller holding `REGISTRY_READ`, `get` on `dial_usage_log` and `TABLE_GRANT` exports everything with
  `include_access: true`
- **THEN** `required_system_tables` contains `dial_usage_log`, `access.dial_usage_log` holds the entry, `dial_usage_log` is not under
  `tables`, and the export preview gives `dial_usage_log` the reason `access`

#### Scenario: A system table selected for its access alone

- **GIVEN** no entry on `dial_usage_log` names an enrichment's column
- **WHEN** the body selects only `{ "type": "table", "name": "dial_usage_log" }` with `include_access: true`
- **THEN** the response is `200`, `tables` and `pipelines` are empty, `required_system_tables` is `["dial_usage_log"]`, and `access`
  holds `dial_usage_log`'s entries

#### Scenario: A system table the closure reaches carries its access

- **GIVEN** a pipeline `p` that reads `dial_usage_log`, which carries an entry for `analytics-viewers`
- **WHEN** only `p` is exported with `include_access: true`
- **THEN** `required_system_tables` contains `dial_usage_log` and `access.dial_usage_log` holds the entry

#### Scenario: A column policy pulls in its enrichment

- **GIVEN** `analytics-viewers`' entry on `dial_usage_log` carries `columns` `["model", "usage_sentiment.label"]`, where
  `usage_sentiment` is an enrichment of `dial_usage_log` that the `enrich` pipeline `sentiment_enrich` targets
- **WHEN** only `dial_usage_log` is exported with `include_access: true`
- **THEN** `tables` holds `usage_sentiment`, `pipelines` holds `sentiment_enrich`, the export preview gives `usage_sentiment` the
  reason `column policy of dial_usage_log`, and `access.dial_usage_log.analytics-viewers` carries the `columns` unchanged
- **AND WHEN** `usage_sentiment` is FAILED
- **THEN** the response is `422` with `error` `catalog_export_invalid` naming `usage_sentiment`

#### Scenario: A fresh export imports cleanly

- **GIVEN** the bundle of "A column policy pulls in its enrichment"
- **WHEN** it is imported with `include_access=true` into a target that has `dial_usage_log` and no `usage_sentiment`
- **THEN** `outcome` is `completed`, `usage_sentiment` is created before the access is written, and
  `GET /v1/tables/dial_usage_log/access` on the target returns the `analytics-viewers` entry as the bundle holds it

#### Scenario: A left-behind entry pulls nothing in

- **GIVEN** a table `t` that stores, for the bundle name `READ_ONLY_ANALYTIC`, an entry written by an earlier build whose `columns`
  name `e.label`, `e` being an enrichment of `t`
- **WHEN** only `t` is exported with `include_access: true`
- **THEN** `e` is not exported, and the export preview lists `READ_ONLY_ANALYTIC` under `left_behind`

#### Scenario: An entry naming a skipped enrichment stays behind

- **GIVEN** `analytics-viewers`' entry on `dial_usage_log` carries `columns` `["model", "usage_sentiment.label"]`, and
  `usage_sentiment` is FAILED
- **WHEN** everything is exported with `include_access: true`
- **THEN** `usage_sentiment` is listed under `skipped`, `access.dial_usage_log` carries no `analytics-viewers` entry, and the export
  preview lists `analytics-viewers` under `left_behind` with `error` `dependency_skipped`

#### Scenario: A skipped enrichment does not drop the table naming it

- **GIVEN** a user table `t` whose entry for `analytics-viewers` carries `columns` `["a", "e.label"]`, `e` being a FAILED enrichment
  of `t`
- **WHEN** everything is exported with `include_access: true`
- **THEN** `t` is under `tables`, `e` is listed under `skipped` with `reason` `failed`, `access.t` carries no `analytics-viewers`
  entry, and the export preview lists `analytics-viewers` under `left_behind` with `error` `dependency_skipped`

#### Scenario: A column policy naming a system enrichment

- **GIVEN** `analytics-viewers`' entry on `dial_usage_log` names a column of `dial_usage_log_payload`
- **WHEN** only `dial_usage_log` is exported with `include_access: true`
- **THEN** `required_system_tables` is `["dial_usage_log", "dial_usage_log_payload"]`, the export preview gives
  `dial_usage_log_payload` the reason `column policy of dial_usage_log`, `tables` is empty, and `access.dial_usage_log` carries the
  entry

#### Scenario: A selected system enrichment carries no access

- **WHEN** the body selects only `{ "type": "table", "name": "dial_usage_log_payload" }` with `include_access: true`
- **THEN** `required_system_tables` is `["dial_usage_log_payload"]`, `dial_usage_log` is neither required nor covered, and `access` is
  `{}`

#### Scenario: Leftover state stays behind

- **GIVEN** an enrichment `e` that stores, for role `X`, `write` together with a leftover `read` and row policy written by an earlier
  build
- **WHEN** `e` is exported with `include_access: true`
- **THEN** `access.e.X` is `{ "privileges": ["write"], "rows": null, "columns": null }`, and the export preview lists, for `e`, a
  `left_behind` item for `X` whose `entry` carries `read` and the row policy and whose `error` is `enrichment_follows_source`

#### Scenario: A write for everyone stays behind

- **GIVEN** a user table `t` whose `*` entry stores `read` and `write`, written by an earlier build
- **WHEN** `t` is exported with `include_access: true`
- **THEN** `access.t."*"` carries only `read`, and the export preview lists `*` under `left_behind` with `write` and `error`
  `validation_error`

#### Scenario: A system table's write stays behind

- **GIVEN** `dial_usage_log` stores, for role `X`, `read` together with a `write` written by an earlier build
- **WHEN** everything is exported with `include_access: true`
- **THEN** `access.dial_usage_log.X` carries only `read`, and the export preview lists `X` under `left_behind` with `write` and
  `error` `table_is_system`

#### Scenario: Security mode none exports access

- **WHEN** security mode is `none` and a caller with no claims exports everything with `include_access: true`
- **THEN** the response is `200` and the bundle carries `access`

#### Scenario: A role a PUT refuses stays behind whole

- **GIVEN** a table `t` that stores an entry for `READ_ONLY_ANALYTIC`, a bundle name, written by an earlier build, and one for
  `analytics-viewers`
- **WHEN** `t` is exported with `include_access: true`
- **THEN** `access.t` holds only `analytics-viewers`, and the export preview lists `READ_ONLY_ANALYTIC` under `left_behind` with its
  whole entry and `error` `validation_error`

#### Scenario: An export with nothing to carry still says so

- **WHEN** an export with `include_access: true` covers no table
- **THEN** the bundle carries `access: {}`

### Requirement: Access import

Both import calls SHALL accept the optional boolean query parameter `include_access`, defaulting to `false`. Whatever its value, the
bundle's `access` member SHALL be bound strictly in stage 2: a `null` table or role value under it SHALL be `400`, like any constraint
violation, and an entry SHALL bind as a `PUT` body without `generation`, so `rows` takes `filter` or `where`. A top-level
`"access": null` SHALL count as no `access` member. When the flag is false,
the bound `access` member SHALL be ignored: no access entry is written on any table and the response's `access` list is empty. When it
is true:

- **Names.** Role keys SHALL be trimmed as a `PUT` trims its role, and table keys SHALL match the bundle's table names exactly.
- **Bundle-level problems.** A bundle with no `access` member, an `access` key naming a table that is neither under `tables` nor under
  `required_system_tables`, a role key that is blank once trimmed, and two role keys of one table that trim to the same role SHALL
  each be reported under `validation_errors`, and `/import` SHALL answer `422` with `error` `catalog_import_invalid`. Every access
  entry under such a problem SHALL be `FAIL` with a problem naming it, so none of them is checked or applied.
- **Upsert.** Each role entry SHALL replace that role's entry on the target table; every role the bundle does not name on that table
  SHALL keep its entry unchanged. Role names SHALL be copied as they are, with no mapping. The upsert SHALL apply to tables this import
  creates, to existing user tables kept under `SKIP_IF_EXISTS` and to required system tables alike. An import SHALL never remove an
  entry: a bundle entry with no privilege and no policy SHALL be `FAIL` with `validation_error`, naming
  `DELETE /v1/tables/{name}/access?role=` as the way to remove one.
- **Validation.** The shared validation pass SHALL check each entry with the rules a `PUT /v1/tables/{name}/access?role=` applies to its
  body, in the `PUT`'s order (see [table-access-control](../table-access-control/spec.md)), against the target table as it stands once
  the import's tables exist, and without a client `generation`. An entry a `PUT` would refuse SHALL be `FAIL` with a problem that starts with
  the `PUT`'s `error` code (`<code>: <message>`). An entry on a table whose own entry is refused, at planning or in the validation pass, SHALL be `FAIL`
  naming it, except that a `FAIL_IF_EXISTS` conflict does not refuse it, as for every other dependent. When the pass stops before an entry is checked, that entry SHALL be `FAIL`
  with a problem saying it was not checked. The pass SHALL check every entry before it applies any. When applying the
  access inside the pass still fails, for example on a lock timeout, every access entry SHALL be `FAIL` with a problem naming the
  failure, and every pipeline SHALL carry the `not checked` arm problem instead of being armed, since the access may be partly
  applied. Any `FAIL` SHALL make `/import` answer `422` with `error` `catalog_import_invalid`.
- **Equality.** An entry SHALL count as equal to the target's when the entry a `PUT` of it would store equals the stored one, so the
  order of `privileges` and `columns` does not matter.
- **Apply.** `/import` SHALL write every access entry in one transaction, after every table and pipeline is created. A failure there SHALL run the compensation of "Best-effort compensation", so either every access entry is
  written or none is, and the access of existing tables is then as it was. `/import` SHALL then answer `200` with that `ImportResult`,
  as when an object fails during apply.
- **Generation and audit.** Each entry written SHALL move the table's access `generation` on by one and set the role's last-change mark,
  as a `PUT` does, and an equal entry SHALL write nothing and move neither. The access step SHALL be audited as at most one revision, none when no entry
  changes, tagged with the import's `import_id`, so the activity feed records one `Update` per changed table, however many of its roles changed, each carrying that
  `importId`.
- **Authorization.** Both calls SHALL require global `TABLE_GRANT`, checked after the programmatic gates of "Import authorization"
  and so before the collision check. A caller without it SHALL receive `403` with `error` `forbidden`, and nothing SHALL be written.
  `TABLE_CREATE` at the endpoint SHALL still be required, even for a bundle that carries only access. When security is not enforced,
  this check SHALL be skipped.

#### Scenario: Access lands on a created table

- **GIVEN** a bundle holding table `t` and an entry for `analytics-viewers` on it
- **WHEN** it is imported into a target without `t`, with `include_access=true`
- **THEN** `t` is created and `GET /v1/tables/t/access` on the target returns the `analytics-viewers` entry as the bundle holds it

#### Scenario: Roles the bundle does not name are kept

- **GIVEN** a target whose `dial_usage_log` carries entries for `ops-readers` and `analytics-viewers`, and a bundle whose
  `access.dial_usage_log` holds only `analytics-viewers` with a different entry
- **WHEN** the bundle is imported with `include_access=true`
- **THEN** `analytics-viewers`' entry is the bundle's, and `ops-readers`' entry is unchanged

#### Scenario: An empty entry does not remove a role

- **GIVEN** a target whose `t` carries an entry for `analytics-viewers`, and a bundle holding `t` under `tables` whose
  `access.t.analytics-viewers` is `{ "privileges": [] }`
- **WHEN** it is imported with `resolution_policy=SKIP_IF_EXISTS` and `include_access=true`
- **THEN** that access entry is `FAIL` with `validation_error`, the response is `422` with `error` `catalog_import_invalid`, and
  `analytics-viewers`' entry on the target is unchanged

#### Scenario: Two keys naming one role

- **WHEN** a bundle whose `access.t` has the keys `"analytics-viewers"` and `" analytics-viewers"` is imported with
  `include_access=true`
- **THEN** `/import/preview` reports it under `validation_errors`, and `/import` is `422` with `error` `catalog_import_invalid`

#### Scenario: A blank role key

- **WHEN** a bundle whose `access.t` has the key `" "` is imported with `include_access=true`
- **THEN** `/import/preview` reports it under `validation_errors`, and `/import` is `422` with `error` `catalog_import_invalid`

#### Scenario: Access for a table the bundle does not carry

- **WHEN** a bundle whose `access` has a key `u` that is neither under `tables` nor under `required_system_tables` is imported with
  `include_access=true`
- **THEN** `/import/preview` reports it under `validation_errors`, and `/import` is `422` with `error` `catalog_import_invalid`

#### Scenario: A null access value is refused at binding

- **WHEN** a bundle whose `access.t` is `null`, or whose `access.t.analytics-viewers` is `null`, is uploaded to either import call,
  with or without `include_access`
- **THEN** the response is `400`

#### Scenario: An access-only bundle still needs TABLE_CREATE

- **WHEN** a caller holding `TABLE_GRANT` but not `TABLE_CREATE` imports, with `include_access=true`, a bundle that carries only access
  for `dial_usage_log`
- **THEN** the response is `403` with `error` `forbidden`, and nothing is written

#### Scenario: Access lands on a skipped existing table

- **GIVEN** a target that already holds the bundle's table `t` with no entries
- **WHEN** the bundle is imported with `resolution_policy=SKIP_IF_EXISTS` and `include_access=true`
- **THEN** the `t` table entry is `SKIP`, its definition is unchanged, and its access holds the bundle's entries

#### Scenario: Re-running the import writes no access

- **WHEN** a bundle already imported with `resolution_policy=SKIP_IF_EXISTS` and `include_access=true` is imported again the same way
- **THEN** every access entry is `SKIP`, and every covered table's access `generation` is unchanged

#### Scenario: Access revisions carry the import id

- **WHEN** an import with `include_access=true` that replaces an entry on the existing `dial_usage_log` completes with `import_id` `X`
- **THEN** exactly one `Update` of `dial_usage_log` records that change, and it carries `importId` `X`

#### Scenario: Access ignored without the flag

- **WHEN** a bundle carrying `access` is imported without `include_access`
- **THEN** no access entry is written on any table, and the response's `access` list is empty

#### Scenario: The flag on a bundle without access

- **WHEN** a bundle exported without `include_access` is imported with `include_access=true`
- **THEN** `/import/preview` reports it under `validation_errors`, and `/import` is `422` with `error` `catalog_import_invalid`

#### Scenario: A null access member counts as absent

- **WHEN** a bundle whose top level carries `"access": null` is imported with `include_access=true`
- **THEN** the response is `422` with `error` `catalog_import_invalid` and a validation error saying the bundle has no `access` member,
  not `400`
- **AND WHEN** it is imported without `include_access`
- **THEN** it imports as a bundle without access

#### Scenario: An entry the target table cannot take

- **GIVEN** a bundle whose entry for `analytics-viewers` on `t` carries `columns` `["a", "z"]`, and a target `t` that has no column `z`
- **WHEN** it is imported with `resolution_policy=SKIP_IF_EXISTS` and `include_access=true`
- **THEN** that access entry is `FAIL` with a problem naming `z`, the response is `422` with `error` `catalog_import_invalid`, and
  nothing is written

#### Scenario: A column policy naming an enrichment the target lacks

- **GIVEN** a bundle whose entry for `analytics-viewers` on `dial_usage_log` carries `columns` `["model", "usage_sentiment.label"]`,
  holding no `usage_sentiment` (an export never writes such a bundle, but a bundle can be edited), and a target without
  `usage_sentiment`
- **WHEN** it is imported with `include_access=true`
- **THEN** that access entry is `FAIL` with a problem naming `usage_sentiment.label`, and `/import` is `422` with `error`
  `catalog_import_invalid`

#### Scenario: Access on a refused table fails with it

- **WHEN** a bundle table `t` is refused by the validation pass, and the bundle carries access for `t`, under `include_access=true`
- **THEN** every access entry on `t` is `FAIL` naming `t`, and no access entry is applied in the pass

#### Scenario: A conflicting table's access is not refused in preview

- **WHEN** `/import/preview` runs with `resolution_policy=FAIL_IF_EXISTS` and `include_access=true` on a bundle holding a table `t` that
  exists on the target, with access for `t`
- **THEN** the `t` table entry is `FAIL` as a conflict, and the access entries on `t` are `CREATE`, `REPLACE` or `SKIP`, not `FAIL`

#### Scenario: Access on a missing system table fails

- **WHEN** a bundle carrying access for a required system table the target does not have is previewed with `include_access=true`
- **THEN** that table's entry is `FAIL`, and every access entry on it is `FAIL` naming it

#### Scenario: Equality ignores order

- **GIVEN** a target `t` on which `analytics-viewers` stores `privileges` `["get", "read"]` and `columns` `["a", "b"]`
- **WHEN** a bundle whose entry for it carries `["read", "get"]` and `["b", "a"]` is previewed with `include_access=true`
- **THEN** that access entry is `SKIP`

#### Scenario: Importing access needs TABLE_GRANT

- **WHEN** a caller holding every privilege the bundle's objects need, but not `TABLE_GRANT`, imports with `include_access=true`
- **THEN** the response is `403` with `error` `forbidden`, and nothing is written

#### Scenario: The TABLE_GRANT check precedes the collision check

- **WHEN** a caller without `TABLE_GRANT` imports, with `resolution_policy=FAIL_IF_EXISTS` and `include_access=true`, a bundle holding
  a table that already exists on the target
- **THEN** the response is `403` with `error` `forbidden`, not `409`

#### Scenario: Access lands whole or not at all

- **GIVEN** a bundle that creates table `t` and carries access for `t` and for `dial_usage_log`
- **WHEN** writing the access fails during `/import` with `include_access=true`
- **THEN** the response is `200`, `t` is removed by compensation, the target's `dial_usage_log` access is as it was, and `outcome`
  is `rolled_back`

#### Scenario: The generation moves as a PUT moves it

- **GIVEN** a target `dial_usage_log` at access `generation` `7`
- **WHEN** an import with `include_access=true` replaces one role's entry on it and creates another's
- **THEN** its `generation` is `9`, and a `PUT` of a different entry for either role carrying `generation` `7` is refused `409` with
  `error` `access_conflict`

#### Scenario: Security mode none imports access

- **WHEN** security mode is `none` and a caller with no claims imports a valid bundle with `include_access=true`
- **THEN** the import succeeds and the bundle's access entries are written

### Requirement: Access import entries

`ImportPreview` and `ImportResult` SHALL carry an `access` list: one entry per table and role in the bundle's `access`, sorted by table
then by trimmed role and then by the role key as written, each `{ table, role, import_action, prev, next, problems[],
ineffective_grants[] }`. The list SHALL be empty
when `include_access` is false.

- `import_action` SHALL be `CREATE` when the target table has no entry for the role (a table this import creates included), `REPLACE`
  when it has a different one, `SKIP` when it has an equal one, and `FAIL` when the entry is refused. `REPLACE` SHALL be used only by
  access entries.
- `role` SHALL be the trimmed role. `prev` SHALL be the target's entry for the role, or `null` when it has none; `next` SHALL be the
  bundle's entry as a `PUT` of it would store it (a `where` given as its `filter`), or the entry as bound from the bundle when it is `FAIL`. Both
  SHALL be in entry shape, with `rows` and `columns` always present, except that a `FAIL` entry's `next.rows` carries `where` when
  the bundle gave one.
- `ineffective_grants` SHALL be the report a `PUT` of that entry would return (see
  [table-access-control](../table-access-control/spec.md), "Ineffective grants are reported on read"), judged on the target's entries
  as the validation pass leaves them, with every access entry of the bundle applied, and empty when none is applied (see "Shared
  validation pass"). `ImportResult` SHALL carry the report the validation pass computed.
- Each role key SHALL give its own entry, so two keys that trim to the same role give two entries as well as the validation error.
- `ImportResult` entries SHALL add `status`: `created`, `replaced` or `skipped` for an entry written or left as it was. When the import
  stopped at an earlier table or pipeline, every access entry SHALL be `skipped` with a problem naming where it stopped. When the access
  step itself failed, every access entry SHALL be `rolled_back` with a problem naming the failure.

#### Scenario: Preview shows what access would change

- **GIVEN** a target table `t` on which `analytics-viewers` carries `read`, and a bundle holding `t` under `tables` whose `access.t` gives
  `analytics-viewers` `read` with a row policy and `finance-owners` `read`
- **WHEN** `/import/preview` runs with `resolution_policy=SKIP_IF_EXISTS` and `include_access=true`
- **THEN** the `access` list holds `t`/`analytics-viewers` with `import_action` `REPLACE` and `prev` the target's entry, and
  `t`/`finance-owners` with `import_action` `CREATE` and `prev` `null`, and nothing is written

#### Scenario: A where is reported as its filter

- **WHEN** `/import/preview` runs with `include_access=true` on a bundle whose entry for `analytics-viewers` on `t` gives `rows` as
  `{ "where": "project = 'p1'" }`
- **THEN** that access entry's `next.rows` is the equivalent `{ "filter": ... }`

#### Scenario: Preview reports an ineffective grant

- **WHEN** `/import/preview` runs with `include_access=true` on a bundle whose `access.t."*"` carries `read` with no row policy, `t` not
  being an enrichment
- **THEN** that access entry's `ineffective_grants` holds the reason `everyone_reads_all_rows`

#### Scenario: Result reports what access was written

- **WHEN** `/import` with `include_access=true` completes after replacing one entry and leaving an equal one
- **THEN** the replaced entry has `status` `replaced`, the equal one `status` `skipped`, and `outcome` is `completed`

#### Scenario: Access is not attempted after an earlier failure

- **WHEN** `/import` with `include_access=true` fails while creating a table
- **THEN** every access entry has `status` `skipped` with a problem naming that table, and no access entry is written

#### Scenario: A failed access step rolls every entry back

- **WHEN** `/import` with `include_access=true` fails while writing the access
- **THEN** every access entry has `status` `rolled_back` with a problem naming the failure

