## Why

An enrichment carries its own `get`, `read` and `sensitive` lists and row policies, as if it were governed on its own.
It is not: it is never queryable alone, and every read of its rows has its source as `FROM`. Its lists can only narrow
what the source allows, and only for per-table readers of the source. That narrowing is invisible in the API and needs
the `enrichment_read_inert` reason to explain grants that do nothing.

The sensitive-grant subset rule on derived tables assumes that whoever is cleared for a derived sensitive column is
cleared on the table it derives from. The maintainers do not want that: raw data and a corrected or reduced form of it
may have different readers. The control that matters stays: a pipeline's author must be entitled to the sensitive
columns it reads.

## What Changes

- **BREAKING** An enrichment's read-side access is its source's. `get`, `read` and `sensitive` on an enrichment are
  evaluated against its `source_table`'s grant lists (and the caller's global privileges); the enrichment stores none
  of its own. A caller that may read the source sees the enrichment's non-sensitive columns, and one that also holds
  the source's clearance sees its sensitive columns. `write` and `modify` stay per enrichment, and `modify` held on the
  enrichment still confers `get` on it, so the team maintaining its schema can read its definition.
- **BREAKING** Row policies are no longer stored on an enrichment. The source's policies already decide which source
  rows a caller reads, and an enrichment's values arrive only on those rows.
- **BREAKING** `PUT /v1/tables/{enrichment}/access` carrying a non-empty `get`, `read` or `sensitive` list is refused
  `409 enrichment_follows_source`, in `none` mode as in `oidc`; empty lists are accepted, so a body echoed from `GET`
  still succeeds. `PUT /v1/tables/{enrichment}/row-policies/{role}` is refused with the same code.
- **BREAKING** The `enrichment_read_inert` reason is removed from `ineffective_grants`: the grant it reported can no
  longer be made.
- **BREAKING** The sensitive-grant subset rule is removed, for enrichment and aggregate targets alike. `PUT /v1/tables/{name}/access`
  and both schema endpoints no longer answer `422 sensitive_grant_widens`; pipeline registration, enable and aggregate
  refresh no longer answer `422 pipeline_validation_failed` for a widening role; and grant edits, schema edits that mark a column sensitive, and `enrich` registration, enable and armed patch no longer answer `409 pipeline_declaration_unresolvable`. A derived
  table's sensitive columns are read by its own clearance holders (for an enrichment, its source's), whether or not
  they are cleared on the table it derives from. A rollup column derived from a `sensitive` input column must still be
  declared `sensitive`.
- A Postgres migration removes the `get`, `read` and `sensitive` lists and every row policy stored on an enrichment.
  State an earlier build stores there later widens nothing: a leftover list confers nothing and a leftover policy only
  narrows, and both can be removed through the API. The change widens access in five ways: a per-table reader of the
  source gains the enrichment's columns its empty `read` list used to withhold; a holder of the source's clearance
  gains the enrichment's sensitive columns its narrower `sensitive` list used to withhold; a role whose enrichment row
  policy blanked values sees them on every source row it reads; a caller that may get the source (through its `get`, `read` or `modify` list) gains `get` on every
  enrichment of it (listing, `GET /v1/tables/{enrichment}`, its revision snapshots, export, and its non-sensitive
  columns in schema discovery and translation); and a holder of an enrichment's `modify` and the
  source's clearance gains schema authority over the enrichment's sensitive columns. A caller confined only by a
  removed enrichment policy is also no longer refused `403 row_policy_confined` by the pipeline surfaces (preview,
  arming, the data plane) over that enrichment. Until column-level grants exist, a
  column not meant for every reader of the source is hidden by marking it `sensitive`.
- **BREAKING** It also narrows access. A role granted `get`, `read` or `sensitive` on an enrichment but not on its
  source loses, at deploy, the enrichment's listing and definition, its columns, or its sensitive columns. On query surfaces they become unknown fields
  (`400`), schema edits of them answer `422` unknown column, and pipeline authoring and runner scans that reference
  them answer `403 sensitive_column_not_entitled`. A runner cleared only on `dial_usage_log_payload`, for example,
  stops scanning until it is cleared on `dial_usage_log`.
- A row-policy `PUT` resolves the table before the both-forms check, so on any table a body carrying both `filter` and
  `where` for a missing table answers `404` rather than `422`.
- `supports_final`, and what an absent `final` resolves to, change for a per-table reader of an `append` source that
  could not read its enrichment: the entity is now final-capable for it.
