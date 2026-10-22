## 1. Parse

- [x] 1.1 Add a raw `offsetAfter(SqlParserPos)` to `SqlSourceRegion` that strips nothing.
- [x] 1.2 Add `SqlQueryFrontEnd.planInsert(sql, bar)` returning `SqlInsertPlan(target, columns, translation)`. It must:
  - strip the trailing `;`;
  - refuse a `FORMAT` token before parsing;
  - call `SqlFinalModifier.rewrite`, then `parseStmtList`;
  - require exactly one `SqlInsert`, a non-null column list and a query source;
  - cut the raw text just past the column-list `)`;
  - pass the cut to `plan()`;
  - refuse `SqlMetadataResult` / `SqlCompositeTranslation`.
- [x] 1.3 Unit tests for `planInsert`:
  - plain source, CTE source, parenthesized `(WITH …)` and ORDER BY-wrapped sources
  - FINAL / `'--'` / `'x)'` literal cuts
  - positional `GROUP BY 1` and `GROUP BY 2, 1`
  - more than one statement, no column list, `VALUES`, composite and metadata (all refused)
  - error text for `SETTINGS`, backticks and `FORMAT`
- [x] 1.4 Move or widen `ExprColumnCollector` so `service/pipeline` can use it. Add a walker that counts
  `EntityRelation` occurrences.

## 2. Wire, mapper and storage

- [x] 2.1 Add nullable `sql` to `AggregateSpec`, `CreatePipelineRequest`, `PatchPipelineRequest` and `PipelineDto`. Put the authoring rules in its `@Schema`.
- [x] 2.2 In `PipelineMapper`:
  - add `sql` to `AGGREGATE_ONLY`;
  - make `sql` exclusive with `group_by`/`measures`, and separately with `filter` (aggregate kind), each a 422 naming the field;
  - make `inputs` optional, and if given require it to equal the parsed source;
  - require the INSERT target to equal `target`.
- [x] 2.3 PATCH mode switching:
  - `sql` clears `group_by`/`measures`/`filter`;
  - `group_by`/`measures` clear `sql`;
  - a patch with no declaration fields carries `sql` over.
  - Also add `sql` to `PatchPipelineRequest.hasDeclaration`, `PipelineMapper.requireComplete` and `PipelineService.missingAuthoredMembers`.
- [x] 2.4 Drafts:
  - In SQL mode, `deriveBestEffort` and `validate()`'s `deriveGroupKeys` must skip group-key derivation.
  - Derive `input_table` best-effort from the parse; if the SQL doesn't parse, store the text with a null `input_table` and never throw.
  - Unparseable `sql` counts as incomplete.
- [x] 2.5 A changed `sql` is a re-declaration: generation bump and `resetCursor`, and a 409 while the pipeline is enabled.
- [x] 2.6 `@WebMvcTest` for: `sql` + `group_by` → 422, `sql` + `filter` → 422, target mismatch → 422, unknown field → 400.
- [x] 2.7 Patch tests:
  - `PATCH {trigger}` on a paused SQL pipeline keeps `sql`;
  - switching both ways clears the other mode;
  - an unparseable draft is stored with a null `input_table`;
  - a disabled PATCH may not turn a complete SQL declaration incomplete.

## 3. Validation

- [x] 3.1 Extract the shared helpers from `AggregateSpecValidator`:
  - `validateInput`, `validateTarget`
  - `requireAggregatableEnrichments`
  - `enforceSensitive`
  - `requireSensitiveGrantsNotWidened`
  - `requireSensitivityPropagated`

  The form path's behaviour must stay unchanged; the existing tests are the check.
- [x] 3.2 Add `AggregateSqlValidator` covering the statement shape, sources, block shape and group keys:
  - **Statement shape:** target and column-list coverage, no `_updated_at`.
  - **Sources:**
    - one input, referenced once
    - no joins, set operations or subqueries
    - no `SELECT *` and no user-written `FINAL`
  - **Block shape:** single block, and an aggregation; no HAVING, DISTINCT, ORDER BY, LIMIT or windows.
  - **Group keys:**
    - matched by position;
    - the set must equal `ordering_key`;
    - no constant keys;
    - at most one bucket, and its step must be 1.
- [x] 3.3 Determinism walker over `QueryFunctionCatalog.isNonDeterministic` (select, filter and group_by).
- [x] 3.4 Type and nullability checks over `RelationColumn`, at form parity:
  - **Group keys:**
    - Nullable keys are refused; the 422 suggests `coalesce`.
    - An enrichment column under a key must be able to represent absence.
    - The key type must equal the target type exactly.
    - The bucket unit must fit the target.
  - **Measures:**
    - No fractional result into a non-decimal target.
    - Measure nullability follows the form rules.
    - Array element types must match.
