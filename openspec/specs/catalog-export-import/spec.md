# catalog-export-import Specification

## Purpose

This capability defines catalog promotion between ADAS environments: `POST /v1/catalog/export` writes a versioned
bundle of user tables, their columns, and pipelines with their dependency closure, and
`POST /v1/catalog/import` validates such a bundle and recreates it on another environment. Each call has a
preview twin, and the API shape mirrors deployment manager's config export/import. Import never changes an
existing object: system tables travel by name as required system tables that are checked, never written, and pipelines
always land disabled. Both import calls share one validation pass, which runs the real create services in a
rolled-back transaction. The surrounding operator sequence is in `docs/catalog-promotion.md`.

## Requirements

### Requirement: Catalog export endpoint

`POST /v1/catalog/export` SHALL return a catalog bundle of the components named in its `ExportRequest` body, as `application/json`
with the header `Content-Disposition: attachment; filename="adas-catalog-<timestamp>.json"`.

The body SHALL be `{ "components": [ { "type": "table" | "pipeline", "name": "<name>" } ] }`. An empty or absent `components` list
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
and measure expressions, or inside an `aggregate` pipeline's `sql` statement.

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

### Requirement: Export preview

`POST /v1/catalog/export/preview` SHALL take the same body as export and SHALL return the resolved object list without the bundle, and
SHALL write nothing.

Each entry SHALL carry `type`, `name`, `description` and `reason`. `reason` SHALL be `selected`, or the dependency that pulled the
object in (for example `source_table of usage_sentiment`). The response SHALL also list the `required_system_tables` the bundle would
require and the `skipped` objects. Selection errors SHALL be the same as export's.

#### Scenario: Preview shows why a dependency was added

- **WHEN** the export preview selects only enrichment `usage_sentiment`, whose source is `usage_base`
- **THEN** the response lists `usage_sentiment` with `reason` `selected` and `usage_base` with `reason`
  `source_table of usage_sentiment`

### Requirement: Excluded and system tables on export

The export SHALL never write a system table (`TableDefinitionEntity.system`) as a table; it SHALL write the name of each system table
the closure reaches under `required_system_tables`, a list of names with no columns and no grants. An explicit selection that names a
system table SHALL be `422` with `error` `catalog_export_invalid`: a system table is required, never exported.

OTLP landing tables (`otel_<sink>_<signal>`) SHALL be excluded. PENDING and FAILED tables SHALL be excluded. When exporting
everything, each excluded table SHALL be listed under `skipped` with `reason` `otlp`, `pending` or `failed`, a pipeline whose
declaration no longer resolves SHALL be listed with `reason` `unresolvable` (and named in a `422` on explicit selection), and every object that needs
one (an enrichment of it, a pipeline reading or targeting it) SHALL be excluded too and listed with `reason` `dependency_skipped`, or
`otlp` for a pipeline that reads an OTLP landing table. An explicit selection that names or whose closure reaches an OTLP landing table,
a pipeline that reads one, or a PENDING or FAILED table SHALL be `422` with `error` `catalog_export_invalid`.

#### Scenario: System table becomes a required system table

- **WHEN** the closure reaches an enrichment of `dial_usage_log`
- **THEN** `required_system_tables` is `["dial_usage_log"]`, and `dial_usage_log` is not under `tables`

#### Scenario: Explicit OTLP selection refused

- **WHEN** the body selects the table `otel_app_logs`
- **THEN** the response is `422` with `error` `catalog_export_invalid`

#### Scenario: Pending table skipped

- **WHEN** an unselected-everything export runs while a table is PENDING
- **THEN** that table is absent from `tables` and listed under `skipped` with `reason` `pending`

### Requirement: Export authorization

Export SHALL require `REGISTRY_READ` at the endpoint, and SHALL check each selected table with the table-access evaluator for
`TABLE_GET`.

A selected table, or one its closure reaches, that the caller may not get SHALL be `404` with `error` `not_found`; with no selection
such tables, and every object that needs one, SHALL be silently left out. A bundle table with a sensitive column the caller may not
see SHALL refuse the export with `403` and `error` `sensitive_column_not_entitled`, whether it was selected, reached by the closure or
exported as part of everything; columns are never silently dropped. The bundle SHALL NOT carry grants: no table's `access` lists
are exported, whatever the caller holds, because grants are each environment's own policy. When security is not enforced, every
check in this requirement SHALL be skipped.

