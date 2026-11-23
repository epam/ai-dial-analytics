## MODIFIED Requirements

### Requirement: Export authorization

Export SHALL require `REGISTRY_READ` at the endpoint, and SHALL check each selected table with the table-access evaluator for
`TABLE_GET`.

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
lacks the source's clearance — or a column the source's column policies hide from the caller refuses the export as above. The bundle
SHALL NOT carry access: no table's access entries — privileges, row policies or column policies — are exported, whatever the caller
holds, because access is each environment's own policy. When security is not enforced, every check in this requirement SHALL be
skipped.

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

- **WHEN** a caller holding `TABLE_GRANT` exports a table that has access entries, row policies and column policies
- **THEN** the bundle entry carries none of them

#### Scenario: Security mode none

- **WHEN** security mode is `none` and a caller with no claims exports everything
- **THEN** the response is `200`

#### Scenario: An enrichment of a gettable source joins the export

- **GIVEN** a caller holding `REGISTRY_READ` and `get` on a source with no sensitive column, without the source's
  clearance, and an enrichment of that source with a sensitive column, on which the caller holds nothing
- **WHEN** the caller exports everything
- **THEN** the response is `403` with `error` `sensitive_column_not_entitled`, naming the enrichment

### Requirement: Import entries

`ImportPreview` and `ImportResult` SHALL group entries by type (`required_system_tables`, `tables`, `pipelines`), each entry being
`{ name, import_action, prev, next, differs, metadata_only, problems[] }`.

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
listed twice, a name listed both as a table and as a required system table). `ImportResult` SHALL also carry `import_id` and `outcome`:
`completed`; `rolled_back` when an object failed and compensation removed everything it had created; or `rollback_failed` when any
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

### Requirement: Import order and no grants

Import SHALL apply objects in dependency order: source tables, then enrichment tables, then `enrich` pipelines, then `aggregate`
pipelines. Import SHALL write no access: every table it creates has no access entry, so until an operator grants access with
`PUT /v1/tables/{name}/access/{role}` only callers holding the global privileges can see or read a created source. A created
enrichment has no read-side access of its own: it is readable at once by every caller that may read its source, which may be a table
the target already had and had granted, subject to that source's column policies (see
[table-access-control](../table-access-control/spec.md)).

#### Scenario: Imported table has no grants

- **WHEN** a table that has access entries on the source is exported and imported
- **THEN** `GET /v1/tables/{name}/access` on the target returns an empty `roles` object

#### Scenario: An imported enrichment of an existing source follows its grants

- **GIVEN** a target on which `analytics-viewers`' entry on `dial_usage_log` carries `read` with no column policy
- **WHEN** a bundle creating an enrichment of `dial_usage_log` is imported
- **THEN** a caller carrying only `analytics-viewers` reads the new enrichment's non-sensitive columns at once, and
  `GET /v1/tables/{name}/access` on the enrichment returns an empty `roles` object
