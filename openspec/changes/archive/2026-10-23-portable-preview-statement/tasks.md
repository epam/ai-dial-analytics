## 1. Portable rendering

- [x] 1.1 Factor `PortableQueryRenderer` out of `QueryTranslationService.toSql`. It resolves the entity with `CatalogQueryService.findModel(entity, TABLE_GET)`, runs a `StructuredQueryBuilder` pass, renders with `StructuredQueryToSqlRenderer`, and reads the SQL back through `SqlQueryFrontEnd.translate`. It returns `{sql, query}` or a refusal reason (plus the column at fault where one is known), and never throws for an inexpressible query. Move `QueryTranslationService.toSql` onto it without changing its behaviour.
- [x] 1.2 Add `STATEMENT_NOT_PORTABLE` (`statement_not_portable`, severity `info`) to `PreviewNoteCode`, and a `PreviewNotes.statementNotPortable(subject, reason)` factory.
- [x] 1.3 Add a nullable `StructuredQuery query` to `PipelinePreview` (Javadoc: `statement` is the portable ADAS-dialect `SELECT` and `query` is its DSL; both null together). Carry `query` through `PipelineResponses.PreviewDto` and `PipelineMapper`, using the serialization `SqlTranslationResponseDto` uses, and update the preview's OpenAPI description.

## 2. Aggregate preview

- [x] 2.1 Keep the parsed `FilterNode` beside the jOOQ condition for the pipeline `filter` and each measure `where` (extend `AggregateCompiler.CompiledFilter`, or return both from `translatedPredicate`), so neither predicate is parsed twice.
- [x] 2.2 Add `StructuredQuery portableQuery(int limit)` to the `Declaration` sealed interface. `FormDeclaration` builds keys (`FieldExpr` / `date_trunc`) under their output names, measures as `FnExpr` (a `where` becomes a `CaseExpr` operand, `THEN 1` for a column-less `count`), the filter, `group_by`, a key-ascending sort and `aggregate.preview-rows`.
- [x] 2.3 `SqlDeclaration.portableQuery`: the analysed block with each select item aliased by its target column (no `__c<i>`), its `group_by` as the front end produced it, the same sort and limit.
- [x] 2.4 In `AggregateRefreshService.preview`, render the portable query through 1.1 and return it as `statement`/`query`, or add `statement_not_portable` on refusal. Stop returning `FinalSettings.render(…)`, and log the physical statement with `SqlLog.LOG.debug("Pipeline '{}' preview statement: {}", …)`. Keep the executed sample, `sampleAliases`, `total_rows` and every other note as they are.
- [x] 2.5 Update the Javadoc that describes the returned statement: `AggregateRefreshService.preview`, `AggregateCompiler.compilePreview` and `AggregateSqlCompiler.compilePreview`/`block`.

## 3. Class-1 enrich preview

- [x] 3.1 In `MaterializeService.preview`, build the Class-1 portable row query: the grain key, each output's analysed `Expr` under its target column name, the filter `FilterNode`, a grain-key-ascending sort and the sample's row cap. Return it in `Class1Preview` next to the physical statement, which is logged at `DEBUG` rather than returned.
- [x] 3.2 `EnrichPreviewService.class1Preview`/`finish`: render through 1.1 and set `statement`/`query`, or add `statement_not_portable` with the output as `subject`. The `llm` and `group` arms stay `statement: null`, `query: null`.

## 4. Tests

- [x] 4.1 Unit: form portable query for a plain key, a `trunc` key, `count(*)`, `distinct`, `group_uniq_array`, a measure `where`, and an enrichment column in a key and in the filter. Each renders, reads back through `SqlQueryFrontEnd`, and contains no `SETTINGS`, `JOIN` or `__` alias.
- [x] 4.2 Unit: every measure function the aggregate gate admits renders and reads back (guards against the pipeline and query catalogs drifting apart).
- [x] 4.3 Unit: a `trunc` key written to a column named like an input field yields `statement_not_portable` with that `subject`, and a preview that still has its sample.
- [x] 4.4 Integration (`AggregatePipelineIntegrationTest`): preview a form declaration with at most `preview-rows` groups, post `statement` to `/v1/queries/execute-sql` and `query` to `/v1/queries/execute`, and compare the groups cell by cell (a `date` key compared as its UTC-midnight timestamp). Check that `translate-sql` on `statement` is equivalent to `query`.
- [x] 4.5 Integration (`AggregateSqlDeclarationIntegrationTest`): the same for an SQL declaration, including the `sum(total_tokens)` → `prompt_tokens` case. Resolve the design's open question here, and if the query path refuses that shape, change the spec scenario to `statement_not_portable`.
- [x] 4.6 Integration (`AggregateEnrichmentIntegrationTest`): a declaration reading an enrichment column. The statement names it by its exposed name, with no join, and returns the same groups.
- [x] 4.7 Integration (`EnrichPreviewIntegrationTest` / `Class1MaterializeIntegrationTest`): the Class-1 statement runs on `execute-sql`, and shared `event_id`s carry the same outputs. The `llm` preview still has no `statement` or `query`.
- [x] 4.8 Integration (`PreviewStatementAuthIntegrationTest`): a statement copied from a reader's preview to a caller holding `TABLE_GET` but no `TABLE_READ` is refused by `execute-sql` as an unknown relation (400 `bad_request`, the query API's rule). Preview already reads the input as its caller, so such a caller cannot preview at all, and a sensitive column the caller cannot read already refuses the preview (403), so neither can reach `statement`.
- [x] 4.9 Update existing tests that assert on the old ClickHouse text of the preview statement (`SETTINGS`, `toStartOfInterval`, physical names, `__c<i>` in the response). Keep the assertions on the executed sample, moving them to the debug log or the compiler unit tests.

## 5. Docs and specs

- [x] 5.1 Add a row to the current release upgrade doc under `docs/` for the preview change: `statement` is now ADAS-dialect SQL, `query` is added, and `statement_not_portable` is a new note code. Name the clients to update, such as a preview tool that showed the statement.
- [x] 5.2 No property is added, so `docs/configuration.md` is unchanged. Confirm the delta specs (`pipeline-registry`, `aggregate-pipeline`, `enrichment-registry`) match the shipped behaviour, including the result of 4.5.
- [x] 5.3 Run the public-repo hygiene sweep from `CLAUDE.md` (`git grep -nIP …` and the multiline pass) over the changed files.

## 6. Verify

- [x] 6.1 `openspec validate portable-preview-statement --strict`
- [x] 6.2 `./gradlew clean build` passes
