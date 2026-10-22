## 1. Response and request contract

- [x] 1.1 Replace `PipelineResponses.PreviewDto` with the flat shape `statement`, `columns`, `rows`,
      `total_rows` (aggregate only, `0` rather than null when empty), `group_key`, `notes`. Add
      `PreviewColumnDto` (`name`, `role`, `type`, `target_type`, `max_bytes`) and `PreviewNoteDto`
      (`code`, `severity`, `subject`, `message`). Delete `EnrichPreviewDto` and `PreviewOutputDto`.
- [x] 1.2 Add the `PreviewColumnRole`, `PreviewNoteCode` (the closed set from the pipeline-registry
      delta) and `PreviewNoteSeverity` enums. Each carries a lowercase `code` exposed through
      `@JsonValue`, like `QueryFieldType`, so the OpenAPI enumerates the wire values.
- [x] 1.3 Remove `cursor` from `PipelineRequests.PreviewRequest`, its class Javadoc and its schema text,
      and from `MaterializeService.preview`'s parameters. Rewrite the `group_key` schema text ("Required
      for one: ADAS has no group-discovery surface"): it is now optional, and the newest group is chosen
      without it. Check that a body carrying `cursor` is a 400 `bad_request` (via
      `StrictRequestJsonConfiguration`).
- [x] 1.4 Rewrite the preview mapping in `PipelineMapper` and `PipelineDataController.preview` for the
      new shape. Update the OpenAPI operation description, `NO_PREVIEW_BODY` and the aggregate
      body-refusal message (both name `cursor`), and declare the 403/422 `@ApiResponse`s preview can
      return.

## 2. Aggregate arm

- [x] 2.1 `AggregateRefreshService.preview`:
      - stop compiling and rendering `compileRefresh`;
      - return the sample statement, the typed columns, the rows and `total_rows`;
      - fix the stale comment at `AggregateRefreshService.java:268`.
- [x] 2.2 `AggregateCompiler.compilePreview`:
      - order by `max(versionExpr) DESC`, with `versionExpr` qualified by the input table, then the keys
        ascending, with the `max` projected under an internal alias;
      - add `count(*) OVER ()` under an internal alias and report it as `total_rows` (0 when no row
        comes back);
      - strip both internal aliases before mapping.
- [x] 2.3 Add measure type inference to `AggregateSpecValidator` (new code, next to
      `RETURN_TYPE_TARGETS`): `count` → long; `sum` over integer/long → long; `sum` over decimal and
      `avg` → decimal; `min`/`max` → the input's type; `group_uniq_array` → array. Type a plain key by
      its input binding, and a `trunc` key by the target type when `TruncUnit.targetTypes` contains it,
      else `timestamp`. Build the `key` and `measure` column descriptors with `type` and the target
      binding's `target_type`.
- [x] 2.4 Compute the aggregate notes:
      - `held_by_enrichment` from `enrichmentBound(...).withholdsEverything()`;
      - `enrichment_behind` from the bound compared with the maximum sampled version (skipped while
        held), with the gap in the message;
      - `no_matching_rows`;
      - the coercion notes through the checker of 3.2.
- [x] 2.5 Reword the preview refusals so their status and code follow arming. Make the aggregate
      preview-budget message name a high-cardinality group key as the likely cause. Fix the stale
      `month` text at `PipelineRequests.java:487`.

## 3. Shared preview helpers

- [x] 3.1 Move the settings merge (`FinalSettings.pipeline` + budget settings) out of
      `StructuredQueryExecutor.executeScan` into one helper next to `FinalSettings` that both the
      executor and a renderer call. The renderer appends `SETTINGS k = v, …` in sorted order, server
      settings only (never `query_id`). Use it for every preview `statement`.
