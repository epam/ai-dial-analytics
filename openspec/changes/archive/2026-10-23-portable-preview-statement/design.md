## Context

The preview `statement` today is rendered straight from the jOOQ query the preview executes:
`AggregateRefreshService.preview` calls `FinalSettings.render(sample.getSQL(INLINED), settings, …)`, and the
Class-1 arm calls `PipelineReadBudget.render` in `MaterializeService.preview`. That query exists to sample.
It joins enrichments physically, projects helper columns (`__latest_v`, `__latest_id`, `__total_groups`, and
`PREVIEW_VERSION`/`PREVIEW_IDENTITY` on Class-1), orders by the physical version column, and carries the
preview budget as `SETTINGS`. SQL declarations add `__c<i>` aliases (`AggregateSqlCompiler.POSITION_ALIAS`).

Most of what a portable statement needs already exists:

- A form declaration's parts are already query-model objects before they become jOOQ. Each group key is
  a `FieldExpr` or `FnExpr("date_trunc", …)` (`AggregateCompiler.groupExpr`), each measure is
  `FnExpr(fn, distinct, [FieldExpr])`, and the `filter` and each measure's `where` are parsed into a
  `FilterNode` by `FilterSqlValidator`. Only the measure `where` skips the model: it goes straight to a
  jOOQ `CASE`.
- An SQL declaration's block is a `StructuredQuery` already (`analysis.plan().translation().query()`).
- A Class-1 output's expression is analysed by `ExprSqlValidator` into the same `Expr` model.
- `StructuredQueryToSqlRenderer` renders a validated `StructuredQuery` as catalog-level SQL that reads back
  through `SqlQueryFrontEnd`. `QueryTranslationService.toSql` pairs it with a `StructuredQueryBuilder`
  validation pass. The query model exposes enrichment columns as entity fields, so the execute path
  adds the joins itself.

## Goals / Non-Goals

**Goals:**

- `statement` and `query` that run unchanged on `execute-sql` and `execute`, and compute the
  declaration's values.
- One definition of "portable": the query path's own validation and renderer, with no parallel list of
  supported constructs.
- No new access: render against the caller's visibility, execute only through the governed path.

**Non-Goals:**

- Reproducing the sample's newest-first order, `total_rows`, or a Class-1 `from`/`to` window. All three
  read the physical version column, which the catalog doesn't expose.
- Exposing the version column to queries.
- Making a computed key whose output name matches an input field expressible in the DSL. That is a
  `structured-query` / `sql-query-translation` change of its own.
- An admin-only option to return the physical statement.
- Changing what the preview executes, the preview budget, or any note other than adding
  `statement_not_portable`.

## Decisions

### Build a `StructuredQuery` next to the jOOQ sample, then render it

Each preview arm gains a "portable query" built from the same analysed parts its sample compiles from.
In `AggregateRefreshService`, the sealed `Declaration` gets `StructuredQuery portableQuery(int limit)`:

- `FormDeclaration` assembles keys, measures, filter, `group_by`, a key `sort` and the page. A measure
  `where` becomes `FnExpr(fn, distinct, [CaseExpr([CaseBranch(where, operand)], null)])`, where the
  operand is the column or the literal `1`. This matches `AggregateCompiler.measureField`. To have the
  `FilterNode` in hand, `CompiledFilter` (or `translatedPredicate`) keeps the node next to the jOOQ
  `Condition`, so the filter is still parsed only once.
- `SqlDeclaration` takes the block with each select item aliased by its target column (not `__c<i>`),
  the front end's `group_by`, a key sort and the page.
- `MaterializeService.preview` builds the Class-1 row query: grain key, outputs by target column name,
  filter, a grain-key sort, and the page.

**Alternatives rejected:**

- *Rewriting the physical SQL (string or jOOQ tree).* That means a reverse mapping from physical names and
  joins to catalog names, which is fragile and duplicates the catalog model.
- *Rendering the stored declaration text.* That only exists for SQL declarations, where the stored text is
  an `INSERT`. It also isn't what ADAS runs: for example, the UTC pin is added by ADAS.
- *Building the jOOQ sample from the `StructuredQuery`* so only one object exists. The sample needs
  physical-only expressions (the version column, `argMax`, a window count) that the DSL can't express,
  so this would reverse the dependency for no gain.

### Validate and render through the query path's own code

A small `PortableQueryRenderer` (factored out of `QueryTranslationService.toSql`) does three things:

