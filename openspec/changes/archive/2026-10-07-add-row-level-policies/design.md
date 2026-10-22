## Context

See proposal.md for motivation. Today a caller's read of a table is all-or-nothing per table, with
sensitive columns hidden at one chokepoint (`CatalogQueryService.buildModel`). Every user read path —
DSL, SQL (Calcite → structured model), both CSV twins, Flight SQL, counts, composite joins/subqueries/set
operations — renders through jOOQ in `StructuredQueryBuilder` + `EnrichmentJoins` and executes on the
read client with `FinalSettings.userRead(...)` merged by `StructuredQueryExecutor.merged()`. Pipeline
reads (scan, group assembly, materialize, aggregate compile/refresh) build their own `SELECT`s and run
with `FinalSettings.pipeline()` or as `INSERT … SELECT` on the writer, often under `SystemIdentity`,
which holds `TABLE_READ` + `TABLE_SENSITIVE` globally and no provider roles. Per-table grants live in
`TableDefinitionEntity.access` (JSONB, `@Audited`) and are matched against raw provider roles by
`TableCallerPermissions`.

`clickhouse.database` defaults to empty, and the renderer emits **unqualified** table names, so tables
resolve against the read session's current database.

## Goals / Non-Goals

**Goals:**
- Role-attached, permissive row policies with ClickHouse semantics, expressed in the DSL filter grammar.
- Enforcement that cannot be bypassed by a query shape the renderer did not anticipate.
- One evaluation shared by enforcement and `rows_restricted` reporting.

**Non-Goals:**
- Restrictive (AND) policies; caller-attribute parameters (`param`) — the `param` refusal is the hook for
  a later ABAC change.
- Narrowing writes. Propagating policies to derived tables (accepted risk, stated in the spec).
- Using ClickHouse's own `CREATE ROW POLICY` (see D2).

## Decisions

### D1. ClickHouse semantics: the policy follows the role, permissive only
A policy narrows; it never grants (grants and global privileges still decide *whether* a table is
readable). The caller's rows on a table are the OR of the filters of its roles that have a policy there;
a role without a policy adds nothing, so a confined caller is not widened by picking up an unconfined
role; `all_rows` is ClickHouse's `USING 1`.
- *Rejected — row grant as a qualified `read` grant with union semantics:* an unfiltered read path
  (global `TABLE_READ`, another role's `read`) would win, so a role mapped to `READ_ONLY_ANALYTIC` could
  not be confined without a `roles-mapping` edit; it also makes the row grant an alternative to `read`
  rather than a refinement of it, which reads as counter-intuitive.
- *Rejected — restrictive policies now:* a second combination axis on every evaluation and report; no
  current use case.

### D2. Enforce with `additional_table_filters`, not in the rendered SQL and not with CH row policies
ADAS sends `additional_table_filters = {'<db>.<table>': '<predicate>', …}` as a per-query server setting.
ClickHouse then filters every reference of the table. Measured on throwaway 26.8.17 and 26.3.27
(1M-row `ReplacingMergeTree`, identical results):
- applied to FROM, alias, derived table, CTE, both `UNION ALL` arms, `IN (SELECT …)`, `EXISTS`, scalar
  subquery, `LEFT`/`INNER JOIN` on either side, self-join, `remote()`;
- applied **after `FINAL`** (a superseded version is not resurrected), also with `final=1`;
- `read_rows` identical to the same predicate in `WHERE` (32,769 vs 1,008,193 unfiltered), primary-key
  condition used; aggregate projections still used and filtered; trivial `count()` filtered;
- bare, table-qualified (`"src"."project_id"`) and db-qualified column names resolve, also when the
  query aliases the table; functions, `now()` windows and nested `and/or/not` match `WHERE`;
- works as an HTTP URL parameter; two-level escaping defeats `P0') OR 1 OR ('`.

Sharp edges found, each closed by a spec invariant: an **unknown key is silently ignored** (fail-open) →
D4; query-text `SETTINGS additional_table_filters = …` or `SET` **replaces** the client value → allow-list
invariant; `merge()` **bypasses** it → no-table-functions invariant; it **also applies to
`INSERT … SELECT`** → never sent on pipeline/system paths.
- *Rejected — inject the predicate in the renderer* (`StructuredQueryBuilder`/`EnrichmentJoins`): only as
  safe as the list of emission sites, one of which (`EnrichmentJoins.selects()`, the source re-read in the
  key `IN` subquery) is easy to miss; every new query shape is a new leak candidate.
- *Rejected — wrap each table as `(SELECT * FROM t WHERE p)` at `FinalTables.of`:* only unaliased tables
  pass there, and wrapping risks the projection/PREWHERE behaviour the ingest tables rely on.
- *Rejected — native `CREATE ROW POLICY`:* ADAS reads as one ClickHouse user; callers are not ClickHouse
  users or roles.

### D3. Policy filter = the DSL filter grammar over a table-only model
`filter` is a `FilterNode` validated by the existing DSL validator against a model of the table's own
stored columns (all of them, sensitive included, no enrichment joins, no caller-dependent hiding), and
rendered by `FilterTranslator` — so field-name → physical-column mapping, enum/object comparisons and
literal typing are exactly the query surface's. Rendering happens at evaluation from the stored tree, so
a later column-mapping change is honoured. Scope: logical/comparison/`in` nodes, `field`, `value`,
`array`, scalar `fn` (incl. `now`/`date_add`/`date_sub`), `case`, `lambda` in higher-order functions;
refused: `param`, aggregate and window functions, dotted enrichment fields, external-call functions.
16 KiB rendered cap per policy (a constant, not a property).
- *Rejected — `{column, values}` lists:* the first-round choice; replaced so
  policies and queries share one filter language. It would have been simpler to analyse but the schema
  guard (D8) works equally from the DSL column walker.

### D4. One evaluator; an entry for every confined table, keyed by the resolved database
`RowPolicyFilter` (service/security) reads provider roles from `SecurityClaimsExtractor` and the mode from
`SecurityModeResolver`, and produces the setting value. It emits an entry for **every** catalog table the
caller is confined on (unknown/unreferenced keys are inert; a missing one fails open), so correctness
does not depend on knowing which tables a statement touches. Keys are `'<db>.<table>'`, where `<db>` is
`ClickHouseProperties.sessionDatabase()` — the configured database, or `default` when blank — the one
expression the client builder now also sets the session's default database from, so the key and the
renderer's unqualified reference resolve against the same database by construction. (The first draft
resolved it with `SELECT currentDatabase()` at startup; unnecessary once the client is seen to set the
session database explicitly on every request.) Stored policies are read with a scalar projection
(`findStoredRowPolicies`, rows whose document is not `{}`), so a read pays one small catalog query and
renders only the tables that confine the caller. The same evaluator answers `rows_restricted`.
- *Rejected — entries only for the model's resolved tables:* reintroduces the "did we list every
  reference" problem D2 removes.