#### Scenario: Sensitive column the caller cannot see

- **WHEN** a caller with `REGISTRY_READ` and `TABLE_GET` on `t`, but no sensitive clearance on it, exports `t`, which has a sensitive column
- **THEN** the response is `403` with `error` `sensitive_column_not_entitled`

#### Scenario: Table the caller cannot list

- **WHEN** the caller selects a table it may not get
- **THEN** the response is `404` with `error` `not_found`

#### Scenario: Grants never exported

- **WHEN** a caller holding `TABLE_GRANT` exports a table that has grants
- **THEN** the bundle entry carries no `access`

#### Scenario: Security mode none

- **WHEN** security mode is `none` and a caller with no claims exports everything
- **THEN** the response is `200`

### Requirement: Bundle format and version compatibility

The bundle SHALL carry `format: "adas-catalog"`, an integer `format_version` (currently `2`), `exported_at`, `skipped`,
`required_system_tables`, `tables` (each `{ table, schema }` in request shape) and `pipelines` (each
`{ pipeline, exported_enabled }`).

The bundle SHALL carry no provenance of the exporting ADAS beyond `exported_at`. A bundle SHALL import into the ADAS version that
exported it or any newer one. `format_version` SHALL be raised whenever the shape of any bundle member changes, including a new enum value.

Version `2` adds the `aggregate` declaration's `sql` member. An export SHALL always write the current version, whether or not any
pipeline it carries uses `sql`, so a bundle never claims a version by its content. An ADAS that reads only version `1` therefore refuses
every bundle a version-`2` ADAS writes with the readable `catalog_bundle_version_unsupported` refusal below, rather than the strict
binding's unknown-field `400` that a bundle carrying `sql` under version `1` would meet. A version-`1` bundle SHALL still import into
a version-`2` ADAS unchanged: it carries no `sql`, and every pipeline in it is in form mode.

Import SHALL parse in two stages. Stage 1 SHALL read only `format` and `format_version`, leniently: a wrong or missing `format` SHALL
be `400`, and a `format_version` above the highest supported SHALL be `422` with `error` `catalog_bundle_version_unsupported`, whose
message names the bundle's version. Stage 2 SHALL bind the whole bundle strictly: unreadable JSON, an unknown field at any depth,
an unknown enum value, or a constraint violation SHALL be `400`.

#### Scenario: Bundle from a newer ADAS

- **WHEN** a bundle with `format_version: 3` that also carries unknown fields is uploaded to an ADAS that supports version 2
- **THEN** the response is `422` with `error` `catalog_bundle_version_unsupported`, and the message names version 3

#### Scenario: A bundle carrying sql is refused readably by an older ADAS

- **WHEN** a `format_version: 2` bundle holding an aggregate pipeline with `sql` is uploaded to an ADAS that supports only version 1
- **THEN** the response is `422` with `error` `catalog_bundle_version_unsupported` naming version 2, not a `400` naming `sql`

#### Scenario: A version 1 bundle still imports

- **WHEN** a `format_version: 1` bundle exported before the bump is uploaded to an ADAS that supports version 2
- **THEN** it is parsed and planned as before, and each of its aggregate pipelines is imported in form mode

#### Scenario: Unknown nested field

- **WHEN** a `format_version: 2` bundle carries an unknown field inside a pipeline's transform
- **THEN** the response is `400`

#### Scenario: Missing required nested field

- **WHEN** a `format_version: 2` bundle has a table entry whose `table.name` is blank
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
`SKIP_IF_EXISTS` and `OVERWRITE`. Import SHALL never mutate an existing object.

- `FAIL_IF_EXISTS`: any bundle object that already exists on the target SHALL make `/import` answer `409` with `error`
  `catalog_import_conflict`, writing nothing.
- `SKIP_IF_EXISTS`: existing objects SHALL be kept unchanged and the rest imported; later bundle objects SHALL be validated against
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

### Requirement: Import entries

