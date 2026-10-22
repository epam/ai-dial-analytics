## Why

Authorization in ADAS stops at the table and the column: a caller that may read `dial_usage_log` reads
every project's rows. Operators need to give a team access to *its own* slice of a shared table — for
example, provider role `X` sees only rows of project `X-PRJ-1` — without copying data into a per-team
table. Permissions are already attached to provider roles (per-table `access` grants), so row-level
permissions should be attached to roles the same way.

## What Changes

- **Per-role row policies on catalog tables.** Each table gains a set of policies keyed by raw provider
  role. A policy is either `{"filter": <FilterNode>}` — a predicate in the JSON DSL's own filter grammar
  (CQL2-JSON) over that table's columns — or `{"all_rows": true}`.
- **ClickHouse row-policy semantics, permissive only.** A policy narrows what a role reads; it never
  grants a read. It follows the role, not the grant path, so a role holding `TABLE_READ` globally (e.g.
  through `READ_ONLY_ANALYTIC`) is confined too. A caller none of whose roles has a policy on a table is
  unconfined there; a caller with several policies sees their union; an `all_rows` policy lifts the
  confinement.
- **New endpoints** under `TABLE_GRANT`: `GET /v1/tables/{name}/row-policies`,
  `PUT /v1/tables/{name}/row-policies/{role}`, `DELETE /v1/tables/{name}/row-policies/{role}`.
- **Engine-enforced.** On every user read (DSL, SQL, CSV, Flight, counts, composite queries) ADAS sends
  ClickHouse's `additional_table_filters` setting carrying the caller's predicate for every table it is
  confined on. ClickHouse applies it to every reference of the table — joins, subqueries, set
  operations — after `FINAL`, with primary-key pruning intact (measured on 26.8 and 26.3).
- **`permissions.rows_restricted`** joins the caller-effective flags on the table read surface.
- **Governance.** A row-confined caller may not arm, preview or refresh a pipeline that reads a table it
  is confined on, nor use the pipeline data plane over it (`403 row_policy_confined` on all of them). A
  column a policy references cannot be dropped or renamed (`409 column_in_row_policy`).
- **Stated non-goals.** Policies narrow reads only, never writes. They do **not** propagate to derived
  tables (enrichment and aggregate targets): a derived table is governed only by its own policies, and
  the rows a pipeline wrote from an unfiltered source are an accepted risk the operator closes by
  setting policies on the targets. No restrictive policies; no caller-attribute parameters.

## Capabilities

### New Capabilities

None — row policies extend the existing per-table access model.

### Modified Capabilities

- `table-access-control`: adds the row-policy model, its management API, evaluation (permissive OR,
  follows the role, `all_rows`), engine enforcement through `additional_table_filters` and the
  invariants that keep it authoritative, the derived-table non-goal, `none`-mode behaviour, and
  `rows_restricted` in caller-effective reporting.
- `authentication`: the row-policy filter joins the components that consult the single security-mode
  resolution.
- `table-management`: table responses carry `permissions.rows_restricted`; a schema change may not drop
  or rename a column a row policy references.
- `pipeline-registry`: a row-confined caller may not arm, preview or refresh a pipeline over a table it
  is confined on.
- `enrichment-runtime`: the pipeline data plane refuses a caller confined on a table the pipeline reads.

## Impact

- **API**: three new `/v1/tables/{name}/row-policies` mappings (each `@RequiresPrivilege(TABLE_GRANT)`);
  additive `rows_restricted` field in every table `permissions` object; new error codes
  `row_policy_confined` and `column_in_row_policy`.
- **Catalog schema**: Postgres migration adding `table_definition.row_policies JSONB NOT NULL DEFAULT
  '{}'`, mirrored on `table_definition_aud` (the entity is `@Audited`).
- **Code**: new `RowPolicyFilter` (service/security), `RowPolicyPredicates` (service/query) and the
  row-policy service/controller; the two user-read entry points of `StructuredQueryExecutor` gain the
  filter; the schema PATCH guard, `PipelineService` arming, `AggregateRefreshService` preview/refresh and
  `PipelineAuthorizer` gain the confinement checks; `FilterTranslator` is reused against a table-only model.
- **Config**: none — no new property, so `docs/configuration.md` is unchanged.
- **Docs**: new operator topic doc `docs/row-level-security.md`.
- **Coordination**: the in-flight `add-ai-query-functions` change adds functions that call an external
  model; a row policy must not be able to call them (see design D7).
