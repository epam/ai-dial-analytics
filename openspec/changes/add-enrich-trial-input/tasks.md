## 1. Contract

- [x] 1.1 Raise `RunnerContract.VERSION_VALUE` to `"7"`, with a one-line note that `trial-input` joined the guarded set
- [x] 1.2 Bring `trial-input` under `RunnerContractInterceptor`, so it requires the header like `scan`, `group-rows`, `materialize` and `rows`
- [x] 1.3 Add the endpoint's request and response shape to the `RunnerContractShapeTest` golden list

## 2. Sampling path

- [x] 2.1 Split `EnrichPreviewService` so the `llm` arm returns the uncapped sample and the resolved (gated) declaration; `preview()` keeps capping exactly as today
- [x] 2.2 Take the row-grain input rows as the first `limit` (1–5) of the preview's own newest-first `ScanService.previewRows` sample, uncapped and keyed as `/scan` keys them (no new read)
- [x] 2.3 Add a `GroupFetchService` fetch over a `ResolvedEnrichPipeline` (no reload by name) that applies the configured fetch cap, `member_select`, ordering and byte cap of `/group-rows`, and returns `truncated`
- [x] 2.4 Keep the group preview's own member sample beside the full fetch (the sample ranks by `member_select`, so it is not the head of the fetch); `trial-input.preview` stays identical to `/preview`
- [x] 2.5 Add a `PipelineMapper` compiled projection of a resolved, in-memory declaration, in the existing compiled shape

## 3. Endpoint

- [x] 3.1 Add `TrialInputRequest {group_key?, from?, to?, limit?}` (unknown member → 400) and `TrialInputResponse {preview, pipeline, input_rows, truncated?}` DTOs
- [x] 3.2 Add `POST /v1/pipelines/{name}/trial-input` to `PipelineDataController`: `@ApiInternal`, `@RequiresPrivilege(REGISTRY_ADMIN)`, contract header documented, and every 4xx code in `@ApiResponse`s
- [x] 3.3 Refuse `aggregate` and Class-1 `sql` pipelines, `limit` outside 1–5, and `limit` on a `group` pipeline with 422 `pipeline_validation_failed`; reuse the preview's `group_key`/`from`/`to` refusals
- [x] 3.4 Translate read-budget refusals to `pipeline_preview_budget_exceeded`, as the preview does
- [x] 3.5 Keep the endpoint off the runner contact/attempt series (do not route it through `served(...)`)
- [x] 3.6 Regenerate `docs/openapi.json`

## 4. Tests

- [x] 4.1 Row-grain: the default returns one uncapped row; `limit: 3` returns three rows newest first; the grain keys match `preview.rows`
- [x] 4.2 Group: `input_rows` equals `/group-rows` for the chosen key; `preview.rows` holds five members; `truncated` follows the fetch cap
- [x] 4.3 Disabled draft with a stale stored mapping: `pipeline` carries the gate-derived outputs and response schema
- [x] 4.4 Refusals: allowlist miss on a disabled `dial_app_route`, `sensitive_column_not_entitled` for an uncleared caller, `forbidden` without `REGISTRY_ADMIN` even with the source and target grants, aggregate/sql kinds, a bad `limit`, budget exceeded
- [x] 4.5 Contract: a missing or mismatched header returns 409 before any read
- [x] 4.6 An empty sample (`no_group_found`) returns 200 with empty `input_rows`; `held_by_enrichment` and `no_matching_rows` come from the same shared sample path the preview tests cover
- [x] 4.7 No write, no cursor change, runner contact series unchanged; `trial-input.preview` equals `/preview` for the same body; existing preview tests unchanged and green

## 5. Docs and verification

- [x] 5.1 Add the paired ADAS/runner contract `7` release to the upcoming release upgrade note in `docs/` (no `configuration.md` change: no property is added)
- [x] 5.2 Confirm the merged spec text matches the shipped behaviour, and run `.claude/skills/pr-ready/hygiene-sweep.pl`
- [x] 5.3 `./gradlew clean build` passes
