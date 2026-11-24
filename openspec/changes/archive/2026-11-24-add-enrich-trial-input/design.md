## Context

An author checks an `llm` enrich pipeline with `POST /v1/pipelines/{name}/preview` before arming it. That
preview reads a governed sample and calls no model, so it cannot tell the author whether the deployment
exists, whether a `dial_app_route` application accepts the rendered body, or whether the reply parses against
the response schema. Those failures surface only after arming, as DLQ entries.

The pieces that would answer those questions live in the Enrichment Runner: the template renderer, the
group-input assembler with its token budget, the DIAL client for both presets, the corrective retry, the
response validator and the output mapper, all behind `TransformInvoker.derive`. The enrich preview
requirement already says a model-calling dry run belongs there. What the runner lacks is the input:

- **Values:** preview `rows` cap every value at 2048 bytes and show at most five group members. The model
  receives full values and the whole group up to the fetch cap.
- **Declaration:** the runner caches the compiled projection of enabled pipelines only. A disabled draft's
  stored output mapping may be stale; the preview samples through the mapping the gate derives in memory.
- **Identity:** the runner calls ADAS with a service API key that holds `TABLE_READ`/`TABLE_WRITE` grants.
  The preview enforces the author's column policy, sensitive clearance, row confinement and, for a disabled
  draft, the `app-routes` allowlist. A dry run reading under the runner's identity would skip all of these.

The admin UI already calls the runner from its server side with the user's bearer token, and the runner
accepts the same identity providers as ADAS.

## Goals / Non-Goals

**Goals:**

- One ADAS read that gives the runner the preview, the declaration that preview gated, and the uncapped rows
  the model would receive, in one snapshot.
- Every check, refusal and budget the preview applies, applied unchanged, against the author's identity.
- No change to the public preview or to the runner's existing data-plane endpoints.

**Non-Goals:**

- Calling a model from ADAS, or rendering a prompt in ADAS.
- Dry runs of Class-1 (`sql`) or `aggregate` pipelines: their preview already shows computed values.
- Previewing an unsaved candidate declaration. The preview evaluates the stored declaration, and so does this.
- The runner endpoint, spend accounting and the UI. They belong to the paired runner change and the admin
  frontend.

## Decisions

### A dedicated runner-only endpoint, not a flag on `/preview`

`POST /v1/pipelines/{name}/trial-input` is a new `@ApiInternal` endpoint on `PipelineDataController`, guarded
by `Adas-Contract-Version` like the other runner-only endpoints.

- *Rejected: an `uncapped` flag on `/preview`.* `/preview` is a public operator surface exempt from the
  contract header. An uncapped mode would put tens of KiB per value and whole groups on a surface whose
  clients (the admin UI, `adas-mcp`) expect a bounded payload, and it would still lack the compiled
  declaration.
- *Rejected: the runner calls `/preview`, then `/scan` or `/group-rows`.* `/scan` reads ascending from a
  cursor, so it cannot fetch the preview's newest rows. Both data-plane reads use the stored mapping and
  authorize by the runner's grants. A descending flag on the scan was already rejected when the preview's
  newest-first read was built, because it would widen the scan's contract.
- *Rejected: serve the compiled projection of disabled pipelines in the sync feed.* The runner runs whatever
  the feed carries, and the feed would also need the gate's in-memory mapping. A per-request snapshot keeps
  drafts out of the runner's cache.

### One snapshot: `{preview, pipeline, input_rows, truncated}`

The endpoint returns the preview response alongside the uncapped input so the runner can hand the UI the
preview it already renders, plus the evaluation, without a second ADAS call. The declaration is taken from
the same `ResolvedEnrichPipeline` the sample was read through, so the outputs the runner maps against are
the ones the gate approved for that sample.

`EnrichPreviewService` is split so the sampling arm returns a pre-cap `Sample` and the resolved declaration;
`preview()` caps it as today, and `trialInput()` returns both. `PipelineMapper` gains a compiled projection
of a resolved declaration that was not reloaded from storage. Its shape is the existing compiled
projection, so the runner deserializes it into the `PipelineDto` it already has.

