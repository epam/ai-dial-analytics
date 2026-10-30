Paths below are relative to `src/main/java/com/epam/aidial/analytics/` (production) and
`src/test/java/com/epam/aidial/analytics/` (tests) unless they start with a repository directory.

## 1. Production rebuild (no I/O)

- [x] 1.1 Add `sync/delta/RequestBodyRebuild.java`: a pure function from a target row (stored body, parent path,
      cache markers) and an in-memory `path → ancestor row (event_id, path, parent, body)` map to an outcome
      (`STORED`, `REBUILT`, `PARTIAL` + reason `MISSING`/`INVALID`) and the body text. Port the logic of the test
      `sync/delta/Rebuild.java` (design D5): iterative walk with a hop bound, root history from `ChatRequest.parse(…)
      .canonicalHistory()`, descendants' message elements appended, system keys and `tools` from the root, own markers
      restored, output written with `ChatRequest.canonical`. `STORED`/`PARTIAL` return the stored text unchanged. Reuse
      `ChatRequest.MESSAGE_ARRAY_KEYS`, `SYSTEM_KEYS`, `TOOLS` and `RequestDeltaEncoder.NO_PARENT`; widen visibility
      only as far as needed.
- [x] 1.2 Add `sync/delta/RequestBodyRebuildTest.java`: follow-up turn, Anthropic `system`, Gemini
      `system_instruction` + `contents`, `input` message array, inherited `tools`, explicit `"tools": null` root, root
      with markers, child with markers (own markers only), missing ancestor → `PARTIAL/MISSING` with stored text, root
      that is not a chat request → `PARTIAL/INVALID`, marker index outside the history → `PARTIAL/INVALID`, decimals and
      lone surrogates survive, body stored whole → `STORED` byte for byte.
- [x] 1.3 Turn `sync/delta/Rebuild.java` (test) into a thin adapter over `RequestBodyRebuild`, so
      `RequestDeltaEncoderTest` and `RequestDeltaIntegrationTest` check ingest against the production rebuild; keep
      their assertions unchanged and green.

## 2. Configuration

- [x] 2.1 Add `configuration/RequestBodyRebuildProperties.java` (`@ConfigurationProperties("query.request-body-rebuild")`,
      `@Validated`, `maxRounds` default `8`, `maxAncestors` default `20000`, each `@Min(1)`), register it like
      `QueryBudgetProperties`, and add the two bare lines to `src/main/resources/application.yml` under `query:`
      (`max-rounds: ${QUERY_REQUEST_BODY_REBUILD_MAX_ROUNDS:8}`, `max-ancestors: ${QUERY_REQUEST_BODY_REBUILD_MAX_ANCESTORS:20000}`),
      no comments.
- [x] 2.2 Test that `0` for either setting fails context startup naming the property.
- [x] 2.3 Add a "Request body rebuild" table under "Query surface" in `docs/configuration.md`: one row per variable,
      one-line description, `Required` = `No`.

## 3. Computed fields in the query model

- [x] 3.1 Add `service/query/ComputedFields.java` (registry) and `ComputedField` (descriptor: name, type, enum values,
      stand-in factory, physical reads). Define `request_body_full` (`string`) and `request_body_rebuild` (`enum`
      `stored`/`rebuilt`/`partial`) for `dial_usage_log_payload`, each producing a transient, never-persisted
      `ColumnMappingEntity` (`sensitive` when the table's `request_body` or `request_cache_control` row is, `heavy` true, `nullable` true,
      fixed description and display name). Present only when the `request_body` row exists (design D1).
- [x] 3.2 Extend `service/query/QueryFieldBinding.java` with `computed` (nullable descriptor) and `reads` (physical
      fields; `[field]` for catalog bindings), keeping the existing constructors' behavior.
- [x] 3.3 Build the stand-ins (design D2) as a dedicated jOOQ field type recognizable after alias unwrapping:
      `tuple(request_body, request_parent_hash, request_ancestor_hashes, request_cache_control, toYYYYMMDD(request_time))`
      for the body and, for the status, the same tuple with the two text columns read through their `.null` subcolumns
      (and the grain key guarding an unmatched join row) — `isNull(...)` read the whole body under `FINAL` when
      measured (design D2) — columns qualified by `dial_usage_log_payload`, UUIDs and the day cast to stable types.