- [x] 3.2 Extract typed, non-throwing range predicates from `WriteNumericRangeValidator` (integer, long,
      decimal), `WriteDateValidator` (date) and `WriteTimestampNormalizer` (timestamp, `MIN`/`MAX`),
      taking `Long`, `BigInteger`, `BigDecimal`, `LocalDate` and `OffsetDateTime`. The write path converts
      its strings and calls them; its behaviour and tests stay unchanged. Then add a target-coercion
      checker for sampled values: `target_type_narrowing` on the rank `integer < long < decimal` (and
      `date < timestamp` for `min`/`max` only, never for a `trunc` key its unit admits), and
      `value_out_of_target_range` through those predicates.
- [x] 3.3 Give `PipelineReadBudgetExceededException` a structured `exceeded` (ceiling, configured
      value). In `EnrichPreviewService`, catch it around every read the preview issues and rethrow
      `PipelinePreviewBudgetExceededException` with author-facing remedies chosen per arm (no
      `from`/`to` on `group`). Leave `PipelineReadBudget.run` and the runner paths unchanged. Update the
      `PipelinePreviewBudgetExceededException` Javadoc, and key its `logRefusal` per pipeline.
- [x] 3.4 Add `PreviewValueCapper` (2048-byte constant):
      - measure each non-null `string`, `object`, `array` and `map` value's UTF-8 size into `max_bytes`,
        objects, arrays and maps as their compact `JsonMapper` serialization; leave `max_bytes` absent
        when nothing non-null was sampled;
      - cut values over the cap to at most 2045 bytes at a code point boundary and append `…`;
      - emit one `values_truncated` note per column.
      Run it on every arm, after the coercion and `enrichment_behind` checks.

## 4. Enrich arm

- [x] 4.1 `EnrichPreviewService`:
      - drop the schema, mapping and inputs echo from the response, but keep calling the throwing
        `resolver.composeSchema(...)`, whose refusal and `null` result the notes depend on;
      - map the existing notes to `output_without_description` (one per output, `subject` = output) and
        `unconstrained_response`;
      - fix the class Javadoc that points at "the runner's own dry run".
- [x] 4.2 Class-1 (`MaterializeService.preview` / `sampleQuery`):
      - order by version and identity, descending;
      - project the version under an internal alias and strip it before mapping;
      - return the rendered `statement`;
      - return the columns: `grain_key`, and each `output` with its inferred `type` and `target_type`;
      - compute `freshness.bound(referencedEnrichments(model, referenced), source)` for notes only (do
        not apply it), and report `held_by_enrichment` / `enrichment_behind` from it.
- [x] 4.3 Row-grain `llm`: extract a scan plan from `ScanService.run` (filter analysis, guarded set,
      clamp bound, source predicates, joins). Render the runner's scan and a new preview read from it.
      The preview read differs only in its SELECT list (grain key + bound inputs) and ORDER BY
      direction (descending). Add a test pinning the runner scan's rendered SQL as unchanged.
- [x] 4.4 Row-grain `llm` clamp notes:
      - a bound that `withholdsEverything` → `held_by_enrichment`, no probe;
      - a partial bound → one probe (filter AND lag AND window AND `seekPast(bound)`, version only,
        `LIMIT 1`). When it finds rows, report `held_by_enrichment` if the sample is empty, or
        `enrichment_behind` if it is not, and never `no_matching_rows`.
- [x] 4.5 Group auto-pick:
      - one statement projecting `toString(<group field>)`, excluding NULL and `''` keys, with an
        enrichment-sourced field resolved through `ScanSourceMetadata.projected` and
        `EnrichmentJoins.addReferenced`;
      - `sensitiveGuard.enforceEntity` over the group field and the filter columns first;
      - the runner's marking-scan clamp, with `withholdsEverything` → `held_by_enrichment`;
      - first `from = now − 24h` through `CursorPredicates.window`, then one retry with no window;
      - report the chosen `group_key`, or `no_group_found` when there is none.
