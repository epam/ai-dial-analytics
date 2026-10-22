## Why

`POST /v1/pipelines/{name}/preview` is the checkpoint an author uses after creating or editing a pipeline
and before enabling it: "is everything right, and will it work?". Today it partly answers a different
question, and it answers in two unrelated shapes:

- The aggregate `statement` is labelled "the real window" but has neither the probed end nor the window
  cap. It also drops the enrichment clamp in exactly the case where that clamp would stop the write.
- Every sample is "the first N by key or by age". In practice that can mean most rows are test traffic,
  data is months old, and several rows are identical. Nothing in the response says so.
- Half the payload repeats what the UI already reads from `GET /v1/pipelines/{name}`: `group_by`,
  `measures`, `response_schema`, `inputs`, `outputs`, and the INSERT's select list.
- The facts an author actually needs are missing:
  - the type of each value and whether the target column can hold it;
  - whether more rows exist than are shown;
  - why a sample came back empty;
  - machine-readable warnings a UI can render.

The UI design for preview is about to start. The contract should be fixed first.

## What Changes

**Response.** One flat shape for both kinds, carrying only what preview alone can know:

```
{ statement?, columns:[{name, role, type, target_type?, max_bytes?}], rows:[…], total_rows?,
  group_key?, notes:[{code, severity, subject?, message}] }
```

- **BREAKING** Remove the aggregate refresh `statement` (the INSERT). Its window is runtime state that
  `GET …/{name}` already serves under `state`, and both of its correctness bugs (missing end and cap,
  dropped clamp) go away with it.
- **BREAKING** Rename `sample_statement` to `statement`, now carrying the `SETTINGS` the sample ran
  under. It is present for `aggregate` and Class-1 `sql` (which gains it), and absent for `llm`.
- **BREAKING** Remove the definition echoes: `group_by`, `measures`, the `enrich` wrapper and its
  `type`, `response_schema`, `response_schema_source`, `inputs` and `outputs`. Their derived forms
  are already on the pipeline read.
- Add `columns[]`: each sample column's role (`key`, `measure`, `grain_key`, `output`, `input`), its
  type, and the target column's type wherever the column is written. Aggregate measure types are new
  inference in the gate, which today only checks a coarse return class.
- Add `total_rows` (`aggregate` only): the number of groups over the whole input, which is the row count
  the target will hold once caught up. It is counted by the sample's own statement, before its `LIMIT`,
  and it catches a wrong group key at a glance (or, for a very wrong one, the preview budget refuses
  the count, which is the same signal). There is no "more rows" flag on either kind, because preview has
  no pagination to act on it. `enrich` gets no count: for Class-1 it would only measure the source.
- Cap every value in `rows` at 2048 bytes, on every kind. A longer string is cut at a code point
  boundary and ends in `…`, the ellipsis included in the 2048. A longer `object`, `array` or `map` value is replaced by its cut JSON text.
  Each capped column is reported once with `values_truncated`. Without the cap, an `llm` sample of five
  body rows (tens of KB each) makes a multi-megabyte response a UI cannot render.
- Add `max_bytes` to `string`, `object`, `array` and `map` columns: the largest sampled value's size
  before the cap. It doubles as a hint at what one input row costs the model.
- Replace free-text notes with coded notes (`code`, `severity`, `subject`, `message`) on both kinds.
  Aggregate preview gains notes for the first time.

**Sample behaviour.**
- Samples come **newest first** instead of first by key or oldest.
  - Aggregate orders groups by their latest input row.
  - Class-1 and row-grain `llm` order rows by version, newest first.
  - A `group` member sample keeps the runner's assembly order; "newest" picks the group.
- A `group` pipeline previewed without a `group_key` samples the group of the newest matching row with
  a non-empty key, under the clamp the runner's group marking applies, and reports which key it chose.
  Today it returns no data at all.
- An `llm` sample carries the bound input columns (plus the grain key on row-grain) only. Filter,
  cursor, version and identity columns are no longer returned, though the filter still drives the joins,
  sensitive enforcement and the clamp exactly as in the runner's read.
- A `group` sample reads its bound inputs for the five members shown only, and counts the rest without
  them.

**Notes that matter before enabling:**
- `no_matching_rows`
- `held_by_enrichment`: an enrichment has no usable bound yet, so an enabled run would write nothing.
  This replaces today's note, which blames the filter for this case.
- `enrichment_behind`: matching rows lie above an enrichment's bound; the message gives the gap.
- `no_group_found`
- `target_type_narrowing`
- `value_out_of_target_range`
- `group_cut_by_fetch_limit`
- `output_without_description`
- `unconstrained_response`
- `values_truncated`

**Request.**
- **BREAKING** Drop `cursor`. A seek-past cursor has no meaning under a newest-first order, and preview
  returns none to continue from. A body carrying it is a 400, as every unknown member already is.
- `from`, `to` and `group_key` stay as they are, though `group_key` is no longer required for a `group`
  sample.

**Errors.**
- The enrich preview's budget refusal moves from `pipeline_read_budget_exceeded` to
  `pipeline_preview_budget_exceeded`, with a preview-oriented message. The limits themselves do not
  change.
- The registry already requires one code per condition across kinds; this brings enrich preview into
  line with that. The runner's own scan and group fetch keep `pipeline_read_budget_exceeded`.