- [x] 3.4 In `service/query/CatalogQueryService.buildModel`, append the computed fields after the payload
      enrichment's catalog columns through `addColumn` (same sensitive gate), add them to the enrichment's exposed
      set, and leave `tableBindings` (row policies) without them.
- [x] 3.5 In `service/query/EnrichmentJoins.derived`, project `binding.reads()` for a computed binding; make the
      object-path pruning (`ObjectPathUsage`) and the heavy-text scalar shaping (`ReadShaping` / `Shaping`) skip
      computed bindings. Treat both computed fields as heavy for late materialization.
- [x] 3.6 Add `projection_only` to `web/dto/query/QuerySchemaFieldDto.java` (false for catalog columns; a computed
      field's `source` is its own name) and update every constructor call site. (Shipped with a ten-argument
      convenience constructor defaulting the flag to `false`, so only `CatalogQueryService` passes it.)
- [x] 3.7 Tests: `service/query/CatalogQueryServiceTest` (fields present after the payload's columns, absent without
      sensitive clearance, absent from `tableBindings`), `service/query/EnrichmentJoinsPruningTest` /
      `EnrichmentJoinsRestrictionTest` (derived table projects the five physical columns),
      `service/query/translate/EnrichmentJoinRenderTest` (stand-in SQL, late-materialized shape keeps the stand-in in
      the outer statement and out of the page block). (The join and render cases shipped together in
      `service/query/translate/ComputedFieldQueryTest`.)

## 4. Projection-only enforcement

