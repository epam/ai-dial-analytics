## Why

`POST /v1/pipelines/{name}/preview` returns a `statement`, but it is the ClickHouse `SELECT` the service
ran: it names physical tables and the enrichment join plumbing, projects internal helper columns
(`__latest_v`, `__latest_id`, `__total_groups`, `__c<i>` aliases), uses engine-only functions
(`argMax`, `toStartOfInterval`) and ends in a `SETTINGS` clause. An author cannot paste it into the query
builder or send it to `POST /v1/queries/execute-sql`, so iterating on a pipeline means rewriting the
statement by hand against a physical schema the governed query surface otherwise hides.

## What Changes

- **BREAKING** The preview `statement` becomes a `SELECT` in the **ADAS SQL dialect**, the subset
  `POST /v1/queries/execute-sql` accepts. It names the catalog entity and its exposed fields (enrichment
  columns included, with no joins), aliases every column by its target column name, and carries no
  `SETTINGS`, no helper columns and no physical identifiers. This applies to the `aggregate` kind (form and
  SQL declarations) and to a Class-1 `sql` enrich pipeline. The `llm` arms still return no statement.
- A new optional response member **`query`** carries the same statement as a structured-query DSL
  document, accepted as is by `POST /v1/queries/execute`, so the query builder can open a preview without
  a `translate-sql` round trip.
- The portable statement reproduces the declaration (its keys, measures, filter, and each measure's
  `where` as a `CASE`), not the preview's sampling mechanics. It orders by the group keys (or, for Class-1, the grain key)
  rather than newest first, so two runs of it agree on a source that is still being written, and is limited
  to the preview row count; it carries no window count, so it does not reproduce `total_rows`. The spec states where its
  rows can differ from `rows`.
- When the declaration cannot be expressed in the ADAS dialect, `statement` and `query` are absent and the
  preview carries a new note **`statement_not_portable`** whose message gives the reason. The main case is
  a computed group key whose target column name is also a field of the input, which the SQL front end
  refuses as ambiguous. The preview itself still succeeds.
- The physical sampling statement is logged at `DEBUG` on the SQL log, as the refresh statement already
  is. No admin-only response option is added.
- The positional `__c<i>` alias rule for SQL declarations stays in the executed sample and no longer
  appears in any response.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `pipeline-registry`: "The dry-run preview answers in one shape for both kinds". `statement` is now the
  portable ADAS-dialect `SELECT`, a new `query` member is added, and the closed note set gains
  `statement_not_portable`.
- `aggregate-pipeline`: "Dry-run preview of an aggregate declaration" and "Preview of an SQL declaration
  samples the block". The `statement` contract changes from "the sample's own `SELECT`" to "the
  declaration's portable `SELECT`", and the `__c<i>` alias is no longer shown.
- `enrichment-registry`: "Dry-run preview of an enrich declaration". The Class-1 `statement` is the
  portable `SELECT` over the source entity rather than the physical sampling statement with its joins.

`class1-enrichment` describes no preview statement and needs no change. `rest-sql-endpoint`,
`structured-query` and `query-translation-endpoint` are unchanged: the preview reuses their renderer and
governance as they stand.

## Impact

- **API:** `POST /v1/pipelines/{name}/preview` response. `statement` changes content (breaking for any
  client that parsed the ClickHouse text), `query` is added, and the note enum gains
  `statement_not_portable`. The OpenAPI description of the preview response is updated.
- **Code:** `service/pipeline/AggregateRefreshService` (the `Declaration` interface and the preview),
  `AggregateCompiler` / `AggregateSqlCompiler` (the portable query alongside the jOOQ sample),
  `service/enrichment/MaterializeService` and `EnrichPreviewService` (Class-1), `service/pipeline/preview/*`
  (`PipelinePreview`, `PreviewNoteCode`, `PreviewNotes`), and the shared rendering factored out of
  `service/query/QueryTranslationService`. The web DTO and mapper for the preview also change.
- **Configuration:** no new property; `docs/configuration.md` is unchanged.
- **Security:** none added. Running the copied statement goes through the governed query path (table
  grants, sensitive-column clearance, row policies, query budget and limits). The statement is rendered
  against the caller's own column visibility.
