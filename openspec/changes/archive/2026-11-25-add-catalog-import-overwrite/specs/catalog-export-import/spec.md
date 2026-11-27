## ADDED Requirements

### Requirement: Updating an existing object

Under `resolution_policy=OVERWRITE` import SHALL compare each bundle table and pipeline that exists on the target as an ACTIVE user
table, or as a pipeline, with the target's full object (not the caller-visible `prev`), and SHALL derive from the difference the
changes listed below, each applied through the service the matching endpoint uses. An object with at least one change SHALL be
`UPDATE`; an object with none SHALL be `SKIP`, whatever it keeps, so a bundle re-imported under `OVERWRITE` converges and its
second run writes no revision. Every other planning rule applies to an `UPDATE` object as it does to a `CREATE` one: an object
depending on a refused object is `FAIL`, a PENDING or FAILED existing table is `FAIL`, and a bundle table whose name is a system
table on the target is `FAIL`.

A table SHALL change as follows, the columns matched by exposed `name`:

- its `description` and `tag_order` SHALL follow the bundle (`update_table`, through the `PUT /v1/tables/{name}` path);
- a column the bundle carries and the target lacks SHALL be added (`add_column`, through the `PATCH /v1/tables/{name}/schema` `add`
  operation), appended after the target's columns;
- a column's `description`, `display_name`, `tag` and `heavy` SHALL follow the bundle, and its `sensitive` flag SHALL follow it
  from `false` to `true` only (`update_column`, through the schema-patch `update` operation);
- all of a table's column changes SHALL be one schema patch, so their ClickHouse commands are one `ALTER`.

A table SHALL keep, and report under `not_applied`: a column the target has and the bundle lacks (`extra_column`), since an import
never drops a column; a column order that differs from the bundle's (`column_order`); a `sensitive` flag the bundle would clear
(`sensitive_loosening`), since an import never widens access to a column; and its target-owned `ttl` (`target_owned`, see
"Environment-specific values reported").

