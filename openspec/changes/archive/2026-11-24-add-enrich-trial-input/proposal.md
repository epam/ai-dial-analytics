## Why

The dry-run preview of an `llm` enrich pipeline (both presets, `chat_completion` and `dial_app_route`) calls no
model, so an author learns that the deployment is missing, the route refuses the body, or the reply does not
parse against the response schema only after arming, from the DLQ. The renderer, the DIAL client and the
response validator live in the Enrichment Runner, and the enrich preview requirement already places a
model-calling dry run there. The runner cannot build one from what ADAS serves today, though:

- The preview caps every value at 2048 bytes and shows at most five group members, so its rows are not what
  the model receives.
- The runner's pipeline cache holds the compiled projection of **enabled** pipelines only, and a disabled
  draft's stored output mapping may be stale. The preview rebuilds that mapping in memory through the gate.
- The runner reads ADAS under its own service identity. A dry run read that way would skip the author's
  column and row restrictions, and a draft that was never armed would skip the `app-routes` allowlist.

This change adds the ADAS half: one runner-only read that hands the runner exactly what it needs, under the
author's identity. The paired Enrichment Runner change adds the model-calling endpoint on top of it.

## What Changes

- New runner-only data-plane endpoint `POST /v1/pipelines/{name}/trial-input` for `llm` enrich pipelines. It
  runs everything the dry-run preview runs, in the same order and with the same refusals, and answers with:
  - `preview`: the public preview response, unchanged (values capped, notes, `group_key`);
  - `pipeline`: the compiled projection of the declaration the preview sampled through, built from the gated
    in-memory declaration for a disabled pipeline;
  - `input_rows`: the rows the runner would receive, uncapped and in the runner's read shape. For a `group`
    pipeline that is the chosen group's full member fetch, as `/group-rows` returns it, with `truncated`. For
    a row-grain pipeline it is the newest sampled rows, up to a requested `limit` (default 1, at most 5).
- It is authorized as the preview is (`REGISTRY_ADMIN`, the target's grant, column entitlement, row
  confinement), against the **calling** identity. The runner forwards the author's credential, so ADAS
  enforces the author's view and the `app-routes` allowlist rather than the runner's.
- It refuses a `sql` (Class-1) pipeline with HTTP 422 `pipeline_validation_failed`: a Class-1 transform calls
  no model, and its preview already shows computed values.
- Every read it issues runs under the pipeline read budget and refuses with `pipeline_preview_budget_exceeded`,
  as the preview's reads do.
- **BREAKING (runner contract):** the endpoint joins the runner-only data-plane set guarded by
  `Adas-Contract-Version`, and the contract version rises from `6` to `7`. A runner built for `6` is refused
  until both halves ship together.
- The enrich preview requirement's statement that no rendered-request dry run exists on either service is
  replaced by a pointer to the runner's dry run and this endpoint. `POST /v1/pipelines/{name}/preview` itself
  is unchanged and still calls no model.

## Capabilities

### New Capabilities

None. The endpoint belongs to the governed data plane in `enrichment-runtime`.

### Modified Capabilities

- `enrichment-runtime`: a new requirement for the trial-input endpoint (its reads refuse under the
  preview's budget code); the exact-contract-version requirement gains it in its endpoint list and records
  version `7`.
- `enrichment-registry`: the "Dry-run preview of an enrich declaration" requirement drops the claim that no
  model-calling dry run exists and points to the runner's dry run, which reads through `trial-input`.

## Impact

- **API:** `POST /v1/pipelines/{name}/trial-input` (internal, `@ApiInternal`, contract-guarded). OpenAPI
  document and `RunnerContractShapeTest` golden list updated.
- **Code:** `PipelineDataController`, `EnrichPreviewService` (the sampling path returns its gated
  declaration and uncapped rows before capping), `ScanService.previewRows` / `GroupFetchService` (an
  uncapped full-group fetch over a resolved declaration), `PipelineMapper` (compiled projection of a
  resolved, in-memory declaration), `RunnerContract` (`VERSION_VALUE = "7"`), `RunnerContractInterceptor`.
- **Paired change:** the Enrichment Runner must ship its contract `7` build with this one. Runners built for
  `6` hold all work against an ADAS that serves `7`, as with every contract bump.
- **Docs:** the release upgrade note names the paired bump. No new configuration property.
- **No change** to `/preview`, `/scan`, `/group-rows`, `/rows` or `/materialize`, to the catalog schema, or
  to Flyway migrations.