- A catalog import that creates an enrichment of a source the target already grants makes it readable at once by that
  source's readers. An export of everything now includes the enrichments of every gettable source, and refuses
  `403 sensitive_column_not_entitled` when one carries a sensitive column the caller lacks the source's clearance for,
  where the enrichment used to be left out.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `table-access-control`: enrichments carry no read-side grant lists and no row policies; their read-side access is
  evaluated against the source; the grant and row-policy APIs refuse them `409 enrichment_follows_source`; the
  `enrichment_read_inert` reason is removed; `none` mode keeps only stored-state rules live; the derived-table rule
  covers aggregate targets and states what a source policy does not narrow in an enrichment; the SQL-condition rule no
  longer refuses `where` on an enrichment separately.
- `authentication`: the sensitive clearance for an enrichment's columns is the source's, so one flattened entity has one
  clearance; the flag change no longer defers to a derived-table subset rule.
- `structured-query`: composite entity visibility — an enrichment's columns are present for every caller that may read
  the source; its sensitive columns follow the source's clearance; read deduplication's final-capability follows.
- `query-schema-discovery`: `supports_final` counts every active enrichment for a caller that may read the source.
- `catalog-export-import`: an imported enrichment follows its source's grants; an export includes the enrichments of
  every gettable source.
- `audit-trail`: the clean-up migration records no revision, a stated limitation; an enrichment snapshot's read-side
  permissions come from its source's current lists.
- `table-management`: marking a column sensitive is no longer subject to a derived-table subset rule.
- `enrichment-registry`: sensitive-column entitlement across the grain join is judged on the enrichment's source.
- `enrichment-runtime`: the sensitive-grant subset rule is removed, and an enrichment's clearance is stated as never
  compared with the tables its pipeline reads; the schema-repoint rule no longer refers to the rule.
- `aggregate-pipeline`: the rollup grant subset rule is removed, from the form and the SQL declaration alike; a
  rollup target's clearance is its own.
- `rest-sql-endpoint`: an enrichment's `TABLE_READ` is its source's.
- `pipeline-registry`: a refresh re-runs the gate because the input and its enrichments may have moved; grants are no
  longer part of it.

## Impact

- **API.** `PUT /v1/tables/{name}/access` and `PUT /v1/tables/{name}/row-policies/{role}` on an enrichment (new `409`);
  `422 sensitive_grant_widens` and `409 pipeline_declaration_unresolvable` are no longer returned on the paths
  above;
  `ineffective_grants` loses a reason, and `sensitive_without_verb` counts an enrichment's `modify`; the `permissions`
  flags reported for an enrichment table on `GET /v1/tables` and `GET /v1/tables/{name}` follow the source;
  `GET /v1/queries/entities` `supports_final` and the default of an absent `final`.
- **Catalog schema.** New Postgres migration clearing `table_definition.access` read-side keys and
  `table_definition.row_policies` for `type = 'ENRICHMENT'`, including the seeded `dial_usage_log_payload`.
- **Code.** `TableAccessEvaluator` and `TableCallerPermissions` (resolve read-side privileges of an enrichment
  against its source), `TableAccessService`, `TableRowPolicyController`, `RowPolicyService`, `RowPolicyConditions`,
  `DefaultExceptionHandler`, `TableCatalogService` (one source lookup per listing), `TableMapper` and
  `CatalogExportService` (javadoc), `WildcardGranteeReport`, the entitlement messages in `EnrichSpecValidator` and
  `SensitiveProjectionGuard`, the OpenAPI descriptions of the access and row-policy endpoints (`ReplaceAccessRequest`,
  `AccessDto`, `AccessReplacedDto`); deletion of
  `SensitiveGrantInvariant`, `SensitiveGrantWidensException` and their calls from `TableAccessService`,
  `TableSchemaService`, `TableSchemaCreationService`, `EnrichSpecValidator`, `AggregateGovernance`,
  `AggregateSpecValidator` and `AggregateSqlValidator`, and of the pipeline-edge queries only it used (`PipelineRepository`, `PipelineInputDependents`,
  `PipelineGenerationService`, `ProviderRolePrivileges.rolesHoldingGlobally`).
- **Docs.** `docs/row-level-security.md`, `docs/catalog-promotion.md`, `docs/heavy-columns.md`, `docs/sql-reference.md`,
  `docs/column-mapping.md`, `docs/recommended-catalog/README.md`, `docs/release-0.24-upgrade.md`, and the `context:`
  section of `openspec/config.yaml`.
- **Out of scope.** Column-level grants (per-column roles or access tags), which would let an enrichment team hide a
  column from some readers of the source without the `sensitive` flag.