- [x] 4.1 DSL: in `service/query/translate/StructuredQueryBuilder` (and the translators it delegates to:
      `ExprTranslator`, `FilterTranslator`, `SubqueryRenderer`), accept a computed binding only as a bare row-mode
      `select` field in a non-`distinct` query; reject every other use, a `sort` on its output name, and an `__adas_`
      output name with `QueryValidationException` using one shared message ("… is projection-only: it is computed after
      the query runs and can only be selected").
- [x] 4.2 SQL: confirm single-entity statements reach 4.1 through `RelToStructuredQueryTranslator`; in
      `service/query/translate/CompositeQueryBuilder` / `service/query/sql/SqlCompositeTranslation` refuse a computed
      binding anywhere in a composite plan with the same 400.
- [x] 4.3 Tests: `service/query/translate/QueryTranslatorValidationTest` (filter, `is_null`, sort, sort by alias,
      group_by, having, function argument, `case`, window, `array_join`, aggregate mode, `distinct`, `in` subquery,
      `__adas_` alias; bare projection and `as` accepted), `service/query/sql/SqlQueryFrontEndTest` and
      `SqlCompositeTranslationTest` (WHERE, ORDER BY by name/alias/ordinal, GROUP BY, DISTINCT, function, join, derived
      table, CTE, set operation, `SELECT *` excludes them), `service/query/QueryTranslationServiceTest` (DSL↔SQL round
      trip of a projection; sort refused). (Shipped as `service/query/translate/ComputedFieldQueryTest` for the DSL
      cases and, for the SQL, composite and translation cases, in `integration/RequestBodyRebuildIntegrationTest`
      against the real front end and catalog.)

## 5. Rebuild read and completion

- [x] 5.1 Add `service/query/rebuild/RequestChainReader.java`: phase 1 and phase 2 statements of design D4 on
      `ClickHouseClientWrapper`, `FINAL` rendered, `final = 1` and `do_not_merge_across_partitions_select_final = 1`,
      day bounds as in `sync/delta/ClickHouseParentLookup`, lists bound as server-side comma-separated parameters in
      chunks of 1000, statement text public for an `EXPLAIN` test.
- [x] 5.2 Add `service/query/rebuild/ComputedFieldCompleter.java`: reads stand-in cells positionally (one adapter for
      the client's tuple mapping), skips all-zero-parent rows, runs rounds of phase 1 under `max-rounds` and
      `max-ancestors`, runs phase 2 only when `request_body_full` is projected, re-walks on phase-2 links (re-ingest
      race), calls `RequestBodyRebuild`, writes `request_body_full` / `request_body_rebuild` cells, maps statement
      failures (budget breach included) to `partial`/`failed` for the rows of the failed statement's day only, issues
      no statement once less than a second of the read's ceiling is left (those rows `partial`/`failed`), and records `query.request.rebuild.rows{outcome,surface}`,
      `query.request.rebuild.partial{reason}`, one WARN line per read with partial rows (counts, rounds; no body or hash)
      and a DEBUG line.
- [x] 5.3 Hook the completer into `service/query/repository/StructuredQueryExecutor` after the read's own statements
      (in `execute`, after the `include_total` count): run it when the select projects a stand-in, under the
      triggering statement's settings with the remaining wall-clock (`ClickHouseBudget.remainingTimeIfAny`, `null` once
      under a second is left), `surface=query` for `execute`, `surface=pipeline` for `executeScan`; assert in
      `executeComposite` that no stand-in is projected. A tagged pipeline read (`PipelineReadBudget.run`, the aggregate
      preview) passes its `StatementTag` to `executeScan`, and each rebuild statement carries a fresh
      `adas-{pipeline}-{stage}-rebuild-{uuid}` id (MDC untouched); tested in `StructuredQueryExecutorTest`.
- [x] 5.4 Unit tests `service/query/rebuild/ComputedFieldCompleterTest` with a stubbed client: all-`stored` page issues
      no statement; round seeding from hints and from found rows' hints; `max-rounds` and `max-ancestors` → `limit`;
      statement exception → `failed` for its day only; spent budget → no statement, `failed`; phase-2 link mismatch → re-resolve or `partial`; status-only projection issues no
      phase-2 statement; chunking at 1000; `NULL` body → both `NULL`, and an all-`NULL` tuple (an unmatched row under
      `join_use_nulls = 1`) → both `NULL`; metrics and the WARN line.

## 6. Pipeline data plane and declarations

- [x] 6.1 Add `computed` to `service/enrichment/support/EntityFields.Field`; have `EntityFieldResolver` append the
      computed fields (from `ComputedFields`) wherever it appends the payload enrichment's columns.
- [x] 6.2 Accept a computed field in `transform.inputs` resolution (`service/enrichment/validation/EnrichSpecValidator`)
      under the existing `SensitiveProjectionGuard` check; refuse it with `PipelineValidationException` in
      `FilterSqlValidator` (filter, `ready_when.signal`, `prefer_sql`, `sql` outputs), `member_select.order_by` and
      `trigger.group_by` validation, and in `service/pipeline/AggregateSpecValidator` / `AggregateSqlValidator`.
- [x] 6.3 Confirm `ScanService`, `GroupFetchService.projectMembers`/`readMembers` and `EnrichPreviewService` project
      the binding through `binding.field()` (so the executor completes it), that the computed fields of one
      enrichment together count as one heavy column and share one group-projection statement, that the selection
      phase never projects it, and that the group byte cap is applied after completion.
- [x] 6.4 Tests: `EnrichSpecValidator` / `FilterSqlValidator` / aggregate validator unit tests for each accept/refuse
      case of the enrichment-registry and aggregate-pipeline deltas. (Shipped as `SourceScopeGuardTest` — the one guard
      every governed fragment passes — and `AggregateSpecValidatorTest`; the enrich declaration's accept, `order_by`
      and `filter` cases run end to end in `integration/GroupEnrichmentIntegrationTest`.)
- [x] 6.5 Have `SensitiveProjectionGuard` judge an enrichment's computed fields by their `sensitive` flag (through
      `ComputedFields.withComputed`), so a non-entitled runner binding one gets the same 403 as for `request_body`;
      test in `SensitiveProjectionGuardTest`.
- [x] 6.6 Refuse a computed field outside `transform.inputs` on **every** declaration write, by name, in
      `service/pipeline/DeclaredComputedFields` (called from `PipelineService.apply`, so create, patch and import alike,
      a disabled draft and every aggregate registration included), scanning fragments outside their literals and
      comments (`QualifiedColumnTokens.referencedNames`); and have `GroupFetchService.prepare` and `ScanService.plan`
      refuse a stored `member_select.order_by` or `trigger.group_by` naming one with the same 422. Tests:
      `PipelineServiceTest` (aggregate form/SQL/filter drafts, an enrich `sql` output),
      `GroupEnrichmentIntegrationTest` (an enrich draft on create and patch), `GroupFetchServiceTest`,
      `ScanServiceTest`.
- [x] 6.7 Pin the declaration-side SHALLs the gate owns: `AggregateSqlValidatorTest` (an SQL declaration measuring
      the rebuilt body), `PipelineDataAuthorizationIntegrationTest` (a non-entitled author binding it, including the
      markers-only-sensitive case; a `type=sql` output reading it), and the surface tag of the rebuild counter in
      `StructuredQueryExecutorTest`.