- [x] 4.6 Group member sample, through a preview-only path on `GroupFetchService` (`fetch` unchanged):
      - `cap = min(group-fetch.default-rows, member_select.limit ?: ∞)`;
      - phase 1 counts members after `prefer`, up to `cap + 1`, projecting no bound or filter columns
        and bypassing `resolveLimit`; on the single-phase fallback, run a separate key-only count;
      - phase 2 reads bound columns for the top 5 members only;
      - report `group_cut_by_fetch_limit` iff the count exceeds `cap` and `cap` is the configured
        default, never from `GroupPage.truncated`, naming the cap and both counts;
      - strip the group key, version, identity and filter columns from the mapped rows.

## 5. Docs and stale text

- [x] 5.1 Fix the `PipelineController` class Javadoc, which says `enrich` has no preview endpoint, and
      the `PreviewDto` and `PipelineDataController` schema and operation texts.
- [x] 5.2 No configuration property is added or renamed, so `docs/configuration.md` gains no row.
      Update the Pipeline read budget group's prose, which says the Class-1 preview sample refuses with
      `pipeline_read_budget_exceeded`: every `enrich` preview read now refuses with
      `pipeline_preview_budget_exceeded`.
- [x] 5.3 Rewrite the preview paragraph in `README.md` (it describes the refresh statement, the echo and
      the enrich sibling shape).
- [x] 5.4 Add a row to `docs/release-0.3-upgrade.md` §6 "Breaking changes at a glance": the preview
      response is reshaped, `cursor` in a preview body is a 400, and the enrich preview budget code is
      `pipeline_preview_budget_exceeded`. The action is to update clients (adas-mcp).
- [x] 5.5 adas-mcp hand-off (separate repo; record it in the change's hand-off notes, nothing to do
      here). Update the `adas_preview_pipeline` tool (`src/adas_mcp/tools/pipelines.py`) and its docs,
      then refresh the snapshot against a running ADAS:
      `curl -s localhost:5000/v3/api-docs | python3 -m json.tool --sort-keys > src/adas_mcp/swagger/adas-openapi.json`.

## 6. Tests