### Row-grain: the preview's own newest-first read, cut to `limit`

`ScanService.previewRows` already reads the newest matching rows with the scan's plan. Trial input runs that
same five-row read and returns its first `limit` rows, so the preview beside them is the preview's own sample.
The preview's read selects only the grain key and the bound columns, though, while a runner scan row also
carries the filter's columns, the version and identity columns and an aligned target's partition echo. A JSONata input is
evaluated over the whole row, so it may read one of those that no binding names. Trial input
therefore reads the sample *scan-shaped*: the same statement also selects the scan page's columns, the input
rows keep them, and the preview's rows are cut back to the sampled columns. `InputMapper` then sees what it sees
in a worker. The extra columns are the scan's own, so the plan's authorization and sensitive-column check
already cover them, and the plain `/preview` read is unchanged. `limit` defaults to 1 and is capped at 5: each
row costs a model call in the runner, and five is the preview's own sample size.

### Group: the runner's own fetch over the gated declaration

The runner's model receives the `/group-rows` assembly, not the preview's five-member read. Trial input
therefore runs `GroupFetchService`'s fetch for the chosen key, through an entry point that takes the
resolved declaration instead of reloading it by name, with the configured fetch cap and the declaration's
`member_select`. The preview's own five-member sample is kept as it is rather than derived from the fetch:
it takes the members the declaration's `member_select` ranks highest, while the fetch returns members in the
source's order, so the head of one is not the other. That costs a second read of five members' bound columns,
which keeps `trial-input.preview` identical to `/preview` for the same body.

### Authorization is the preview's, not the data plane's

The endpoint is annotated `@RequiresPrivilege(REGISTRY_ADMIN)` and runs `PipelineAuthorizer.authorize` and
the preview's entitlement checks. The data-plane rule of `TABLE_READ` on the source plus `TABLE_WRITE` on the
target is deliberately not sufficient: it is what the runner's service identity holds. The runner forwards
the author's `Authorization` header on this call alone, so the identity ADAS sees is the person who
pressed the button. No new credential or configuration key is introduced.

### Contract version 7

Adding an endpoint does not change an existing shape, but the runner's dry run depends on it. With the
version unchanged, a runner built for the new endpoint, talking to an older ADAS, would see a `404` it
cannot tell apart from `unknown_pipeline` without parsing codes. The contract is an equality check by
design, so the endpoint joins the guarded set, `RunnerContractShapeTest` pins its request and response
shape, and the version rises to `7`. Both halves ship together, as for every bump.

### Not counted as runner contact

`served(...)` records runner attempts and contact for the data-plane endpoints. Trial input is called on an
author's request, so recording it would make a runner whose scheduled work has stalled look healthy. The
endpoint bypasses those series.

## Risks / Trade-offs

- **[Large responses]** A group with heavy bodies can reach the fetch's byte cap per call. → The cap is the
  one the runner's real fetch already lives under. Trial input is called on demand, one call per click.
- **[An author's identity reads heavy columns more often]** → Same governed paths, same read budget, refused
  under `pipeline_preview_budget_exceeded`.
- **[Version skew holds all runner work]** → The upgrade note names the paired release. This is the
  established cost of a contract bump, accepted deliberately because the version is equality-only.
- **[Refactoring the preview's sampling path]** could change the public preview. → Existing preview tests
  stay unchanged and must pass. A test asserts that `trial-input.preview` equals `/preview` for the same
  body.

## Migration Plan

1. Release ADAS with contract `7` and the paired runner build together. The upgrade note says so.
2. Rollback means rolling back both. A mixed pair holds runner work (409 `runner_contract_mismatch`) and
   writes nothing, so it costs a stall, not data.

## Open Questions

None blocking. `adas-mcp` does not need a tool for this endpoint, since it is runner-only.
