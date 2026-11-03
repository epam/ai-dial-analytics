## Why

A pipeline has two independent on/off switches: the registry's `enabled` flag, and a pause the Enrichment
Runner holds in its own memory. A UI user sees two buttons that both "stop the pipeline", cannot tell when to
pick one over the other, and can find a pipeline that reads as running in ADAS while the runner has it paused.
The two also differ in ways no user should have to know: disabling made the runner discard queued work whose
cursor had already advanced, while a pause kept it but was lost on every runner restart and was invisible to
ADAS.

This change makes `enabled` the pipeline's single run control. The runner side — following the flag, keeping
a stopped pipeline's work, removing its own pause — is a matching change in the Enrichment Runner.

## What Changes

- `enabled` is specified as the **single run control** of every pipeline kind: active or stopped, stored
  durably in the registry, and the one place a caller reads or changes whether a pipeline runs. Stopping keeps
  progress for both kinds; restarting re-runs the declaration gate.
- **An enable-only or disable-only patch no longer bumps `generation`**; it still moves `updated_at`.
  `generation` becomes purely the declaration's revision. Today a stop and restart bumps it twice, which
  makes the runner discard queued work as stale and voids the pipeline's materialized position, freezing
  dependent rollups until the runner writes again. A patch that re-declares and changes `enabled` still
  bumps once.
- The runner's sync feed becomes the **unfiltered** enrich listing (`GET /v1/pipelines?kind=enrich&view=compiled`)
  instead of `enabled=true`, so a consumer can tell a stopped pipeline (kept, marked `enabled: false`) from a
  deleted one (absent). A consumer executes only entries carrying `enabled: true`. The endpoint, its filters
  and the entry shape are unchanged; this is a change of what the runner asks for, not of what ADAS serves.
- The runner's dead-letter circuit-breaker pause is specified as a runtime condition the runner reports, not
  a control: it never changes `enabled`.
- Spec rationale that described the old runner behaviour (dropping a disabled pipeline from its cache,
  dead-lettering its in-flight work) is corrected. The disabled-pipeline write refusal stays as the backstop,
  and its `pipeline_conflict` code is pinned to mean "disabled" only, so the runner can hold such a write.
- **Not breaking**: no endpoint, request shape, error code, config key or migration changes, and the
  runner contract version does not move. The one observable difference is that `generation` stays put on
  a flag-only patch; a runner built before this change still sees the flag through the `enabled=true`
  feed, where a stopped pipeline is absent and a restarted one reappears.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `pipeline-registry`: adds "A pipeline has exactly one run control"; "Register and enable lifecycle" stops
  bumping `generation` on a flag-only patch and corrects its runner rationale; "A generation change is
  logged with its old and new value and its cause" drops the flag-only causes; and the safety argument in
  "The declaration gate runs when a write arms the pipeline, not when it is stored" is corrected.
- `enrichment-registry`: "The enrich feed carries the transform, a resolved schema and a change token" — the
  runner reads the unfiltered feed and executes only `enabled: true` entries; the Purpose paragraph names the
  unfiltered feed.
- `enrichment-runtime`: "A disabled pipeline's write paths refuse" (narrowed cost) and "A runner that has
  stopped calling is visible as a metric" (follows `enabled`; a breaker pause is not reported to ADAS).

## Impact

- **API**: none. `PATCH /v1/pipelines/{name}` `enabled` and the feed's `enabled` filter are unchanged.
- **Code**: `PipelineService.patch` moves `updated_at` without bumping `generation` on a flag-only patch,
  and its enable/disable log lines report the unchanged generation. Javadoc in `PipelineService` and
  `RunnerContactMetrics` that describes the runner dropping a disabled pipeline is corrected, and the
  `generation` OpenAPI description stops promising a bump on every accepted mutation. Tests pin the
  flag-only patch and the unfiltered compiled feed carrying a disabled pipeline with its full entry.
- **Docs**: an upgrade note for disabling, in ADAS, any pipeline an operator had paused in the runner before
  the runner is upgraded.
- **Enrichment Runner**: the matching change `single-pipeline-run-control` in that repository. Ship this
  change first: the runner's parked work survives a restart only once a flag-only patch stops moving
  `generation`. A runner shipped earlier still works, but a stop and restart drops its parked work as stale,
  as today.
- **UI**: drops the runner's pause and resume buttons and drives `enabled` alone; it keeps reading the runner's
  runtime status to show a breaker pause.
