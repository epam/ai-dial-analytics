## 1. Column decision

- [ ] 1.1 Give `SensitiveColumnAccess.ColumnVisibility` three states per column — visible, masked with the allowing
  governing roles' `RowPolicy` filters, hidden — keeping `allows(column)` as "visible or masked", adding `masks(column)`
  and a strict `visible(column)`; computed fields follow "Column visibility by caller role" (masked when no input is
  hidden and one is masked); row-contributing roles (the rows-only roles whose policy enters the row filter, the set
  `RowPolicyFilter.confining` computes, shared as `readsTable` is) narrow the decision: a column such a role's list
  leaves out is masked, and its policy enters the mask condition of the columns it allows (tests: the rows-only
  scenario with `N`'s `columns` `null` and `["project"]`); `ColumnVisibility.and` and `visibleColumnsAtRevision` combine three states (hidden when
  either side hides, else masked when either masks); a column that would be masked is hidden when the governed table
  stores a column whose physical name equals its own name or an enrichment's name, or an enrichment stores a column
  named after any table of the entity (tests: a table `t` storing a column `t`, a source storing a column named after
  its enrichment, and an enrichment `e` storing a `Map` column `e`); `isAll()` means "nothing hidden or masked", and its callers
  (`SensitiveProjectionGuard.enforceVisible`, `TableCallerPermissions`, `TableCatalogService`, `CatalogExportService`,
  `ImportTargetReader`) are reviewed against that meaning
- [ ] 1.2 Update `SensitiveColumnAccessTest` to the modified scenarios (`confiningRolesIntersect`,
  `intersectionOfConfiningRoles`, `rowUnrestrictedRoleWidens`, `computedFieldsFollowTheirColumns`) and add cases for the
  `*` own-rows policy, a get-only caller and a sensitive column without the clearance staying hidden

## 2. Query model

- [ ] 2.1 Add `mask` and `absent` to `QueryFieldBinding`, rename `field()` to `physicalField()`, add `value()` —
  `if(mask, if(mask, f, defaultValueOfArgumentType(assumeNotNull(f))), NULL)` for a scalar, `if(mask, f, <empty>)` for
  a composite, a computed stand-in left unwrapped — and review every former `field()` call site, recording in the
  commit which read the physical name on purpose
- [ ] 2.2 ClickHouse test: over an absent scalar cell, a value-dependent failing expression (a `width_bucket` count
  computed from the cell) and a function reading the null's underlying value return the same result whatever the cell
  stores
