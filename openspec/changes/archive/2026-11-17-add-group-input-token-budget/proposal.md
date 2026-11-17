## Why

The Enrichment Runner trims a group pipeline's assembled input to an approximate token budget before the call. It
drops the oldest members and estimates four characters per token. The budget is one runner-wide setting, so a
pipeline cannot opt out. That matters for a `dial_app_route` application that counts tokens properly and decides
for itself what to keep: the runner's pre-trimming throws away members it could have used. The only switch today
removes the trimming for every group pipeline at once, including `chat_completion` ones that need it. The runner
is ready to read a per-pipeline budget. ADAS has to let a pipeline declare one and serve it.

## What Changes

- `advanced` gains an optional fifth member, **`input_token_budget`** (integer). Absent means the runner's
  configured default, `0` means the runner does not trim this pipeline's groups, and a positive value is the
  pipeline's own ceiling. It is stored as sent and served under both projections, like the other knobs.
- A negative value is refused with HTTP 422 `pipeline_validation_failed`.
- It is a **group-only** member. Declaring it on a `schedule` pipeline is refused with HTTP 422
  `pipeline_validation_failed` on every write, a disabled draft included, as `trigger.member_select` is. It is an `enrich`-only member, so on an `aggregate` pipeline it is refused as a member
  of another kind.
- **BREAKING (contract):** `RunnerContract.VERSION` goes from `5` to `6`. The compiled projection's `advanced`
  gains a member, which `RunnerContractShapeTest` pins. ADAS and the runner deploy together. During the skew the
  echo check refuses the runner, so no call is made.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `enrichment-registry`: the `advanced` block gains `input_token_budget`, with its range and group-only rules, and
  both projections carry it.
- `enrichment-runtime`: the runner contract version this build serves is `6`.

## Impact

- **`/v1` surface:** `POST` and `PATCH /v1/pipelines` accept `advanced.input_token_budget`. `GET` (both views) and
  the enrich feed serve it. Catalog export and import carry it in the source projection.
- **Catalog schema:** none. The member is stored inside the pipeline's `spec` JSON, so there is no migration.
- **Catalog bundle:** `format_version` goes from `2` to `3`, as the bundle's pipeline shape gains a member, so an
  older ADAS refuses a new bundle with `catalog_bundle_version_unsupported`.
- **Config keys:** none.
- **Code:** `PipelineRequests.AdvancedRequest`, `EnrichSpec`, `PipelineMapper` (store, project, and the
  `ENRICH_ONLY` list), `PipelineService.requireNoTriggerContradiction`, `RunnerContract`, `CatalogBundle`.
- **Docs:** `docs/release-0.24-upgrade.md` (the contract now moves `4` → `6` in this release, and the new knob).
- **Paired change:** the Enrichment Runner reads the member and speaks contract `6` in its 0.24 release.
