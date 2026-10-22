## Why

The form declaration (`group_by` + `measures` + `filter`) can't express several things users need:
- an expression over aggregates, e.g. a sentiment computed from rating counts;
- an expression measure such as `sum(a*b)`;
- a non-trunc group key;
- `arg_max` as a measure.

Users also already think in SQL. They want to paste an `INSERT … SELECT` and have ADAS refresh it incrementally. Every aggregate pipeline declared so far fits a single-block statement, so this can ship without new refresh machinery.

## What Changes

- New aggregate-only declaration field `sql` on `POST/PATCH /v1/pipelines`. It holds exactly one `INSERT INTO <target> (cols…) SELECT …`.
  - It is mutually exclusive with `group_by`, `measures` and `filter`, and a mix is refused with a 422.
  - `target` stays required and must equal the statement's INSERT target.
  - `inputs` is optional; if given, it must equal the statement's single source.
- ADAS **parses and re-renders** the statement and never passes it through. Every governance gate still applies:
  - sensitive access and lineage
  - determinism
  - the function catalog
  - one input plus grain-joined enrichments only
  - the enrichment clampability rule
  - type and nullability parity with the form path
- **Incremental only.** A statement is admitted only if dirty-key full recompute stays correct for it:
  - It must flatten to one block. CTEs and derived tables may filter and compute.
  - The GROUP BY set must equal the target `ordering_key`, with non-nullable keys.
  - It may not use HAVING, DISTINCT, ORDER BY, LIMIT, windows, joins, set operations, subqueries, `SELECT *` or `FINAL`.
  - At most one time bucket is allowed, with a step of 1.
  - Anything else is refused with a 422 that names the clause.
- **The refresh contract is unchanged:**
  - the probe applies the statement's WHERE as the filter
  - dirty keys get a full recompute, and the restriction is pushed into the enrichment grain join
  - writes go into `ReplacingMergeTree(_updated_at)`
  - the cursor advances after the write
- Time buckets without an explicit zone are rendered UTC-pinned, as on the form path.
- PATCH switches modes explicitly:
  - sending `sql` clears `group_by`, `measures` and `filter`;
  - sending `group_by`/`measures` clears `sql`;
  - a patch with no declaration fields keeps the stored `sql`.
  - A changed `sql` is a re-declaration: generation bump plus cursor reset, and a 409 while enabled.
- Draft (disabled) writes accept an unparseable `sql` and store it as-is with a null `input_table`. It counts as incomplete for arming.
- Dependency guards (table delete, schema drop/rename, sensitive grant) read SQL-mode pipelines by **token scan**, never by parsing.
- Catalog export/import carries `sql`. `CatalogBundle.FORMAT_VERSION` is bumped, so an older ADAS refuses a newer bundle with the readable format 422.
- **No** Flyway migration, **no** new config property, **no** change to `view=compiled` (it stays refused for aggregates) or to the preview response shape.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `aggregate-pipeline`: adds the SQL declaration and its eligibility rules (refusals), compilation and refresh. Modifies:
  - the spec vocabulary, where `measures` is no longer universally required
  - the nullable-key rule
  - the probe-filter wording
  - the enrichment-reference derivation
- `pipeline-registry`:
  - the aggregate-kind fields include `sql`
  - mode-switching PATCH semantics
  - draft completeness, where `sql` counts as an authored declaration
  - gate timing
  - target retained on PATCH
- `enrichment-runtime`: the rename-repoint guard counts an aggregate's `sql` text as a read fragment (token scan, INSERT column-list prefix excluded).
- `catalog-export-import`: the bundle declaration carries `sql`; format version bump; import ordering and env-specific scan cover the SQL text.

## Impact

- **API:** `CreatePipelineRequest` / `PatchPipelineRequest` / `PipelineDto` gain `sql`, with authoring rules in its `@Schema`. The `adas-mcp` OpenAPI snapshot refresh is a follow-up in that repo.
- **Code:**
  - `service/query/sql/SqlQueryFrontEnd` gets a new `planInsert` and a raw source-region offset.
  - New `AggregateSqlValidator` and `AggregateSqlCompiler`.
  - Shared helpers are extracted from `AggregateSpecValidator` / `AggregateCompiler`.
  - `AggregateRefreshService` (refresh, probe, preview) branches on SQL mode.
  - `PipelineMapper` / `PipelineService` handle exclusivity, mode switching and completeness.
  - `QualifiedColumnTokens` / `declaredFieldsOf` gain the SQL token-scan branch.
  - Catalog bundle, parser and planner.
- **Storage:** `sql` is stored in the existing `spec` JSONB, so there is no migration and no Envers change.
- **Operational:** probe and refresh cost follow the statement WHERE's selectivity, the same as `filter_sql`.
