## ADDED Requirements

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
and measure expressions.

#### Scenario: Enrichment pulls in its source

- **WHEN** the selection is only the enrichment table `usage_sentiment`
- **THEN** the bundle also holds its source table and the pipeline that targets `usage_sentiment`

#### Scenario: Enrichment referenced only inside a filter

- **WHEN** the selection is an aggregate pipeline whose only reference to enrichment `usage_sentiment` is the filter token
  `"usage_sentiment.sentiment"`
- **THEN** the bundle holds `usage_sentiment` and the pipeline that targets it

### Requirement: Export preview

`POST /v1/catalog/export/preview` SHALL take the same body as export and SHALL return the resolved object list without the bundle, and
SHALL write nothing.

Each entry SHALL carry `type`, `name`, `description` and `reason`. `reason` SHALL be `selected`, or the dependency that pulled the
object in (for example `source_table of usage_sentiment`). The response SHALL also list the `external_tables` the bundle would
reference and the `skipped` objects. Selection errors SHALL be the same as export's.

#### Scenario: Preview shows why a dependency was added

- **WHEN** the export preview selects only enrichment `usage_sentiment`, whose source is `usage_base`
- **THEN** the response lists `usage_sentiment` with `reason` `selected` and `usage_base` with `reason`
  `source_table of usage_sentiment`

### Requirement: Excluded and external tables on export

The export SHALL never write a system table (`TableDefinitionEntity.system`) as a table; it SHALL write each system table the closure
reaches as an **external reference** under `external_tables`, carrying a column fingerprint (`name`, `type` per column) and the
table's grants when the caller may export grants. An explicit selection that names a system table SHALL be `422` with `error`
`catalog_export_invalid`: a system table is referenced, never exported.

OTLP landing tables (`otel_<sink>_<signal>`) SHALL be excluded. PENDING and FAILED tables SHALL be excluded. When exporting
everything, each excluded table SHALL be listed under `skipped` with `reason` `otlp`, `pending` or `failed`, a pipeline whose
declaration no longer resolves SHALL be listed with `reason` `unresolvable` (and named in a `422` on explicit selection), and every object that needs
one (an enrichment of it, a pipeline reading or targeting it) SHALL be excluded too and listed with `reason` `dependency_skipped`, or
`otlp` for a pipeline that reads an OTLP landing table. An explicit selection that names or whose closure reaches an OTLP landing table,
a pipeline that reads one, or a PENDING or FAILED table SHALL be `422` with `error` `catalog_export_invalid`.

The column fingerprint SHALL list the external table's columns the caller may see: its sensitive columns only when the caller holds
the sensitive clearance on it.

#### Scenario: System table becomes an external reference

- **WHEN** the closure reaches an enrichment of `dial_usage_log`
- **THEN** `dial_usage_log` appears under `external_tables` with its column fingerprint, and not under `tables`

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
exported as part of everything; columns are never silently dropped. `access` lists, including those on external references, SHALL be
included only when the caller holds `TABLE_GRANT`; otherwise they SHALL be omitted and the bundle SHALL flag the omission
(`access_omitted: true`). When security is not enforced, every check in this requirement SHALL be skipped.

#### Scenario: Sensitive column the caller cannot see

- **WHEN** a caller with `REGISTRY_READ` and `TABLE_GET` on `t`, but no sensitive clearance on it, exports `t`, which has a sensitive column
- **THEN** the response is `403` with `error` `sensitive_column_not_entitled`

#### Scenario: Table the caller cannot list

- **WHEN** the caller selects a table it may not get
- **THEN** the response is `404` with `error` `not_found`

#### Scenario: Grants omitted without TABLE_GRANT

- **WHEN** a caller without `TABLE_GRANT` exports a table that has grants
- **THEN** the bundle entry carries no `access` and the bundle has `access_omitted: true`

#### Scenario: Security mode none

- **WHEN** security mode is `none` and a caller with no claims exports everything
- **THEN** the response is `200` and includes grants

### Requirement: Bundle format and version compatibility

The bundle SHALL carry `format: "adas-catalog"`, an integer `format_version` (initially `1`), `exported_at`, a `source` block
(`runner_contract_version`, `flyway_postgres_version`), `access_omitted`, `skipped`, `external_tables`, `tables` (each
`{ table, schema, access }` in request shape) and `pipelines` (each `{ pipeline, exported_enabled }`).

`source` SHALL be provenance only and never a gate. A bundle SHALL import into the ADAS version that exported it or any newer one.
`format_version` SHALL be raised whenever the shape of any bundle member changes, including a new enum value.