- [x] 6.1 Rewrite the aggregate preview assertions for the new shape:
      - `AggregatePipelineIntegrationTest`, `AggregateRefreshServiceTest`, `AggregateCompilerTest`;
      - `AggregatePreviewBudgetIntegrationTest`, `DraftPipelineIntegrationTest`;
      - `AggregateEnrichmentIntegrationTest`: line 269 against the sample statement. The
        `transform_null_in` test (`restrictedRecomputeIsExactOnlyWhileNullNeverSatisfiesIn`, which cut
        the refresh `IN (select distinct` out of preview's `statement`) moves to the compiled refresh
        statement, from `compiler.compileRefresh` or from `system.query_log` by its
        `adas-<p>-refresh-write-%` query_id;
      - `PipelineDataContractTest`, which builds `PreviewResult("INSERT", …)` and mocks
        `EnrichPreviewService`;
      - tighten `AggregatePipelineIntegrationTest`'s `count(*)` assertion (~line 1101) to
        `count(*) AS "hop_count"`, since the window count also renders `count(*)`.
- [x] 6.2 Add aggregate tests for:
      - newest-first order;
      - `total_rows` equals the group count over the whole input, and is `0` when empty;
      - no `INSERT` in the response, and no helper column in `columns`/`rows`;
      - `held_by_enrichment` and `enrichment_behind`;
      - `target_type_narrowing` on a `count` into an `integer` target (no range note);
      - `value_out_of_target_range` and `target_type_narrowing` on a `sum` over `long` into `integer`
        with a group sum above 2^31;
      - a day `trunc` key into `date` raising no narrowing;
      - `no_matching_rows`;
      - 403 `sensitive_column_not_entitled` through preview;
      - the `SETTINGS` clause in `statement`;
      - a compiler test for a `_updated_at`-versioned input joined to a user-table enrichment (qualified
        ordering expression).
- [x] 6.3 Rewrite `EnrichPreviewIntegrationTest` and `FoldedDeclarationIntegrationTest` for the new
      shape. The folded-legacy schema assertion moves to `GET` (the authored `transform`).
- [x] 6.4 Add enrich tests for:
      - a `cursor` body giving 400;
      - newest-first row-grain and Class-1 samples;
      - row-grain columns limited to the grain key and the inputs, with a filter on an enrichment column
        still joined and still enforced;
      - group auto-pick, skipping an empty key, `no_group_found`, and `held_by_enrichment` on a held
        clamp;
      - group columns limited to the inputs, with `group_key` reported;
      - `group_cut_by_fetch_limit` with no `member_select.limit`, with one below the default (no note),
        and with one above the default (note, naming the default);
      - a clamp-emptied row-grain sample reporting `held_by_enrichment`, and a partially clamped one
        reporting `enrichment_behind`;
      - Class-1 `enrichment_behind`;
      - a budget refusal giving `pipeline_preview_budget_exceeded`, while the runner scan keeps
        `pipeline_read_budget_exceeded`.
- [x] 6.5 Add `PreviewValueCapper` unit tests:
      - an over-cap string cut on a multi-byte boundary, and on a 4-byte (surrogate-pair) character that
        straddles the cut, with the cell at most 2048 bytes;
      - an over-cap object and an over-cap array, becoming cut JSON text;
      - an under-cap value left unchanged;
      - `max_bytes` measured from the full value, and absent for an all-null column;
      - one note per column, with the right count.
- [x] 6.6 Add range-predicate unit tests for each typed overload, plus the unchanged write-path tests.

## 7. Verification

- [x] 7.1 `openspec validate refine-pipeline-preview --strict` passes. Re-diff each MODIFIED delta
      against its base right before archive, because the base may move. The three copied aggregate
      requirements and "Aggregate spec vocabulary" each change only a sentence or two, so a stale copy
      would silently revert someone else's edit.
- [x] 7.2 `./gradlew clean build` passes (the integration tests need a reachable Docker daemon).

## Hand-off notes

- **adas-mcp (5.5), not done here — separate repo.** `adas_preview_pipeline`
  (`src/adas_mcp/tools/pipelines.py`) and its docs still describe the old response (`statement` as the
  refresh `INSERT`, `sample_statement`, `group_by`, `measures`, the `enrich` member) and may send `cursor`,
  which is now a 400. Update the tool to read `statement`, `columns`, `rows`, `total_rows`, `group_key` and
  coded `notes`, drop `cursor`, and treat `pipeline_preview_budget_exceeded` as the enrich preview's budget
  code. Then refresh the snapshot against a running ADAS:
  `curl -s localhost:5000/v3/api-docs | python3 -m json.tool --sort-keys > src/adas_mcp/swagger/adas-openapi.json`.
- **Covered at unit level rather than end to end:** the aggregate preview's 403 `sensitive_column_not_entitled`
  (`AggregateRefreshServiceTest`; the handler's 403 mapping is tested elsewhere), the aggregate
  `held_by_enrichment` / `enrichment_behind` notes (`AggregateRefreshServiceTest`), and the enrich preview's
  budget-code translation (`EnrichPreviewServiceTest`). An end-to-end budget refusal would need its own
  `pipeline.budget.*` override, which is a second Spring context against the shared PostgreSQL container.
- **Follow-up (design risk):** a disabled draft whose stored `output_bindings` are stale previews through the
  in-memory derivation while `GET ?view=compiled` still serves the stale mapping; re-derive on target schema
  patch.
- **Follow-up (post-archive review):** `group_cut_by_fetch_limit` takes its cap from
  `enrichment.group-fetch.default-rows`, but the runner always sends `limit = GROUP_FETCH_LIMIT`
  (`GroupWorkerService.fetchMembers`), so ADAS's default only applies to a limit-less caller. The note is
  right only while both are 200; if either diverges, it names the wrong cap and the wrong knob.
- **Post-archive review:** task 5.4 was ticked but the 0.3 upgrade-guide row was missing;
  it is added now.