- *Rejected — bare keys:* match only when the session database equals the table's, an implicit coupling.

### D5. Attach to user reads only, last
The filter is merged into the user-read settings (`FinalSettings.userRead` call path) after every other
setting, so `merged()`'s "later overrides earlier" cannot drop it; a test pins the order. Pipeline reads
(`FinalSettings.pipeline()`), `ClickHouseInsertSelectExecutor` and the system identity never carry it —
the latter is also empty by construction (no provider roles).

### D6. Storage: a separate `row_policies` JSONB column on `table_definition`
`{"<role>": {"filter": {…}} | {"all_rows": true}}`, default `'{}'`, mirrored on `table_definition_aud`.
Mapped as text (see D7), which also makes the Envers dirty check a plain string comparison. Not a key
inside `access`: `access` is role lists with per-key merge `PUT` semantics; policies are a role→document
map with per-role lifecycle.
- *Rejected — a separate table:* a second write path to audit and cascade for data that is one table's
  definition.

### D7. Per-role sub-resource API under `TABLE_GRANT`
`GET …/row-policies`, `PUT|DELETE …/row-policies/{role}`. Per-role resources avoid the merge / revoke /
null ambiguity of a whole-document `PUT`. Each mapping is `@RequiresPrivilege(TABLE_GRANT)` (global-only),
so `EndpointAuthorizationCoverageTest` covers them. The role path variable must accept any provider role
name a grant list accepts, including characters that need percent-encoding; it is URL-decoded once and
validated like a grantee. A role containing `/` cannot be addressed by this path — implementation checks
whether the configured providers can emit one and, if so, raises it before the API ships.

**External-call functions:** the function catalog gains `QueryFunctionCatalog.callsExternalService(name)`,
answering `false` for every function today. No such function exists yet; the in-flight
`add-ai-query-functions` change introduces them and must declare them there. The policy validator asks
that method — it holds no list of its own. The method is a hook rather than a serialized descriptor field
so this change does not alter the published `/v1/queries/functions` shape the AI change also touches.

**Storage format:** `row_policies` is mapped as JSON *text* and converted by `RowPolicyCodec` with the
application's `JsonMapper`, for the reason `SavedQueryEntity.query` is: Hibernate's JSON format mapper is
Jackson 2 and cannot see the Jackson 3 `FilterNodeDeserializer` wiring.