- [ ] 2.3 In `CatalogQueryService.buildModel`, translate each masked column's filters against the governed table's
  physical bindings (the source's, for an enrichment column), binding caller values from the request's claims as
  `RowPolicyFilter.predicate` does rather than through the `CallerValues.resolving` scope (tests: a caller masked by an
  own-rows `*` policy succeeds on a DSL query, discovery and a pipeline preview), OR them and wrap the
  result `ifNull(…, 0)` into one condition shared per allowing-role set, and set `absent` by type (`NULL`, `[]`,
  `map()`, `'{}'::JSON`); keep the clearance check in `addColumn` first
- [ ] 2.4 Add `masked` to `QuerySchemaFieldDto` and set it in discovery; update the `@Schema` descriptions that go
  stale (`columns_restricted` "hide or mask", `StructuredQueryResultDto.rows` naming `$masked`, the
  `403 column_not_entitled` descriptions naming masked columns); regenerate `docs/openapi.json`
- [ ] 2.5 `CatalogQueryServiceTest`: replace `columnsHiddenByColumnPolicyLeaveTheModel` and
  `computedFieldsFollowTheColumnPolicyOfTheColumnsTheyReturn` with masked, hidden and computed cases

## 3. Statement building

- [ ] 3.1 Resolve every reference through `value()`: `ExprTranslator.resolveFieldOrNull`, the wildcard projection
  (aliased to `physicalField().getName()`, the key it emits today), object-path access (mask around the extracted subcolumn), `toJSONString` over an object,
  array expansion, windows, `having`, the count query
- [ ] 3.2 Keep the mask out of the enrichment derived table and apply it in the outer select; keep composite key
  reduction on stored keys (the join's `ON` compares masked outputs); keep the grain join on stored keys, translate its
  key sets through the same masked or rewritten conjunct forms as the outer filter, and transfer partition bounds from
  a masked partition field only for rewritten null-rejecting conjuncts
- [ ] 3.3 Project one flag per (condition, context) pair the outputs need — `NOT mask` per row, `max(NOT mask)` per
  group, `max(NOT mask) OVER W` for a row-mode window output and `max(max(NOT mask)) OVER W` for an aggregate-mode one,
  and for `row_number`/`rank`/`dense_rank` the same over `W`'s partition and order with `ROWS BETWEEN UNBOUNDED
  PRECEDING AND CURRENT ROW` whatever frame `W` declares (test: a ranking window with a one-row frame behind an absent
  row is marked) — record each output's flag set, and propagate a leaf's flags through composite blocks as flag-only bindings
- [ ] 3.4 Name the flag columns per statement so they differ from every output name of the query (`__adas_mask_<i>`,
  suffix advanced past a caller's output of that name); leave the shipped `__adas_` reservation unchanged; render each
  condition once per SELECT scope as `(ifNull(<or>, 0) AS __adas_mcond_<i>)` and reference the alias elsewhere,
  inside lambda bodies too, deciding definition versus reference while each scope renders (one `Condition` can render
  in two scopes): the definition at a reference outside every lambda body (its place in the text does not matter), or,
  in a scope that references the condition only inside lambdas, once per outermost lambda body; choose alias and flag
  names, and the composite operand aliases `CompositeQueryBuilder.nextAlias` generates, apart from every name the
  statement declares (outputs at every level, lambda parameters, CTE and derived-table aliases); refuse a query that
  references a masked field and carries a lambda parameter or any resolved output name (explicit, derived or generated,
  at every level) that equals a table it reads or begins with `<table>.`, except an output named after the field it
  projects bare (an enrichment field without `as`). ClickHouse tests on 26.3 and 26.8:
  - a lambda parameter named like an internal alias reads the absent value;
  - an output aliased as the table is refused;
  - an output `dial_usage_log.project_id` used as a `group_by` key with a `having` over a masked field, and as a sort
    key, is refused, while an enrichment field projected without `as` beside a masked field runs;
  - a wildcard over many masked columns under one large policy renders the policy text once per scope, including a
    filter copied into a key subquery and a first mask reference in a lambda capture;
  - a lambda reading 16 masked fields under a policy near 16 KiB, sixteen lambdas each reading one, and a filter-only
    lambda in a key subquery all run under the default `max_query_size` and `max_ast_elements`
- [ ] 3.5 Collapsing shapes:
  - a row-mode distinct projection with marks renders as `GROUP BY <every output>` in place of `DISTINCT`, each flag as
    `max(<flag>)`, a sort key that is not an output as `any(<key>)`, and the row flag (`NOT mask`) of each output that is
    a bare masked composite field as a further key;
  - an aggregate-mode distinct keeps `max(max(NOT mask)) OVER (PARTITION BY <every output>)`;
  - a DSL distinct projection with a window output is planned as an inner window block under an outer grouped block,
    as the SQL front end plans it (test that both surfaces agree);
  - a grouping key over a bare masked composite field also adds `NOT mask`, so an absent cell never collapses with a
    stored empty value;
  - a mark flag and a bare-absent flag per output position travel through `union all` branches, derived tables and
    CTEs, so `materialize` nulls a composite cell only where its branch output is a bare absent field (test:
    `execution_path` in one branch, `arrayConcat(execution_path, ['x'])` in the other); `union all` branches align with
    one flag per output position (constant `0` where none);
  - `union` (distinct) renders as that `union all` grouped by the outputs, with `max` of each flag, ordered by
    `min((branch ordinal, rowNumberInAllBlocks()))` over each branch as rendered (test the "A masked UNION DISTINCT keeps
    the last branch's order under the row limit" scenario);
  - benchmark the distinct shapes: low and high cardinality, with and without a sort, a JSON output;
  - a scalar subquery in an output expression returns `tuple(value, flag)`, with the flag under the subquery's own
    mode;
  - a flag propagated from a composite operand is wrapped `ifNull(flag, 0)`;
  - `intersect`/`except` whose operand output depends on a masked field is refused (`400 bad_request` naming the
    field)
- [ ] 3.6 Rewrite top-level conjuncts of a masked scalar field compared with any operand containing no subquery and
  not the null literal, nor an `in` list holding one (`eq`/`ne`/`lt`/`le`/`gt`/`ge`/`in`/string match), or tested
  `is not null` (`ne null`), to
  `mask AND <comparison on physicalField()>` with the operand reading masked values, in `FilterTranslator` for a
  block's `WHERE` filter only, so the main filter, the count, the key subqueries, composite key reduction and each
  composite leaf get it and `having`, `case` conditions, join `ON` and flag-only bindings do not; never rewrite `eq null`; the 3.9 equivalence test covers literal, relative-time and
  caller-value operands and `eq null`
- [ ] 3.7 Keep read-shaping for masked fields: copy a qualifying conjunct over the stored value into the enrichment's
  derived table while the outer `WHERE` keeps the masked or rewritten form (`<mask> AND "__adas_c<n>"` where the
  derived table computes it); push an allow-listed call over a masked heavy field and read the pushed column in the
  outer statement through the field's mask, with the call's value on an absent cell as the absent value (the scalar
  masked form for calls that return `NULL` over `NULL`, `''` for `json_extract_string_ci`, `[]` for
  `json_extract_array`), its references carrying the mask into the row's flags; allow a qualifying aligned-lookup
  conjunct over a masked enrichment field on the same grounds; transfer partition bounds from a masked partition
  column only for rewritten comparisons. Test that a masked `request_body ico 'error'` count projects `__adas_c0`, not
  `request_body`, and equals the unshaped statement's count
- [ ] 3.8 Rendered-SQL test: for each clause shape (projection, wildcard, filter rewritten and not, group, order,
  having, join, window, distinct, union, scalar subquery, array expansion, object path, count, composite leaf,
  enrichment column, the late-materialized page block and outer statement), every occurrence of a masked column's
  physical name is inside its mask or a rewritten conjunct guarded by it, outside an explicit allowlist of the readers
  that read stored values on purpose (the enrichment derived table's projection, the grain join's keys and partition,
  the page keys and partition, the key subqueries' and composite key reduction's selected key and partition columns,
  but not their conjuncts, the reduced operand's own key in `reducedBinding IN (…)`, the aligned lookup's partition
  pre-selection `partitionExpr(source.p) IN PS`, copied conjuncts and pushed calls inside an enrichment's derived
  table, and a masked computed field's unwrapped stand-in, whose value the completer nulls from the row's flag)
- [ ] 3.9 Rewrite-equivalence test on ClickHouse: for every rewritten operator, the rewritten and unrewritten forms
  return the same rows, including stored `NULL`s and absent cells, under `transform_null_in = 0` and `1`, with an `in`
  list holding a null item among the cases (left unrewritten)

## 4. Results

- [ ] 4.1 As a step of its own after `materialize` and `complete` (`StructuredQueryExecutor`, before the
  `QueryResultPage(outputColumns(select), …)` and in `run()`), build `$masked` per row from the flags and each output's
  condition set (projection order, member omitted when empty, any flag value but `0` counting as absent), null a bare
  composite masked cell, and drop the flag columns from the columns and the row maps
- [ ] 4.2 Null computed fields on rows whose flag is not `0`, and skip their completion read there
  (`ComputedFieldCompleter`, `RequestChainReader`), running the completion before the flag columns are stripped from
  the rows and from `outputColumns(select)`
- [ ] 4.3 Refuse a top-level output column named `$masked` (`400 bad_request`; Flight `INVALID_ARGUMENT`) on the
  statement's top-level output names only — a single-entity statement's and a composite plan's outermost block's, not
  in the shared `OutputColumnNaming.resolve` — in the DSL path and in `SqlQueryFrontEnd.plan` before its
  `INFORMATION_SCHEMA` branch, with tests for the DSL alias, the SQL alias, an `INFORMATION_SCHEMA` alias, translation,
  Flight and `validate-sql` (`valid: false`), that `$Masked` is accepted, and that a derived-table column named
  `$masked` executes
- [ ] 4.4 Place the masked-output refusals (`$masked` name, `intersect`/`except`, a table-named name) where every surface
  resolves the query: the SQL front end's plan step (`SqlQueryFrontEnd.plan`, which
  `validate-sql`, `sql` saved-query and inline-widget validation through `SavedQueryBodyResolver`, and `execute-sql`
  share) and the DSL builder (which `execute`, `translate` and `query`-kind saved-query and dashboard validation
  share); test each refusal on `execute-sql` and on save
  for a `sql` saved query, a `query` saved query and an inline widget

## 5. Other consumers

- [ ] 5.1 `TableCallerPermissions`: `columns_restricted` is true when a column is hidden or masked
  (`TableCallerPermissionsTest`)
- [ ] 5.2 `TableMapper`, `TableCatalogService`, `TableSchemaService`, `CatalogExportService`, `ImportTargetReader`
  treat a masked column as visible, except that `TableSchemaService` refuses `403 column_not_entitled` a `ttl.column`
  naming a masked column (before `TableTtlRules.requireValid`, keyed on the physical name) and any `ttl` member while
  the stored TTL is on one; update `TableMapperColumnVisibilityTest`, `TableCatalogServiceTest`,
  `TableSchemaServiceTest`, `CatalogTransferAuthIntegrationTest` (an export with a masked column succeeds; a
  hidden one is still refused) and `TableGrantRevisionIntegrationTest` (an older revision lists a masked column; a
  system table's snapshot reports `columns_restricted` for a masked-only caller)
- [ ] 5.3 `SensitiveProjectionGuard` refuses a masked column as a hidden one on every pipeline path, before the
  clearance and after the `row_policy_confined` check, while the unplannable-statement mention check in
  `AggregateSqlValidator` keeps judging hidden columns only (a statement mentioning only a masked column keeps its
  `422`); extend `SensitiveProjectionGuardTest` and
  `ColumnPolicyPipelineIntegrationTest` with the "Pipeline paths refuse a masked column" scenarios (an unconfined
  caller with a masked column, and a confined one refused for its confinement first)
- [ ] 5.4 Withhold the `enrichment_behind` note's instants when the read source's `version_column` is masked: gate
  `AggregateRefreshService` (`model.exposesSourceColumn`), `MaterializeService` and `EnrichPreviewService`
  (`versionVisible`) on the strict `visible(column)`, and check every other disclosure keyed on "the column is in the
  caller's model" the same way

## 6. Integration tests

- [ ] 6.1 Update `ColumnPolicyReadIntegrationTest` to the modified "Column visibility by caller role" scenarios
  (masked columns listed and absent per row, the `*` own-rows case with a known and an unknown caller value, a policy
  over a column that is null on a row, get-only caller, clearance still hides)
- [ ] 6.2 New masked-query integration test over DSL, SQL, CSV and Flight: the "A masked field reads as absent at every
  reference", "Masked inputs are reported per output cell", "Set operations over a masked output", "Response
  envelope" and surface scenarios, each checked against a reference built from the entity's flattened rows (enrichments
  joined on stored keys) with each absent cell then replaced by its absent value; include an unknown caller value and
  a null policy column, with a computed field, so a row whose mask is unknown is marked and its computed value is null
- [ ] 6.3 Pruning test: a rewritten key-column filter reads no more rows (`system.query_log` `read_rows`) than the same
  filter for a caller allowed the column
- [ ] 6.4 Schema discovery and Flight metadata: masked fields listed, `masked` flag set (`FlightMetadataAuthIntegrationTest`
  `columnPolicyHidesColumnsOnEveryFlightChannel` keeps the hidden case)

## 7. Docs

- [ ] 7.1 `docs/row-level-security.md`: rewrite "Column policies" for visible, masked and hidden columns, `$masked`, partial
  aggregates, expanding an absent array (no element, no mark), CSV and Flight nulls, the pipeline refusal, the
  discovery `masked` flag, `columns_restricted` set by a masked column too, the `intersect`/`except` and table-named
  name refusals (equal to a table, or beginning with `<table>.`), the hiding fallback on a table with a column named
  after it, the `403` for a TTL over a masked column, the per-row disclosure of a policy over a sensitive column, the
  accepted cost channel of a pruned filter or copied conjunct on a masked column (and that repeated probes can recover a
  single row's value a caller's filter isolates in a granule), the cost of a sort on a masked leading ordering-key
  column (no read in key order on a raw read), rows-only entries' `columns` narrowing the rows they add, the accepted
  row-order disclosure of masked ordering-key and partition columns
  with how an administrator avoids it, and an enrichment value disclosing its absent key (allow the key wherever its
  enrichment's columns are allowed)
- [ ] 7.2 Upgrade notes of the release that ships the change: the behaviour switch, `$masked` and the reserved name,
  clients that render every row member, wildcard `intersect`/`except` and wildcard-beside-a-table-named-alias
  statements now refused for masked callers, rows-only entries with a `columns` list now narrowing the rows they add,
  rollback
- [ ] 7.3 `docs/sql-reference.md`: the `$masked` alias refusal and the table-named alias refusal in the alias rules, the
  `intersect`/`except` refusal over a masked output in the set-operation section, including the wildcard case
- [ ] 7.4 Check the Infinity panels in `grafana/dashboards/` for a stray `$masked` column (declare explicit columns
  where a panel renders every row member) and note `$masked` in `docs/grafana-dashboards.md`

## 8. Verification

- [ ] 8.1 `openspec validate add-per-cell-column-masking --strict` and `openspec validate --specs --strict`; at archive,
  ADDED requirements go before the `## Implementation notes` header of each spec that has one (structured-query,
  query-schema-discovery, flight-sql-endpoint)
- [ ] 8.2 `./gradlew clean build`
