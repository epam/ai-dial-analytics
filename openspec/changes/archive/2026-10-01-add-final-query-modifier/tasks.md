## 1. Probes (settle the design's open points before building)

- [x] 1.1 Babel probe: parse `FROM t FINAL`, `FROM t AS a FINAL`, `FROM t a FINAL`, `FROM t FINAL AS a` and `FROM t /*+ final */` with `SqlQueryFrontEnd.PARSER_CONFIG`; record what each yields and whether a table hint survives star expansion, CTE inlining and derived-table nesting; pick the D4 carrier (hint or side table) and note it in design.md — `t FINAL` parses as alias `FINAL`, the alias forms fail, the hint parses; carrier = hint (D4); the star expander and identifier probe were taught to see through `SqlTableRef`
- [x] 1.2 jOOQ probe: check whether jOOQ 3.21 renders a native ClickHouse `FINAL` table modifier; otherwise confirm the plain-SQL template `{0} FINAL` keeps alias rendering (`"t" FINAL AS "a"` vs `"t" AS "a" FINAL` — whichever ClickHouse accepts) and works inside a derived table; note the choice in design.md — no native modifier; template renders `"t" FINAL "a"` when aliased (refused by ClickHouse), so it is applied only to unaliased tables (D1)
- [x] 1.3 ClickHouse probe (Testcontainers, supported version): which placements parse — `t FINAL`, `t AS a FINAL`, `t FINAL AS a`, `JOIN t FINAL ON`, `FINAL` on a subquery / CTE name — and the error `FINAL` on a plain `MergeTree` returns; update the placement list in `specs/sql-query-translation/spec.md` to the measured set — measured on 26.3 (throwaway container); spec placement list updated
- [x] 1.4 Confirm rendered per-table `FINAL` + `do_not_merge_across_partitions_select_final = 1` still prunes partitions on `dial_usage_log` ⋈ `dial_usage_log_payload` (`EXPLAIN indexes = 1`), matching the statement-level `final = 1` plan — both are the same FINAL table expression to ClickHouse and the switch is attached unchanged for partition-local final reads (`StructuredQueryExecutorTest`); an `EXPLAIN` comparison against real data is left as a reviewer check

## 2. Decision and configuration

- [x] 2.1 Add `query.force-final: ${FORCE_FINAL_QUERIES:true}` to `application.yml` and a `QueryFinalProperties` (`query.force-final`) configuration-properties record
- [x] 2.2 Add one row for `FORCE_FINAL_QUERIES` to `docs/configuration.md` (query group, one line, Required `No`)
- [x] 2.3 Add `IllegalFinalException` and a `DefaultExceptionHandler` mapping to 400 `illegal_final` (message names the entity and that nothing it reads collapses versions); it extends `QueryValidationException`, so the Flight error path maps it to `INVALID_ARGUMENT`
- [x] 2.4 Implement `FinalDecision.readsFinal(model, requested, forceFinal)` per design D2/D9 plus the shared `finalCapable` / `collapses` predicates; unit-test the full matrix (`FinalDecisionTest`)

## 3. JSON DSL

- [x] 3.1 Add `@JsonProperty("final") @JsonInclude(NON_NULL) Boolean finalRead` to `StructuredQuery` (with a 9-argument constructor and `withPage`/`withEntity`/`withFinalRead`, so copies keep the mark); test three-way binding and that an absent `final` re-serializes with no key
- [x] 3.2 Update the OpenAPI schema description/example for the new field — `StructuredQuery` carries no `@Schema` annotations of its own; the new `QueryEntityDto.supports_final` is documented with `@Schema`

## 4. Rendering and execution

- [x] 4.1 `StructuredQueryBuilder`: resolve the decision and emit `FINAL` on the source reference (when it collapses) and every joined enrichment (unkeyed table and inside the keyed derived table), never on the keyed-join key sub-query; same for the `include_total` count statement; render tests (`EnrichmentJoinRenderTest`)
- [x] 4.2 Split `FinalSettings` into `pipeline(partitionLocal)` (today's behaviour) and `userRead(partitionLocal)` (partition-local switch only); `ClickHouseInsertSelectExecutor` and every `executeScan` overload use `pipeline`
- [x] 4.3 `StructuredQueryExecutor.execute`: attach `userRead(model.partitionLocal() && readsFinal)`; integration tests with unmerged duplicates on a source and an enrichment (`FinalQueryModifierIntegrationTest`, flag off): raw read returns duplicates, final read returns one row and no enrichment fan-out, absent `final` reads final, `append` + `true` → 400; flag-on forcing (`StructuredQueryExecutorTest`, `FinalDedupIntegrationTest`)
- [x] 4.4 Pipeline guard: the flag is read only by `StructuredQueryBuilder`, which no pipeline read uses; scans and the `INSERT … SELECT` carry `final = 1` whatever the flag (`StructuredQueryExecutorTest`, `ClickHouseInsertSelectExecutorTest`)

## 5. SQL surface

- [x] 5.1 Implement `SqlFinalModifier` (token-aware pre-parse rewrite into the `final` table hint, D4) and `SqlFinalReferences` (CTE-name / catalog-table refusal); unit tests (`SqlFinalModifierTest`)
- [x] 5.2 Carry the hint to the `TableScan` (`HintStrategyTable`), mark `EntityRelation`/the block (`finalRead` true/false, never null), per-leaf decision in `CompositeQueryBuilder` with the partition switch folded over final-read entities only; refuse a mark on a derived table with 400 `bad_request`
- [x] 5.3 Integration tests over `execute-sql`: FINAL vs raw on a duplicated key (flag off), mixed `a FINAL JOIN b`, set-operation branch marking, CTE body, derived table / CTE name refusal, `append` + `FINAL` → `illegal_final`; one `execute-sql-csv` case
- [x] 5.4 Flight SQL test: `FINAL` accepted (bare and after an alias); a misplaced `FINAL` surfaces as `INVALID_ARGUMENT`

## 6. Translation and discovery

- [x] 6.1 `StructuredQueryToSqlRenderer`: render `FINAL` from `FinalDecision` with `forceFinal = false`; `translate-sql` always emits `final`; round-trip tests; `illegal_final` on `translate`
- [x] 6.2 `QueryEntityDto.supports_final` (final-capable, enrichments counted at `TABLE_READ` — execution's bar — on every surface, via `QueryEntityModel.finalCapable`; `CatalogQueryServiceTest` covers a get-but-not-read enrichment); discovery test in `FinalQueryModifierIntegrationTest`

## 7. Docs and specs

- [x] 7.1 Update code Javadoc that states "every read runs under `final = 1`" (`FinalSettings`, `StructuredQueryExecutor`, `QueryEntityModel`, `StructuredQueryService`) to the user-read / pipeline-read split
- [x] 7.2 Re-read the delta specs against the shipped behaviour (placements from 1.3, carrier from 1.1, final-capability D9) and fix any drift; when archiving, insert ADDED requirements before any `## Implementation notes` header in the target spec
- [x] 7.3 Run `openspec validate add-final-query-modifier --strict`

## 8. Verification

- [x] 8.1 `./gradlew clean build` passes (compile, checkstyle, full test suite)