Import SHALL parse in two stages. Stage 1 SHALL read only `format` and `format_version`, leniently: a wrong or missing `format` SHALL
be `400`, and a `format_version` above the highest supported SHALL be `422` with `error` `catalog_bundle_version_unsupported`, whose
message names the bundle's version. Stage 2 SHALL bind the whole bundle strictly: unreadable JSON, an unknown field at any depth,
an unknown enum value, or a constraint violation SHALL be `400`.

#### Scenario: Bundle from a newer ADAS

- **WHEN** a bundle with `format_version: 2` that also carries unknown fields is uploaded to an ADAS that supports version 1
- **THEN** the response is `422` with `error` `catalog_bundle_version_unsupported`, and the message names version 2

#### Scenario: Unknown nested field

- **WHEN** a `format_version: 1` bundle carries an unknown field inside a pipeline's transform
- **THEN** the response is `400`

#### Scenario: Missing required nested field

- **WHEN** a `format_version: 1` bundle has a table entry whose `table.name` is blank
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

#### Scenario: Legacy output_vars preserved

- **WHEN** a pipeline whose transform declares `output_vars` is exported and imported
- **THEN** the imported pipeline's `view=source` projection declares the same `output_vars` and no `outputs`

### Requirement: Import preview and apply

`POST /v1/catalog/import/preview` and `POST /v1/catalog/import` SHALL each take `multipart/form-data` with a `file` part (the bundle as
exported) and an optional `options` part (`application/json`:
`{ "role_map": { "<source role>": "<target role>" }, "acknowledge_reused_names": false }`).

`/import/preview` SHALL write nothing and SHALL return `200` with an `ImportPreview` whenever the bundle parses, problems included.
`/import` SHALL run the same validation pass, and only if it finds no problem SHALL apply the bundle and return `200` with an
`ImportResult`, also when an object fails during apply and compensation runs (`outcome` `rolled_back` or `rollback_failed`). Under
`FAIL_IF_EXISTS`, an existing object SHALL make `/import` answer `409` even when the pass would also find other problems; `/import`
checks collisions after authorization and before the validation pass. A missing, non-integral or below-1 `format_version`, a
non-JSON file, a duplicate JSON key or a null list element SHALL be `400` `bad_request`.
A missing `file` part SHALL be `400`. An `options` part with an unknown field SHALL be `400`.

#### Scenario: Preview writes nothing

- **WHEN** `/import/preview` is called with a valid bundle on an empty target
- **THEN** the response is `200`, every entry has `import_action` `CREATE`, and no table, column, grant or pipeline exists afterwards

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

`ImportPreview` and `ImportResult` SHALL group entries by type (`external_tables`, `tables`, `pipelines`), each entry being
`{ name, import_action, prev, next, differs, metadata_only, problems[] }`.

- `import_action` SHALL be `CREATE` (new), `SKIP` (exists under `SKIP_IF_EXISTS`, or an external reference whose fingerprint matches)
  or `FAIL` (exists under `FAIL_IF_EXISTS`, or fails validation under either policy, including an external-table fingerprint mismatch
  and a pipeline whose target is held by a differently named pipeline).
- `prev` SHALL be the target's existing object re-exported in bundle shape, or null when new; `next` SHALL be the bundle's object
  after `role_map`, kept on `SKIP` entries too. `prev` SHALL follow the export's visibility rules for the caller: grants only with
  `TABLE_GRANT`, sensitive columns only with the clearance, and null for a table the caller may not get or a pipeline without
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
listed twice, a name listed both as a table and as an external table). `ImportResult` SHALL also carry `import_id` and `outcome`:
`completed`; `rolled_back` when an object failed and compensation removed everything it had created; or `rollback_failed` when any
entry ended `rollback_failed`.

#### Scenario: Differing object under either policy in preview

- **WHEN** `/import/preview` runs with a bundle whose table `t` differs from the target's `t`, under each policy
- **THEN** both responses are `200`, and the `t` entry carries `prev`, `next` and `differs: true`

#### Scenario: Description-only difference

- **WHEN** the only difference between `prev` and `next` is a column description
- **THEN** the entry has `differs: true` and `metadata_only: true`

### Requirement: Shared validation pass

Both import calls SHALL run one validation pass that executes the real table create, define-schema, grant and pipeline create
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

### Requirement: External references are checked, never written