## 7. Integration tests (ClickHouse Testcontainers)

- [x] 7.1 Add `integration/RequestBodyRebuildIntegrationTest.java` extending `AbstractClickHouseIntegrationTest`,
      writing rows through `RequestDeltaEncoder` and `UsageLogWriter` as `RequestDeltaIntegrationTest` does (plus the
      matching `dial_usage_log` rows), and covering: a three-turn chain read via `POST /v1/queries/execute` (trace/span/
      time filter, `limit 1`) equals the body as sent (`Rebuild.canonical`) with `rebuilt`, while `request_body` stays
      the delta; a row stored whole → `stored`, byte-identical, and no rebuild statement in `system.query_log`; a chain
      deeper than 128 → `rebuilt`; `MAX_ROUNDS=1` → `partial`/`limit`; a deleted parent row → `partial`/`missing`; a
      re-ingested parent re-linked to a new root still rebuilds exactly; rows from two days look up their own day only;
      expired payload → both `NULL`; tools inheritance and markers end to end.
- [x] 7.2 In the same test, read the same rows through `execute-sql`, `execute-csv`, `execute-sql-csv` and compare the
      values; assert the 400s of a filter, a sort and a join on the field.
- [x] 7.3 Cost checks: `EXPLAIN indexes = 1` of the phase-1 statement shows the partition and `idx_request_hash`
      pruning and of phase 2 the primary key; `system.query_log` shows the status-only projection reading no
      `request_body` bytes beyond its null map (on the shipped ClickHouse versions; if the null-map read does not hold,
      document the cost in the structured-query spec instead). (`isNull` did not hold under `FINAL`; the stand-in reads
      the `.null` subcolumn instead, which does — see 3.3 and design D2.)
- [x] 7.4 Extend `integration/FlightSqlIntegrationTest` with a statement selecting both fields: `Utf8` schema, values
      equal to `execute-sql`'s.
- [x] 7.5 Extend `integration/GroupEnrichmentIntegrationTest` (group-rows) and `integration/EnrichmentScanIntegrationTest`
      (row-grain scan) with a pipeline binding `dial_usage_log_payload.request_body_full`: the member/page row carries
      the rebuilt body, a partial member does not set `truncated`, the byte cap counts the rebuilt size. (The row-grain
      scan case shipped in `GroupEnrichmentIntegrationTest` beside its existing row-pipeline scan case.) The same class
      previews a row-grain and a group declaration binding both fields: completed values, and the preview columns typed
      `string` and `enum`.
- [x] 7.6 Extend `integration/QuerySchemaDiscoveryIntegrationTest`: both fields listed for an entitled caller with
      `projection_only: true`, hidden for a non-entitled one, `projection_only: false` on catalog fields; and assert
      `GET /v1/tables/dial_usage_log_payload` lists neither. (The non-entitled case is `CatalogQueryServiceTest`'s: this
      integration class runs with security off.)

## 8. Specs and docs

- [x] 8.1 `docs/release-0.4-upgrade.md`: in §6, extend the request-delta row's action to "re-point pipelines binding
      `dial_usage_log_payload.request_body` to `request_body_full` before enabling `delta`"; in §7, add the two new
      variables and the same instruction under `SYNC_REQUEST_BODY_MODE`; note the rollback order (design Migration
      Plan).
- [x] 8.2 `docs/heavy-columns.md` "Request bodies in delta form": describe `request_body_full` and
      `request_body_rebuild` with one example query, the projection-only rule and the `partial` meaning; link the
      data-sync spec.
- [x] 8.3 Check every touched file against the CLAUDE.md public-repo hygiene rules (no environment names, no
      real-data figures, invented ids only) with the PCRE and multiline sweeps CLAUDE.md gives.
- [ ] 8.4 When archiving, insert the new requirements of `structured-query`, `sql-query-translation`,
      `query-schema-discovery`, `flight-sql-endpoint` and `query-translation-endpoint` **before** each spec's
      `## Implementation notes` header; confirm the merged `data-sync` and `table-management` requirements match the
      shipped behavior.
- [x] 8.5 Run `openspec validate add-request-body-rebuild`.

## 9. Verification

- [ ] 9.1 Run `./gradlew clean build` and make it pass.