1. Resolves the entity with `CatalogQueryService.findModel(entity, TABLE_GET)` for the calling identity.
2. Runs `StructuredQueryBuilder.build`, discarding the result.
3. Renders with `StructuredQueryToSqlRenderer` and reads the SQL back through `SqlQueryFrontEnd.translate`,
   so a statement the front end would refuse is caught before it's returned.

Any `QueryValidationException` or `SqlTranslationException` becomes a `statement_not_portable` note, with
`statement` and `query` left null. The renderer never fails the preview.

**Why `TABLE_GET` and not the scan model:** the scan model (`ScanSourceMetadata.entityModel`) is resolved
for pipeline execution, with every column. Rendering against it could print a column the caller can't
see, and the response is the caller's. `TABLE_GET` is the bar `translate` uses, so a caller with only
that bar gets a statement they can read but must hold `TABLE_READ` to run. The `translate` endpoint
already works this way.

**Why read it back:** the renderer's own rejections cover inexpressible DSL. A computed key whose output
name matches an input field, though, is valid DSL that the builder and front end refuse only in
combination. Reading the SQL back makes the check "what `execute-sql` would accept", not "what the
renderer can print".

**Alternative rejected:** a hand-written list of portable shapes in the pipeline package. It would drift
from the front end, and the spec requires the query path's own check.

### Order by keys, limit to the preview rows

The sample's newest-first order reads the version column (`_ingested_at` on the seeded sources), which the
catalog doesn't expose. Ordering the portable statement by the group keys (or the grain key) makes it
deterministic, and when `total_rows ≤ preview-rows` it returns exactly the sampled groups.

**Alternatives rejected:**

- *Omitting the ORDER BY.* The execute path's default ordering would make two runs of the statement
  disagree.
- *Adding a visible `max(<timestamp>)` column.* Not every input has a catalog timestamp that tracks
  ingest order, and the column would be one `columns` doesn't list.

### `query` member next to `statement`

`query` lets the query builder open a preview without a `translate-sql` round trip. It is the front
end's reading of the rendered statement, not the document it was rendered from. The two are equivalent,
but only the reading is what `translate-sql` answers: it spells out the defaults (`final: true` on a
version-collapsing source, `nulls: last` on an ascending sort) and drops the alias of a plain field.
Returning the reading makes "statement and query describe the same query" exact rather than "equivalent",
and the read-back costs nothing extra because it already runs as the portability check. It's serialized
with the same Jackson mapping `SqlTranslationResponseDto` uses for its `query`.

### Physical statement only in logs

`SqlLog.LOG.debug("Pipeline '{}' preview statement: {}", …)` on both arms, matching the refresh statement
log in `AggregateRefreshService.runRefresh`. An admin-only response flag would add a privileged branch to
a response contract for a debugging need the log already meets.

## Risks / Trade-offs

- **Clients parsing the old ClickHouse text break.** The field keeps its name but its content changes.
  → Marked **BREAKING** in the proposal and the commit (`feat!`), and the OpenAPI description is updated.
  The old text was never part of a stable contract beyond "the statement ran".
- **A shape the query path refuses that the pipeline path accepts.** Examples are an output name that
  matches an input field, and an aggregate or function the pipeline compiler accepts but the query
  catalog doesn't. → `statement_not_portable` with the query path's message. Integration tests cover the
  known case, and a round-trip unit test covers every measure function the aggregate gate admits.
- **Rendering against `TABLE_GET` can refuse a caller the preview allowed.** In practice it can't: the
  sample already resolves the input as its caller at `TABLE_READ` (`ScanSourceMetadata.entityModel`), so
  a caller without it never reaches rendering. If that ever loosens, the refusal is caught and reported
  as `statement_not_portable` rather than turning into an error, so the preview's status codes stay as
  specified.
- **Equivalence drift.** The portable statement and the sample could compute different values: for
  example, if `date_trunc` stopped pinning UTC on one path, or a measure `CASE` rendered differently.
  → Both go through the same `ExprTranslator` in the execute path. An integration test runs preview and
  `execute-sql` on one fixture and compares the shared groups cell by cell.
- **Extra work per preview.** One builder pass and one parse, with no ClickHouse round trip; small next to
  the sample.

## Migration Plan

No data or configuration migration. Deploying the change switches the response content. Rolling back
restores the physical statement. Neither direction touches stored pipelines.

## Open Questions

None. Whether the query path runs an SQL declaration's measure aliased to an input field name
(`sum(total_tokens) AS prompt_tokens`) with its meaning was open; it does, and
`AggregateSqlDeclarationIntegrationTest` pins it.