`ImportPreview` and `ImportResult` SHALL group entries by type (`required_system_tables`, `tables`, `pipelines`), each entry being
`{ name, import_action, prev, next, differs, metadata_only, problems[] }`.

- `import_action` SHALL be `CREATE` (new), `SKIP` (exists under `SKIP_IF_EXISTS`, or a required system table the target has)
  or `FAIL` (exists under `FAIL_IF_EXISTS`, or fails validation under either policy, including a required system table the target lacks
  and a pipeline whose target is held by a differently named pipeline).
- `prev` SHALL be the target's existing object re-exported in bundle shape, or null when new; `next` SHALL be the bundle's object,
  kept on `SKIP` entries too. `prev` SHALL follow the export's visibility rules for the caller: sensitive columns only with the
  clearance, and null for a table the caller may not get or a pipeline without
  `REGISTRY_READ`. `differs` is still computed from the full objects.
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
listed twice, a name listed both as a table and as a required system table). `ImportResult` SHALL also carry `import_id` and `outcome`:
`completed`; `rolled_back` when an object failed and compensation removed everything it had created; or `rollback_failed` when any
entry ended `rollback_failed`.

#### Scenario: Differing object under either policy in preview

- **WHEN** `/import/preview` runs with a bundle whose table `t` differs from the target's `t`, under each policy
- **THEN** both responses are `200`, and the `t` entry carries `prev`, `next` and `differs: true`

#### Scenario: Description-only difference

- **WHEN** the only difference between `prev` and `next` is a column description
- **THEN** the entry has `differs: true` and `metadata_only: true`

### Requirement: Shared validation pass

Both import calls SHALL run one validation pass that executes the real table create, define-schema and pipeline create
services inside a single Postgres transaction that is always rolled back, with ClickHouse DDL replaced by a no-op, and then runs the
arm-time pipeline checks on each pipeline, once every pipeline is created, to fill `armable` and `arm_problems`. The pass SHALL run in its own transaction, never a caller's, and SHALL tag its log lines with `catalog_import_validation=true`
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

### Requirement: Required system tables are checked, never written

Import SHALL check each required system table by name against the target: the table SHALL exist, be system-flagged and ACTIVE, and the
entry SHALL be `SKIP` when it is and `FAIL` under either policy when it is not. Its entry SHALL carry `name`, `import_action` and
`problems` only.

Import SHALL NOT compare a required system table's columns: a pipeline that reads a column the target lacks SHALL be reported through
the validation pass's `armable: false` and `arm_problems`. Import SHALL NOT write, compare or report grants on a system table.

#### Scenario: System-table grants untouched

- **WHEN** a bundle that requires `dial_usage_log` is imported with `SKIP_IF_EXISTS`
- **THEN** the `dial_usage_log` entry is `SKIP`, and the target's `dial_usage_log` grants are byte-identical before and after

#### Scenario: Missing system table

- **WHEN** the bundle requires a system table the target does not have
- **THEN** that entry is `FAIL` with a problem saying it does not exist, and `/import` is `422` with `error` `catalog_import_invalid`

#### Scenario: Missing column surfaces at arm time

- **WHEN** a bundle pipeline reads a `dial_usage_log` column the target's `dial_usage_log` lacks
- **THEN** the `dial_usage_log` entry is `SKIP`, and the pipeline entry reports `armable: false` with an `arm_problems` entry naming
  the column

### Requirement: Import order and no grants

Import SHALL apply objects in dependency order: source tables, then enrichment tables, then `enrich` pipelines, then `aggregate`
pipelines. Import SHALL write no grants: every table it creates has empty `access` lists, so until an operator grants access with
`PUT /v1/tables/{name}/access` only callers holding the global privileges can see or read it.

#### Scenario: Imported table has no grants

- **WHEN** a table that has grants on the source is exported and imported
- **THEN** `GET /v1/tables/{name}/access` on the target returns five empty lists

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
then arm each pipeline; expect aggregates to rebuild from zero; and run any data-sync backfill separately, since runner state does not
travel.

#### Scenario: Runbook present

- **WHEN** the change ships
- **THEN** `docs/catalog-promotion.md` exists and covers each of the five steps

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