Import SHALL check each external reference against the target: the table SHALL exist, be system-flagged and ACTIVE, and every
fingerprinted column SHALL exist with the same type; otherwise the entry SHALL be `FAIL` under either policy.

Import SHALL NOT write grants on a system table. An external reference's grants (after `role_map`) SHALL only be compared with the
target's: a difference SHALL make the entry `SKIP` with `differs: true` and SHALL be listed in `env_specific`. An enrichment of a system
table SHALL be checked against the target's system-table grants; a bundle grant on the enrichment that is wider than they allow SHALL
make the enrichment `FAIL` with a problem naming the target grant.

#### Scenario: System-table grants untouched

- **WHEN** a bundle whose `dial_usage_log` grants differ from the target's is imported with `SKIP_IF_EXISTS`
- **THEN** the `dial_usage_log` entry is `SKIP` with `differs: true`, and the target's `dial_usage_log` grants are byte-identical before
  and after

#### Scenario: Fingerprint mismatch

- **WHEN** the bundle fingerprints a `dial_usage_log` column with a type that differs on the target
- **THEN** the `dial_usage_log` entry is `FAIL` and `/import` is `422` with `error` `catalog_import_invalid`

#### Scenario: Enrichment grant wider than the target's system grant

- **WHEN** a bundle enrichment of `dial_usage_log` grants sensitive access to a role the target's `dial_usage_log` does not
- **THEN** the enrichment entry is `FAIL` with a problem naming the target grant

### Requirement: Import order, grants and role_map

Import SHALL apply objects in dependency order: source tables, then enrichment tables, then `enrich` pipelines, then `aggregate`
pipelines, applying each table's grants immediately after its schema is defined.

`options.role_map` SHALL rewrite every role in every grant list before validation. A role not in the map SHALL be kept and listed in
`env_specific`.

#### Scenario: Role rewritten

- **WHEN** a bundle grants `TABLE_READ` on `t` to `source-analysts` and `role_map` maps `source-analysts` to `target-analysts`
- **THEN** after import `t` grants `TABLE_READ` to `target-analysts` and not to `source-analysts`

#### Scenario: Sensitive enrichment grant ordered after its source

- **WHEN** a bundle holds a source table and an enrichment of it, both granting the same sensitive access
- **THEN** the import succeeds, because the source's grants are in place before the enrichment's are checked

### Requirement: Import authorization

Import SHALL require `TABLE_CREATE` at both endpoints, and because the create services are called in-process it SHALL repeat the
controller gates programmatically: global `TABLE_MODIFY` when the bundle creates any table, `REGISTRY_ADMIN` when it creates any
pipeline, `TABLE_GRANT` when it carries access lists on tables it creates, and global `TABLE_SENSITIVE` when any created column is
sensitive.

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
refuse such a bundle with `422` `catalog_import_invalid` unless `options.acknowledge_reused_names` is true.

The guard exists because the Enrichment Runner keys its scan cursor by pipeline name and its DLQ and spend state by name and
generation, never deletes them, and a recreated pipeline restarts at generation 1.

#### Scenario: Refused without acknowledgement

- **WHEN** the target once had a pipeline `p` that was deleted, and a bundle creating `p` is imported without the acknowledgement
- **THEN** the response is `422` with `error` `catalog_import_invalid`, and the preview entry for `p` shows `reused_name: true`

#### Scenario: Accepted with acknowledgement

- **WHEN** the same import passes `options.acknowledge_reused_names: true`
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
table until it is deleted. Each created object SHALL be written in its own audit revisions (table create, schema, grants) under the
importing user, and the import SHALL log one structured `catalog_import` event carrying an `import_id`, the bundle hash and the object
list.

#### Scenario: DDL failure on the second table

- **WHEN** ClickHouse DDL fails while creating the second table of a bundle
- **THEN** the first table is gone from both the catalog and ClickHouse, the `ImportResult` marks it `rolled_back`, and re-running
  with `SKIP_IF_EXISTS` after the cause is removed creates both

### Requirement: Environment-specific values reported

The import preview and result SHALL list under `env_specific` every environment-specific value in the bundle: pipeline
`transform.model`, `advanced.rate_rpm`, cron triggers, absolute date literals in filters, table `ttl`, grant roles `role_map` does not
map, and external-reference
grants that differ from the target's. Import SHALL NOT validate `transform.model` against the target.

#### Scenario: Model listed

- **WHEN** a bundle pipeline declares `transform.model: gemini-2.5-flash-lite`
- **THEN** `env_specific` holds an entry naming that pipeline, the field `transform.model` and the value

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