- [x] 3.5 Sensitivity:
  - gate at the declarer's `TABLE_READ` bar;
  - a column that is hidden because it is sensitive returns 403;
  - lineage is checked from the inlined select expressions.
- [x] 3.6 Wire the validator at the arm and declaration gate, in preview, and in the refresh gate.
- [x] 3.7 One unit test per refusal. These must include:
  - a self-join via CTE
  - `SELECT *`
  - a nullable key
  - a non-nullable enrichment key
  - a bucket step n≠1
  - an hour bucket into a `date` column

## 4. Compile and refresh

- [x] 4.1 Add a public `StructuredQueryBuilder.build(..., LimitPolicy.NESTED)` overload that takes an extra leaf condition.
- [x] 4.2 Share `dirtyKeys`, `bucketBound` and `groupEnrichment` from `AggregateCompiler`. Dirty-key sub-selects must call `addReferenced` for enrichment keys.
- [x] 4.3 Add `AggregateSqlCompiler`:
  - render the block with the dirty-key restriction passed into `build`, so it is pushed into the enrichment grain join, including for an expression key such as `coalesce("enr.col", '')`;
  - run the grain join unrestricted on a tick whose dirty keys include the key's absence value (design D5);
  - pin `withFinalRead(false)`;
  - UTC-pin zone-less buckets;
  - prepend `INSERT INTO "target" (cols…, "_updated_at")` and append `now64(3)`.
- [x] 4.4 `AggregateRefreshService` SQL branch:
  - **Probe:** compile the block WHERE like `filter_sql` (`CompiledFilter.columns`) and use it as the filter.
  - **Write:** through `ClickHouseInsertSelectExecutor`.
  - **Measure helpers:** `valuePreservingMeasures`, `previewColumns`, the `noMatchingRows` wording and the measure-filter loop.
- [x] 4.5 Preview in SQL mode:
  - limit the sample to `aggregate.preview-rows`, under `PreviewBudget`;
  - inject the `__total_groups`/`__latest_v`/`__latest_id` helper columns;
  - `statement` stays the sample's own statement.
- [x] 4.6 Feed the block WHERE to `PipelineJsonReadNotes`.
- [x] 4.7 `AggregateSqlCompilerTest`, modelled on `AggregateCompilerTest`, asserting:
  - the dirty-key restriction and bucket bound are in the WHERE;
  - the enrichment join is keyed by the dirty keys;
  - no `LIMIT`, no `FINAL`;
  - `_updated_at` is appended.

## 5. Dependency guards

- [x] 5.1 Add an SQL branch to `AggregateSpecValidator.declaredFieldsOf` that unions three token sets:
  - `QualifiedColumnTokens.of`;
  - the lowercased contents of double-quoted identifiers;
  - `identifiers` of the text with the `INSERT INTO … (cols)` prefix stripped, falling back to the full text.

  Never parse. Update the tolerance comment at PGS:210-216.
- [x] 5.2 Make `referencedEnrichments` (the lenient one) and `referencedEnrichmentsOrThrow` work in SQL mode without parsing.
- [x] 5.3 Tests:
  - an `alias."enr.col"` reference is found;
  - a target column-list name does not block an input-column drop;
  - a schema patch dropping or renaming a column referenced only as a bare name in the SQL is refused with 409;
  - a table delete is refused when only the SQL references the enrichment.

## 6. Export / import

- [x] 6.1 Add `sql` to `BundlePipeline.Declaration` and to the import mapping, and bump `CatalogBundle.FORMAT_VERSION`.
- [x] 6.2 `CatalogImportPlanner`: `dependencies()` takes the SQL input table and enrichments, and `collectEnvSpecific` scans the SQL text.
- [x] 6.3 Round-trip test: `sql` comes back verbatim, import ordering uses the SQL input table, and an older-format reader refuses with the readable 422.

## 7. Integration and equivalence

- [x] 7.1 Lifecycle test: create → arm → refresh → append rows → refresh. The target must equal a full recompute, and a second tick must not change it.
- [x] 7.2 Integration tests for:
  - a CTE that flattens to one block;
  - an enrichment key with the clamp, using a `sessions_rollup`-shaped fixture with a `coalesce` key (synthetic, not a real deployment's declaration);
  - patching `sql` resets the cursor.
- [x] 7.3 Equivalence test: a form-declared pipeline and its SQL twin write identical rows, including a day bucket with no explicit time zone.

## 8. Docs and verification

- [x] 8.1 Confirm the `aggregate-pipeline`, `pipeline-registry` and `catalog-export-import` delta specs match what shipped. No `docs/configuration.md` change, since no property is added.
- [x] 8.2 `openspec validate aggregate-sql-declaration --strict`, plus a hand diff of each MODIFIED delta against its base.
- [x] 8.3 Note the `adas-mcp` OpenAPI snapshot refresh as a follow-up in that repo.
- [x] 8.4 `./gradlew clean build` passes.