A pipeline whose declaration differs from the bundle's, once the target-owned members are taken from the target, SHALL be
re-declared from the bundle's `view=source` projection (`redeclare`, through the `PATCH /v1/pipelines/{name}` path), listing the
declaration members that change. Its `enabled` state SHALL be kept, except that an enabled `aggregate` pipeline SHALL be disabled
first (`disable`), because a declaration patch on an enabled aggregate is refused, and SHALL stay disabled (see "Pipelines are
imported disabled").

A difference no listed change reaches SHALL make the entry `FAIL`, naming the field and saying the object must be deleted and
re-imported or changed by hand: a column's `source_name`, `type`, `element_type`, `nullable` or enum values; a table's `type` or
`write`; `ordering_key`, `partition_by`, `grain_key`, `cardinality`, `source_table`, `identity_column` or `version_column`; and a
pipeline's `kind` or `target`. An object with a change SHALL also be `FAIL` when the target table holds a column hidden from the
caller, by sensitivity or by a column policy, or when the caller may not get the table, because the schema patch resolves the
caller's visible columns only. The update's privileges ("Import authorization") are checked before this refusal, so a caller
lacking them is answered `403`.

Each object entry SHALL carry `changes[]`, `not_applied[]` and `effects[]`:

- `changes`: `{ op, name, fields }`, `op` one of `update_table`, `add_column`, `update_column`, `disable` and `redeclare`, `name`
  the column for a column change, and `fields` the members that change;
- `not_applied`: `{ kind, name, reason }`, `kind` one of `extra_column`, `column_order`, `sensitive_loosening` and `target_owned`;
- `effects`: `{ kind, pipeline }`, consequences the import reports and does not act on. `kind` is one of `generation_bump`,
  `rebuild_from_zero`, `rearm_required` and `existing_rows_not_rederived`, and `pipeline` names the pipeline affected:
  - a re-declaration bumps that pipeline's `generation` (`generation_bump`);
  - so does a change to an enrichment's columns or column descriptions, which bumps the generation of the pipeline targeting it;
  - a re-declared `aggregate` rebuilds its target from zero (`rebuild_from_zero`);
  - an `aggregate` the import disabled must be re-armed (`rearm_required`);
  - a re-declared `enrich` pipeline leaves the rows it already wrote with their previous values until a backfill re-derives them
    (`existing_rows_not_rederived`).

Under `SKIP_IF_EXISTS` an existing object's `SKIP` entry SHALL carry the `changes`, `not_applied` and `effects` `OVERWRITE` would
give it and SHALL write none of them. Under `FAIL_IF_EXISTS`, and on `CREATE` entries, the three lists SHALL be empty.

#### Scenario: A new column and changed descriptions are applied

- **GIVEN** a target holding enrichment `usage_identity` and its `enrich` pipeline `usage-identity-live`, both from an earlier
  import of a bundle
- **WHEN** a later bundle adds a nullable column `client_version` to `usage_identity` and changes two column descriptions, and is
  imported with `resolution_policy=OVERWRITE`
- **THEN** the `usage_identity` entry is `UPDATE` with an `add_column` change for `client_version` and two `update_column` changes
  naming `description`, its result `status` is `updated`, and `GET /v1/tables/usage_identity` lists `client_version` last and
  the new descriptions

#### Scenario: A changed output expression re-declares an enrich pipeline in place

- **GIVEN** an enabled `enrich` pipeline `usage-identity-live` at generation 3
- **WHEN** a bundle whose declaration of it changes one `transform.outputs` expression is imported with `OVERWRITE`
- **THEN** the entry is `UPDATE` with a `redeclare` change naming `transform.outputs` and effects `generation_bump` and
  `existing_rows_not_rederived`, and afterwards the pipeline is still enabled, at generation 4, with the bundle's expression

#### Scenario: An enabled aggregate is disabled and left disabled

- **GIVEN** an enabled `aggregate` pipeline `sessions_rollup`
- **WHEN** a bundle changing its `filter` is imported with `OVERWRITE`
- **THEN** the entry carries the changes `disable` and `redeclare` and the effects `rebuild_from_zero` and `rearm_required`, and
  afterwards `GET /v1/pipelines/sessions_rollup` reports `enabled: false` with the bundle's `filter`

#### Scenario: A second run converges

- **WHEN** the same bundle is imported again with `OVERWRITE`
- **THEN** every entry is `SKIP`, no revision is written, and the response is `200` with `outcome` `completed`

#### Scenario: A column only the target has is kept

- **WHEN** a target table carries a column `local_note` the bundle's version lacks, and the bundle is imported with `OVERWRITE`
- **THEN** `local_note` still exists afterwards, and the entry carries a `not_applied` item of kind `extra_column` naming it

#### Scenario: Sensitivity is tightened, never loosened

- **WHEN** a bundle marks column `a` `sensitive: true` and column `b` `sensitive: false`, where the target has `a` not sensitive
  and `b` sensitive, and is imported with `OVERWRITE`
- **THEN** `a` is sensitive afterwards through an `update_column` change, `b` is still sensitive, and the entry carries a
  `not_applied` item of kind `sensitive_loosening` naming `b`

#### Scenario: A type change cannot be updated

- **WHEN** a bundle changes the `type` of an existing column and is imported with `OVERWRITE`
- **THEN** `/import/preview` reports the table entry `FAIL` naming the column and `type`, and `/import` is `422` with `error`
  `catalog_import_invalid`, writing nothing

#### Scenario: A changed pipeline target cannot be updated

- **WHEN** a bundle declares an existing pipeline with a different `target` and is imported with `OVERWRITE`
- **THEN** the pipeline entry is `FAIL` naming `target`

#### Scenario: A hidden column blocks an update

- **GIVEN** a caller without `TABLE_SENSITIVE` on a target table `t` that has a sensitive column
- **WHEN** the caller imports, with `OVERWRITE`, a bundle that changes `t`'s description
- **THEN** the `t` entry is `FAIL`, saying the update needs every column of `t` visible to the caller
- **AND WHEN** the bundle's `t` equals the target's
- **THEN** the `t` entry is `SKIP`

#### Scenario: The safe policy previews what OVERWRITE would do

- **WHEN** the bundle of "A new column and changed descriptions are applied" is imported with `SKIP_IF_EXISTS`
- **THEN** the `usage_identity` entry is `SKIP` and lists the `add_column` and `update_column` changes, and the target is unchanged

## MODIFIED Requirements

### Requirement: Resolution policy

Both import calls SHALL require the query parameter `resolution_policy`, with deployment manager's values `FAIL_IF_EXISTS`,
`SKIP_IF_EXISTS` and `OVERWRITE`. Only `OVERWRITE` SHALL change an existing table definition, column or pipeline, and only as
"Updating an existing object" defines; apart from it, import changes only the access entries a table definition stores: an import
called with `include_access=true` writes those onto existing tables as "Access import" defines, under every policy. An access entry
is not a bundle object for this requirement, so an existing entry never makes `FAIL_IF_EXISTS` conflict.

- `FAIL_IF_EXISTS`: any bundle object that already exists on the target SHALL make `/import` answer `409` with `error`
  `catalog_import_conflict`, writing nothing.
- `SKIP_IF_EXISTS`: existing objects SHALL be kept unchanged, apart from their access, and the rest imported; later bundle objects SHALL be validated against
  the existing object, not the bundle's version of it.
- `OVERWRITE`: an existing object SHALL be updated as "Updating an existing object" defines, kept unchanged when nothing reaches
  it, and the rest imported; later bundle objects SHALL be validated against the existing object as the import leaves it.

A missing `resolution_policy` SHALL be `400`. A bundle re-run under `SKIP_IF_EXISTS` or `OVERWRITE` after a partial failure SHALL
converge on the bundle's objects.

#### Scenario: Second promotion fails under FAIL_IF_EXISTS

- **WHEN** a bundle already imported is imported again with `resolution_policy=FAIL_IF_EXISTS`
- **THEN** the response is `409` with `error` `catalog_import_conflict`, and nothing is written

#### Scenario: Second promotion skips under SKIP_IF_EXISTS

- **WHEN** the same bundle is imported again with `resolution_policy=SKIP_IF_EXISTS`
- **THEN** every entry is `SKIP` with `differs: false`, no revision is written, and the response is `200`

#### Scenario: Only the new object is created

- **WHEN** the bundle is re-exported with one new pipeline and imported with `SKIP_IF_EXISTS`
- **THEN** only that pipeline has `import_action` `CREATE` and is created

#### Scenario: OVERWRITE updates what differs

- **WHEN** a bundle already imported is re-exported after one column description changed on the source, and is imported with
  `resolution_policy=OVERWRITE`
- **THEN** the response is `200`, that table's entry is `UPDATE`, every other entry is `SKIP`, and the target's column carries the
  new description

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
`{ name, import_action, prev, next, differs, metadata_only, changes[], not_applied[], effects[], problems[] }`. The rules below are
about object entries; an access entry is not an object.

- `import_action` SHALL be `CREATE` (new), `UPDATE` (exists, and is changed under `OVERWRITE` as "Updating an existing object"
  defines), `SKIP` (exists under `SKIP_IF_EXISTS`, exists with nothing to change under `OVERWRITE`, or a required system table the
  target has) or `FAIL` (exists under `FAIL_IF_EXISTS`, or fails validation under any policy, including a required system table the
  target lacks, a pipeline whose target is held by a differently named pipeline, and a difference `OVERWRITE` cannot apply).
- `prev` SHALL be the target's existing object re-exported in bundle shape, or null when new; `next` SHALL be the bundle's object,
  kept on `SKIP` and `UPDATE` entries too. `prev` SHALL follow the export's visibility rules for the caller: sensitive columns only with the
  clearance, no column a column policy hides from the caller (see [table-access-control](../table-access-control/spec.md),
  "Column visibility by caller role"), and null for a table the caller may not get or a pipeline without
  `REGISTRY_READ`. `differs` is still computed from the full objects, so it MAY be `true` when only a column omitted from `prev`
  differs, as for a sensitive column the caller cannot see.
- An existing user table that is PENDING or FAILED SHALL be `FAIL` under every policy, telling the operator to delete it first; a
  bundle table whose name is a system table on the target SHALL be `FAIL`. An object that depends on a refused object (an enrichment
  of a refused source, a pipeline reading or targeting one) SHALL be `FAIL` naming that dependency.
- An enrichment table to be created, or a pipeline to be created, that depends on a table the bundle neither carries under `tables`
  nor lists under `required_system_tables` SHALL be `FAIL` naming that table when the target does not have it either, a name an SQL
  statement's `FROM` reads excepted as "An SQL declaration's import dependencies are read from its statement" defines. A dependency
  the bundle does not list SHALL also be ACTIVE on the target: one that is PENDING or FAILED there SHALL make the object `FAIL`
  naming it and its state, since nothing over it could be armed, and a table a pipeline reads cannot be deleted for repair while
  that pipeline exists. Its columns are left to the validation pass, and a system table among them is not checked as a required
  system table.
- `differs` SHALL compare the serialized `prev` and `next`, excluding `enabled`, state and timestamps; `metadata_only` SHALL be true
  when only descriptions, display names or tags differ. A `SKIP` entry under `OVERWRITE` MAY therefore carry `differs: true`, its
  differences all listed under `not_applied`.
- `changes`, `not_applied` and `effects` SHALL be filled as "Updating an existing object" defines, and SHALL be empty otherwise.
- Pipeline entries SHALL also carry `armable`, `arm_problems[]` and `reused_name`.
- `ImportResult` entries SHALL add `status`: `created`, `updated`, `skipped`, `rolled_back`, `rollback_failed` or `failed`. An object not
  attempted because the import stopped earlier SHALL be `skipped` with a problem naming where it stopped.
- `import_action` SHALL be written in upper case, as deployment manager writes it.

The top level SHALL carry `env_specific[]` and, on `ImportPreview`, `validation_errors[]` (bundle-level problems: limits, a name
listed twice, a name listed both as a table and as a required system table, and the bundle-level access problems of "Access import"). `ImportResult` SHALL also carry `import_id` and `outcome`:
`completed`; `rolled_back` when an object or the access step failed and compensation removed everything it had created and
reverted everything it had updated; or `rollback_failed` when any entry ended `rollback_failed`.

#### Scenario: Differing object under either policy in preview

- **WHEN** `/import/preview` runs with a bundle whose table `t` differs from the target's `t`, under each policy
- **THEN** every response is `200`, and the `t` entry carries `prev`, `next` and `differs: true`

#### Scenario: Description-only difference

- **WHEN** the only difference between `prev` and `next` is a column description
- **THEN** the entry has `differs: true` and `metadata_only: true`

#### Scenario: A hidden column is omitted from prev

- **GIVEN** a target table `t` with columns `a` and `c`, and a caller holding `TABLE_CREATE` and `get` on `t` whose only
  governing role's `columns` on `t` is `["a"]`
- **WHEN** the caller runs `/import/preview` with a bundle carrying `t`
- **THEN** the `t` entry's `prev` lists column `a` and not `c`

#### Scenario: A dependency in neither the bundle nor the target

- **WHEN** `/import/preview` runs with `resolution_policy=SKIP_IF_EXISTS` and a bundle holding a form-mode pipeline `p` whose input
  `usage_base` the bundle neither carries nor lists, and whose target the bundle carries, on a target without `usage_base`
- **THEN** the `p` entry is `FAIL` naming `usage_base`, and `/import` is `422` with `error` `catalog_import_invalid`
- **AND WHEN** the target has `usage_base`
- **THEN** the `p` entry is `CREATE`
- **AND WHEN** the target's `usage_base` is PENDING or FAILED
- **THEN** the `p` entry is `FAIL` naming `usage_base` and its state, and `/import` is `422` with `error`
  `catalog_import_invalid`

#### Scenario: A converged object with a kept difference is SKIP

- **WHEN** the only difference between a target table and the bundle's is a column the target has and the bundle lacks, and the
  bundle is imported with `OVERWRITE`
- **THEN** the entry is `SKIP` with `differs: true`, an empty `changes` and one `not_applied` item

### Requirement: Shared validation pass

Both import calls SHALL run one validation pass that executes the real table create, define-schema, table update, schema-patch,
pipeline create and pipeline patch services inside a single Postgres transaction that is always rolled back, with ClickHouse DDL
replaced by a no-op, and then runs the
arm-time pipeline checks on each pipeline it created or updated and left disabled, once every pipeline is created or updated and, when the import is called with
`include_access=true` and no access entry is `FAIL`, every access entry of the bundle is applied in the same transaction (see
"Access import"), to fill `armable` and `arm_problems`. An updated pipeline that stays enabled SHALL be checked by its own
re-declaration, which runs the arm-time gate because it leaves the pipeline enabled, and a refusal there SHALL make its entry `FAIL`.
`armable`, `arm_problems` and each access entry's `ineffective_grants` SHALL
therefore describe the target as the import would leave it, its access included. When an access entry is `FAIL` before the access
is applied (an entry the checks refuse, or a bundle-level problem), none SHALL be applied: arming SHALL still run, against the target
without the bundle's access. When applying the access inside the pass fails, every access entry SHALL be `FAIL` and every pipeline
SHALL carry the `not checked` arm problem instead of being armed, as "Access import" defines. In both cases every access entry's
`ineffective_grants` SHALL be empty. The pass SHALL run in its own transaction, never a caller's, and SHALL tag its log lines with `catalog_import_validation=true`
in the MDC, since the services it calls log registrations, patches and arms that are rolled back. A SQL-level failure while arming SHALL leave
the remaining pipelines with a `not checked` arm problem.

The pass SHALL check identifier rules, reserved names, `max-tables` against the total after import, and `max-columns-per-table`
including injected provenance columns and the columns an update adds. It SHALL list every Java-side refusal; a SQL-level failure SHALL stop the pass at that object
and be reported on it.

#### Scenario: Armable reported on a fresh target

- **WHEN** `/import/preview` runs a bundle of a source table, an enrichment, an aggregate target, an enrich pipeline and an aggregate
  pipeline on an empty target
- **THEN** each pipeline entry reports `armable: true`, and nothing is written

#### Scenario: Missing target column

- **WHEN** a skipped existing target table lacks a column a bundle pipeline requires
- **THEN** that pipeline reports `armable: false` with an `arm_problems` entry naming the column

#### Scenario: A column an update adds satisfies a pipeline

- **WHEN** the same bundle is imported with `OVERWRITE`, and the bundle's version of that table carries the column
- **THEN** the table entry is `UPDATE` with an `add_column` change for it, and the pipeline reports `armable: true`

#### Scenario: Table limit counted on the total

- **WHEN** the target holds `max-tables - 1` tables and the bundle creates two
- **THEN** `validation_errors` reports the limit and `/import` is `422` with `error` `catalog_import_invalid`

#### Scenario: An update over the column limit is refused

- **WHEN** an `OVERWRITE` update would bring a target table over `max-columns-per-table`
- **THEN** the table entry is `FAIL` naming the limit and `/import` is `422` with `error` `catalog_import_invalid`

#### Scenario: An enabled enrich re-declaration the gate refuses fails in preview

- **WHEN** an `OVERWRITE` import re-declares an enabled `enrich` pipeline with a declaration the arm-time gate refuses on the target
- **THEN** the pipeline entry is `FAIL` with the gate's problem, and `/import` is `422` with `error` `catalog_import_invalid`

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

Import SHALL require `TABLE_CREATE` at both endpoints, and because the create and update services are called in-process it SHALL
repeat the controller gates programmatically: global `TABLE_MODIFY` when the bundle creates any table, `TABLE_MODIFY` on each table
it updates (held globally or granted on that table), `REGISTRY_ADMIN` when it creates or updates any pipeline, and global
`TABLE_SENSITIVE` when any created column is sensitive or an update adds a sensitive column or sets `sensitive: true` on one.

A missing privilege SHALL be `403` with `error` `forbidden`. Compensation SHALL delete only objects this import created, without a
`TABLE_DELETE` check, and SHALL revert only objects this import updated. When security is not enforced, every check in this
requirement SHALL be skipped.

#### Scenario: Missing TABLE_MODIFY

- **WHEN** a caller with `TABLE_CREATE` but not `TABLE_MODIFY` imports a bundle that creates a table
- **THEN** the response is `403` with `error` `forbidden`, and nothing is written

#### Scenario: Missing TABLE_MODIFY on an updated table

- **WHEN** a caller with `TABLE_CREATE`, and `TABLE_MODIFY` neither globally nor on table `t`, imports with `OVERWRITE` a bundle
  that updates `t`
- **THEN** the response is `403` with `error` `forbidden`, and nothing is written
- **AND WHEN** a role the caller carries is granted `modify` on `t`
- **THEN** the import updates `t`

#### Scenario: Tightening sensitivity needs TABLE_SENSITIVE

- **WHEN** a caller without `TABLE_SENSITIVE` imports with `OVERWRITE` a bundle that sets `sensitive: true` on an existing column
- **THEN** the response is `403` with `error` `forbidden`, and nothing is written

#### Scenario: Updating a pipeline needs REGISTRY_ADMIN

- **WHEN** a caller without `REGISTRY_ADMIN` imports with `OVERWRITE` a bundle that re-declares an existing pipeline
- **THEN** the response is `403` with `error` `forbidden`, and nothing is written

#### Scenario: Security mode none

- **WHEN** security mode is `none` and a caller with no claims imports a valid bundle
- **THEN** the import succeeds

### Requirement: Pipelines are imported disabled

Import SHALL create every pipeline disabled, whatever its `exported_enabled`. An `OVERWRITE` update SHALL keep an existing pipeline's
`enabled` state, except an enabled `aggregate` it re-declares, which SHALL be left disabled. Arming SHALL remain a separate
`PATCH enabled=true`, which re-runs the arm-time validation; the import preview SHALL be recommended before arming and SHALL NOT be
enforced. Import SHALL NOT arm a pipeline: the order in which pipelines are armed is the operator's, as the runbook sets out.

#### Scenario: Enabled source pipeline lands disabled

- **WHEN** a pipeline exported with `exported_enabled: true` is imported
- **THEN** `GET /v1/pipelines/{name}` on the target reports `enabled: false`, and `PATCH enabled=true` then succeeds

#### Scenario: An updated enrich pipeline keeps running

- **WHEN** an enabled `enrich` pipeline is re-declared by an `OVERWRITE` import
- **THEN** `GET /v1/pipelines/{name}` reports `enabled: true` afterwards

#### Scenario: A disabled pipeline stays disabled

- **WHEN** a disabled pipeline of either kind is re-declared by an `OVERWRITE` import
- **THEN** it is still disabled afterwards

### Requirement: Best-effort compensation

`/import` SHALL apply each object as its own committed step and, on the first failure, SHALL delete what this import created and
revert what it updated, in reverse order (pipelines, then enrichments, then sources). Compensation is best effort, not a guarantee.

An updated pipeline SHALL be reverted by re-declaring its previous declaration and restoring its previous `enabled` state; an
updated table by restoring its previous `description`, `tag_order` and column metadata and dropping the columns this import added.
A revert that is refused or fails SHALL mark the entry `rollback_failed`; a reverted entry SHALL be `rolled_back`. A revert of a
re-declaration bumps the pipeline's `generation` once more.

Before deleting a table, import SHALL check that any pipeline targeting it was created by this import; otherwise it SHALL leave the
table and mark it `rollback_failed`. A crash mid-import, or a ClickHouse drop that succeeds before the catalog delete fails, MAY leave
orphans or a partly updated object; re-running the bundle with `SKIP_IF_EXISTS` SHALL recover a missing object, re-running it with
`OVERWRITE` SHALL also finish an update, and either SHALL refuse a left-behind PENDING or FAILED
table until it is deleted. Each created or updated object SHALL be written in its own audit revisions (table create, schema, table
update, pipeline patch) under the
importing user, and the import SHALL log one structured `catalog_import` event carrying an `import_id`, the bundle hash and the object
list. Every audit revision the import writes — creates, updates and compensation deletes and reverts alike — SHALL be tagged with that same `import_id`,
which the activity feed returns as `importId` (see the `audit-trail` capability), so what an import did, including what a crash
mid-import left behind, is recoverable from the feed and not only from the log.

#### Scenario: DDL failure on the second table

- **WHEN** ClickHouse DDL fails while creating the second table of a bundle
- **THEN** the first table is gone from both the catalog and ClickHouse, the `ImportResult` marks it `rolled_back`, and re-running
  with `SKIP_IF_EXISTS` after the cause is removed creates both

#### Scenario: DDL failure after an update

- **WHEN** an `OVERWRITE` import updates table `a`, adding column `x` and changing a description, and ClickHouse DDL then fails on
  the second table
- **THEN** `a` no longer has `x` and carries its previous description, its entry is `rolled_back`, the `outcome` is
  `rolled_back`, and re-running with `OVERWRITE` after the cause is removed updates both

#### Scenario: Import revisions carry the import id

- **WHEN** an import completes and returns `import_id` `X`
- **THEN** every activity recorded for the objects it created or updated carries `importId` `X`

### Requirement: Environment-specific values reported

The import preview and result SHALL list under `env_specific` every environment-specific value in the bundle, each item being
`{ type, name, field, value, target_value, kept }`. The environment-specific fields are:

- **target-owned:** every `advanced` member (`rows_per_scan`, `rows_per_call`, `rate_rpm`, `sample_fraction`,
  `input_token_budget`), `trigger.cron`, the deployment binding `transform.preset`, `transform.model` and `transform.route`, and
  table `ttl`;
- **bundle-owned, reported only:** absolute date literals in filters and in an `aggregate` pipeline's `sql`.

One rule set SHALL decide both what this list reports and what an `OVERWRITE` update keeps.

An item SHALL be listed for each such field whose bundle value is set and, on an existing object, for each whose target value is set.
`value` is the bundle's value and `target_value` the target's, null on a new object. `kept` SHALL be `true` when the target's value
stays in place of the bundle's: on an existing object, for a target-owned field. The deployment binding is the exception: under
`OVERWRITE`, when the bundle's `transform.preset` differs from the target's, the bundle's whole binding is taken and its three
items are `kept: false`. A target-owned `advanced` member the target leaves unset stays unset. A date literal, and every field of a new
object, SHALL be `kept: false`.

Import SHALL NOT validate `transform.model` against the target. A date literal in `sql` SHALL be reported under the field
`sql`, found by the same literal scan as a filter's, so a statement's `WHERE request_time >= '2026-08-05'` is as visible to the operator
as the same predicate written as a filter.

#### Scenario: Model listed

- **WHEN** a bundle pipeline declares `transform.model: gemini-2.5-flash-lite`
- **THEN** `env_specific` holds an entry naming that pipeline, the field `transform.model` and the value

#### Scenario: Date literal in an SQL statement listed

- **WHEN** a bundle aggregate pipeline's `sql` carries `WHERE request_time >= '2026-08-05'`
- **THEN** `env_specific` holds an entry naming that pipeline, the field `sql` and the value `2026-08-05`

#### Scenario: Every advanced member listed

- **WHEN** a bundle pipeline declares `advanced.rows_per_scan: 500` and `advanced.sample_fraction: 0.5`
- **THEN** `env_specific` holds an entry for each, with `kept: false` on a new pipeline

#### Scenario: A target's tuning survives an update

- **GIVEN** an existing pipeline with `trigger.cron` `0 */30 * * * *`, `advanced.rate_rpm: 20` and `transform.model` `model-b`
- **WHEN** a bundle declaring `0 */10 * * * *`, `rate_rpm: 60`, `model-a` and the same preset, and a changed `transform.outputs`
  expression, is imported with `OVERWRITE`
- **THEN** the pipeline is re-declared with the bundle's outputs and keeps `0 */30 * * * *`, `rate_rpm: 20` and `model-b`, and the
  three `env_specific` items carry `target_value` and `kept: true`
- **AND WHEN** the only difference is those three values
- **THEN** the entry is `SKIP` with three `not_applied` items of kind `target_owned`

#### Scenario: A changed preset takes the bundle's binding

- **WHEN** a bundle switches an existing pipeline from preset `chat_completion` to `dial_app_route` with a `model` and `route`, and is
  imported with `OVERWRITE`
- **THEN** the pipeline is re-declared with the bundle's preset, model and route, and their `env_specific` items carry
  `kept: false`

#### Scenario: A table's TTL survives an update

- **WHEN** a bundle table's `ttl` differs from the existing table's and its description changed, and it is imported with `OVERWRITE`
- **THEN** the table's description is updated, its TTL is unchanged, and its `env_specific` `ttl` item carries `kept: true`

### Requirement: Post-import runbook

The project SHALL document the promotion runbook in `docs/catalog-promotion.md`. It SHALL cover eight steps:

1. Review `env_specific`. A model must exist on the target, because nothing checks it at arm time.
2. Enable enrichment-producing pipelines before the aggregates that read them.
3. Preview and then arm each pipeline.
4. Expect aggregates to rebuild from zero.
5. Run any data-sync backfill separately, since runner state does not travel.
6. When promoting access:
   - review every access entry's roles and row-policy values in the import preview and the export preview's `left_behind`;
   - handle the bundle as access configuration;
   - remove a role on the target with `DELETE /v1/tables/{name}/access?role=`, since an import never removes one.
7. To move one pipeline or some roles' access into an environment that already has the tables:
   - export it with `dependencies: false`, or with `access_only` and `roles`;
   - read the import preview for a table the target lacks, which the import then refuses rather than creates.
8. To bring a changed bundle to an environment that already has its objects:
   - preview with `OVERWRITE`;
   - read each entry's `changes`, `not_applied` and `effects`;
   - re-arm the aggregates the import disabled in dependency order;
   - backfill an `enrich` pipeline whose existing rows should be re-derived.

#### Scenario: Runbook present

- **WHEN** the change ships
- **THEN** `docs/catalog-promotion.md` exists and covers each of the eight steps