### D8. Governance reuses existing edges and codes
- Schema guard: the DSL column walker over each stored filter yields referenced columns; drop/rename of
  one → `409 column_in_row_policy` (new code; the existing pipeline-dependency guard uses
  `409 conflict`, but a distinct code tells the operator which kind of dependency to remove).
- Arming/preview/refresh and the data plane all go through `RowPolicyFilter.requireUnconfinedPipeline`
  → `403 row_policy_confined`: `PipelineService.create/patch` before anything is applied,
  `AggregateRefreshService.preview/refresh`, and `PipelineAuthorizer.authorize` (scan, group-rows,
  materialize and the enrich preview) after its read/write checks. The tables checked are the input, the
  target's `source_table`, and every enrichment of either — a superset of the laundering rule's edges,
  chosen so the answer never depends on parsing a declaration that may not resolve. One code for every
  pipeline surface, rather than `pipeline_access_denied` on the data plane, because the remedy is the
  same everywhere (remove the confining policy or use an unconfined identity), and a client distinguishing
  "lacks a grant" from "is row-confined" needs the distinction on both sides.

### D9. Observability
ClickHouse records the setting (values included) in `system.query_log`. ADAS logs at debug only the
confined table names and role count, never the predicate, and adds the confined table names as an OTel
span attribute on the query span.

## Risks / Trade-offs

- [Derived tables leak a confined source's rows] → accepted; stated in
  `table-access-control`; `docs/row-level-security.md` carries the operator checklist (put policies on
  each enrichment/aggregate target a confined role may read).
- [A ClickHouse upgrade changes `additional_table_filters` semantics] → integration tests pin coverage,
  `FINAL` ordering and the override refusal; the spec requires re-verification on version bumps.
- [A future `SETTINGS` passthrough lets a caller clear the filter] → spec requires an allow-list that
  never contains the setting; a test sends `SETTINGS additional_table_filters = {}` and asserts filtered
  or refused.
- [Unknown key fails open, e.g. a database mismatch] → keys built from the startup-resolved session
  database and the same table name the renderer emits; a test asserts each confined table's key matches
  the rendered reference.
- [Settings travel in the URL; many policied tables × large filters could exceed `http_max_uri_size`
  (1 MiB default) or a proxy limit] → 16 KiB per policy; a confined caller typically spans few tables; an
  integration test proves a 256 KiB setting round-trips.
- [A non-indexable policy predicate (e.g. `lower(col) = …`) costs a full scan on every read] → it is what
  the operator asked for; `docs/row-level-security.md` recommends ordering-key columns.
- [`system.query_log` holds policy values] → policies are not secrets from ClickHouse operators, who
  already see the data.
- [A role confined on a table can still *write* other rows] → by design (ClickHouse semantics); stated.
- [Some measured properties are pinned by the spike, not by this change's test suite] → the integration
  test covers the REST surfaces and composite shapes; these were measured on throwaway ClickHouse 26.8 and
  26.3 but have no automated test here: Flight SQL end to end (it executes through the same
  `StructuredQueryExecutor` entry points, which a unit test pins), the DSL CSV twin, `FINAL` ordering on a
  deduplicating table, an enrichment-table policy inside a flattened entity, and a large (256 KiB) setting
  round-trip. A ClickHouse upgrade should re-run the spike (see the spec's re-verification requirement).

## Migration Plan

1. Postgres migration `V45__table_row_policies.sql`: `ALTER TABLE table_definition ADD COLUMN row_policies JSONB NOT NULL DEFAULT
   '{}'::jsonb` and the same on `table_definition_aud` (nullable there, as audit mirrors are).
2. Deploy. With no policies stored no caller is confined, the setting is not sent at all, and every
   read behaves exactly as before.
3. Rollback: the previous build does not map the column and ignores it; stored policies simply stop
   being enforced, so remove them first if a rollback must not widen reads silently.

## Revision after archive (review)

**D1 is revised: a reading role without a policy lifts the confinement.** On review, the
"policy follows the role" rule no longer lets a narrower confined role narrow a broad one the same caller
carries. A caller is unconfined on a table when one of its roles *may read the table* and has no policy
there — "may read" meaning the table's `read` grant names the role, or a `roles-mapping` maps it to
`TABLE_READ` globally and the caller holds `TABLE_READ` globally on the request (the union-of-mappings
lookup in `ProviderRolePrivileges` is advice-grade, so it is gated on the caller's own privilege). Roles that
give no read on the table — unrelated IdP groups, Core roles — never count, or almost every caller would be
unconfined. A broad role is confined by a policy of its own.

`all_rows` is removed: under the revised rule it is equivalent to "a reading role with no policy". A policy
is a `filter`; `{"all_rows": true}` is now an unknown member (400). The main `table-access-control` spec
carries the revised requirements; the delta above records the rule as first archived.