**Spec and doc corrections:**
- The registry gate requirement says an enabled preview is never re-gated. That is true only of
  `enrich`; aggregate deliberately re-gates in both states, so the wording is scoped to `enrich`.
- The aggregate preview requirement says "every refusal is 422". 403 also occurs (sensitive columns and
  row policy), and 409 can in principle, so the wording is corrected.
- The enrich preview requirement says an enabled preview runs "only the template-placeholder check". It
  also checks the transform's presence and type and keeps the response-schema composer's refusal, so
  the wording names those.
- The enrich preview requirement points a caller wanting the rendered request at the runner's dry-run
  surface. None exists; the wording says so, and so does the `EnrichPreviewService` Javadoc.
- Three other aggregate requirements still describe the old preview ("Preview executes under a resource
  budget", "Refresh observability", "Refresh serves the aggregate kind only"), and are corrected.
- `docs/configuration.md`'s pipeline read budget prose and the `README.md` preview paragraph describe
  the old codes and shape.
- Stale Javadoc and schema text: `PipelineController` says enrich has no preview; `PipelineRequests`
  says calendar `month` is rejected, that `group_key` is required "because ADAS has no group-discovery
  surface", and documents `cursor`; `PipelineDataController` names `cursor` in its body refusal and
  operation description.

**Not changed:**
- Gating rules: the full gate on disabled pipelines; the full gate on enabled aggregate; on enabled
  enrich, the template check, transform presence and type, and the composer's refusal.
- Budgets and their limits.
- The unwindowed scope of the aggregate sample.
- No model call and no rendered prompt.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `pipeline-registry`: adds the shared preview response shape (columns, rows, aggregate `total_rows`, coded notes)
  and the one preview budget code across kinds. It also scopes the "enabled preview is not re-gated"
  sentence to `enrich`.
- `aggregate-pipeline`: "Dry-run preview of an aggregate declaration" drops the refresh statement and
  the echoes, makes the sample newest-first with typed columns and coercion notes, and corrects the
  refusal-code wording. "Aggregate spec vocabulary" points the derived `group_by` and measure column at
  the pipeline read instead of the compiled projection (refused for this kind) and the preview.
  "Preview executes under a resource budget", "Refresh observability" and "Refresh serves the aggregate
  kind only" drop their references to the old preview statement and sibling shapes.
- `enrichment-registry`: "Dry-run preview of an enrich declaration" drops the schema, mapping and input
  echoes and the `cursor` parameter. It adds the Class-1 `statement`, newest-first samples, a clamped
  group auto-pick, the group-cut count, and the clamp-versus-filter cause on row-grain samples.
- `enrichment-runtime`: "Every pipeline-internal read runs under a resource budget" now lists the new
  preview reads among the budgeted statements, and says a read made for the preview surface refuses
  under the preview code while the runner's keeps the read code.

## Impact

- **API:** `POST /v1/pipelines/{name}/preview`. Both the request (`cursor` removed) and the response
  change incompatibly. The OpenAPI document changes with them.
- **Code:**
  - DTOs: `PipelineResponses.PreviewDto`, with `EnrichPreviewDto` and `PreviewOutputDto` removed, and
    `PipelineRequests.PreviewRequest`.
  - `PipelineDataController.preview`, `PipelineMapper`.
  - `AggregateRefreshService.preview` and `AggregateCompiler.compilePreview`.
  - `EnrichPreviewService` and `EnrichPreview`.
  - `AggregateSpecValidator` (measure type inference), `TruncUnit` (key typing).
  - The preview paths of `MaterializeService`, `ScanService` (scan-plan extraction) and
    `GroupFetchService` (preview-only count and member read).
  - `PipelineReadBudgetExceededException` (structured ceiling), `PipelinePreviewBudgetExceededException`
    Javadoc, and the preview-budget log key in `DefaultExceptionHandler`. No new handler is needed.
  - `FinalSettings` / `StructuredQueryExecutor` (settings merge moved into one helper).
  - `WriteNumericRangeValidator`, `WriteDateValidator`, `WriteTimestampNormalizer` (typed,
    non-throwing range predicates).
  - Stale Javadoc and schema text in `PipelineController`, `PipelineRequests`,
    `PipelineDataController` and `EnrichPreviewService`.
- **Tests:** the preview tests in `AggregatePipelineIntegrationTest`, `AggregateRefreshServiceTest`,
  `AggregateCompilerTest`, `AggregatePreviewBudgetIntegrationTest`, `AggregateEnrichmentIntegrationTest`,
  `EnrichPreviewIntegrationTest`, `DraftPipelineIntegrationTest`, `FoldedDeclarationIntegrationTest` and
  `PipelineDataContractTest` are rewritten against the new shape. `AggregateEnrichmentIntegrationTest`'s
  `transform_null_in` test read the refresh statement from preview and needs another way to reach it.
- **Docs:** `README.md` (preview paragraph), `docs/configuration.md` (pipeline read budget prose only,
  no property row), `docs/release-0.3-upgrade.md` (breaking-changes row).
- **Consumers:** the `adas-mcp` `adas_preview_pipeline` tool and its docs (a separate repo) need a
  matching update. The Enrichment Runner does not call preview. No UI exists yet.
- **No change** to configuration properties, the catalog, migrations or ClickHouse.
