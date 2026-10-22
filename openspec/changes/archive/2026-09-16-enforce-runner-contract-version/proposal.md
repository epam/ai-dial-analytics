## Why

The ADAS ↔ Enrichment Runner cut-over is a coordinated window — *scale the runner to 0 → deploy ADAS →
deploy the runner → scale to 1* — enforced by nothing but a runbook, while both repos auto-deploy
on merge. Reading the two shipped breaking releases off the merged
code and finds one order that fails silently and irreversibly: with ADAS ahead of the runner, an old
runner reading the authored projection gets no evaluator and no `grain_key`, its scan succeeds, every row
is skipped for want of a grain value, and its cursor advances through the entire backlog — no work item,
no dead letter, no model call, no metric, and lag falling to zero as it catches up. Those rows are
permanently unenriched; recovery is resetting the cursor and re-deriving at full model cost.

That trace is of the runner **image that was deployed at the time**, and it matters that it no longer
reproduces: the release itself added an admission arm (`PipelineRouting.unsupportedReason`'s `outputs`
check, ungated by lane) that now refuses an entry carrying no `outputs`, which an authored projection
never carries. What the two releases therefore demonstrate is not a live hole but the *class* — a caller
whose declaration lost a field its lane dereferences, refused by nothing on either side — and the next
shape change reopens it with a different field. `fold-evaluator-into-pipeline`, already in flight, is
precisely the case the current arm does not cover: an entry with no separately-addressed evaluator but
`outputs` still present passes admission and routes to the scan lane as an ordinary row-grain pipeline.

Today nothing on ADAS's side can tell an out-of-date caller from a current one, so the transport keeps
working perfectly while the definition the caller holds is wrong. This change makes that state
unreachable: a caller that does not speak this ADAS's runner contract is refused on the governed data
plane, which turns an out-of-order deploy into the sanctioned "the runner is scaled to 0" state instead
of a silent loss.

## What Changes

- **A runner contract version on the wire.** A code constant (not configuration) is echoed as the
  `Adas-Contract-Version` response header on every response under `/v1/pipelines` and below, so a client
  can tell a current ADAS from one that predates the header at all. Set by a servlet filter ordered **ahead
  of the security chain**, so a `401`/`403` the chain writes itself carries it too — otherwise a wrong key
  would be reported by the runner as a version skew.
- **BREAKING (runner-only surfaces):** the four data-plane endpoints — `POST /v1/pipelines/{name}/scan`,
  `/group-rows`, `/materialize`, `/rows` — require a matching `Adas-Contract-Version` request header.
  Absent → `409 runner_contract_required`; present and different → `409 runner_contract_mismatch`. The
  operator surfaces (`preview`, `refresh`), registration, and both read projections are unaffected, so
  adas-mcp, Swagger and every human read keep working unchanged.
- **A pipeline-scoped write-back must be able to write a label.** A row naming only the grain key and
  provenance columns is refused (`422 write_output_column_required`), because against the target's
  `ReplacingMergeTree` it replaces a label that has already been paid for with NULLs. Its own code rather
  than the existing `write_column_not_allowed`, whose remedy is the opposite one (drop a column, not add
  one). Independent of any version check.
- **`view=compiled` is no longer silently ignored.** Asking for the compiled projection where ADAS does
  not resolve one — a listing without `kind=enrich`, or a single read of a non-`enrich` pipeline — is
  refused instead of answering with the unresolved object under the compiled name.
- **Attribution for a runner that has stopped calling.** Counters for contract refusals and a gauge of
  seconds since the last runner call per `enrich` pipeline, registered on first contact and dropped when
  the pipeline is deleted **or disabled** — a disabled pipeline leaves the feed by design, and a
  scrape-derived gauge left behind for one climbs forever. Metrics only: ADAS's liveness probe targets
  the aggregate `/actuator/health`, so a new health contributor that can report DOWN would restart the
  service rather than report a condition.
- **The existing aggregate deregistration gains its second delete path.** `aggregate.lag.seconds` is
  dropped by `PipelineService.delete` but not by `deletePipelineTargetingTable`, which deletes a pipeline
  when its target table is dropped — the same permanent-false-alarm leak that method's sibling documents
  itself as existing to prevent. Fixed here because the new gauge would inherit it.

No new configuration property: the contract version is a constant, for the same reason the runner hard-codes
`view=compiled` — its only wrong setting disables everything at once, silently.

## Capabilities

### New Capabilities

None. The contract version is a property of the existing governed data plane, not a new surface.

### Modified Capabilities

- `enrichment-runtime`: the governed data plane (`scan`, `group-rows`, `rows`) requires an exact runner
  contract version, a write-back row must name at least one of the pipeline's declared output columns, and
  a runner that has stopped calling is attributable per pipeline.
- `class1-enrichment`: `materialize` carries the same contract requirement as the rest of the data plane.
- `pipeline-registry`: every `/v1/pipelines` response carries the contract version; a requested
  projection is either served as requested or refused, never silently downgraded.

The contract-refusal counters and the per-pipeline runner-contact gauge belong to `enrichment-runtime`
alongside the endpoints they measure, following `aggregate-pipeline`'s precedent for domain metrics; the
`observability` capability covers OTLP plumbing and is unchanged.

## Impact

- **APIs**: the four runner-only `POST /v1/pipelines/{name}/…` endpoints gain a required request header
  (breaking for a caller built before this change — by design); three new stable error codes
  (`runner_contract_required`, `runner_contract_mismatch`, `write_output_column_required`); a new response
  header on every response under `/v1/pipelines` and below; `?view=compiled` without `kind=enrich` becomes
  a `400` and a compiled single read of a non-`enrich` pipeline a `422`.
- **Code**: a new contract constant, a `OncePerRequestFilter` (the response header) and a
  `HandlerInterceptor` (the request requirement) under `web/contract/`, three handlers added to
  `DefaultExceptionHandler` (the two 409 codes, the new 422, and a bare `InvalidRequestParameterException`
  — previously only ever reached the handler wrapped in a binding failure), `EnrichWriteValidator`,
  `PipelineController`'s projection arms, `PipelineService`'s delete/patch deregistration, and a small
  metrics component.
- **Cross-repo**: the Enrichment Runner must send the header and verify the echo; that half ships as its
  own change in `analytics-enrichment-runner` and is not optional. Neither deploy order loses work that a
  requeue cannot recover — ADAS-first refuses the old runner's calls with cursors untouched (though it
  still charges queued model calls and archives their writes: see the design's Migration Plan), and
  runner-first keeps the outgoing runner pod serving because the incoming one never reaches readiness.
- **Cross-repo, docs only**: `adas-mcp` passes `view` through on `adas_list_pipelines` /
  `adas_get_pipeline` and documents that `compiled` "changes nothing" for an `aggregate` pipeline, which
  stops being true. Its docstrings and the two refusals belong in a change in that repo; the tool itself
  keeps working because it forwards what the caller asked for and renders the `ErrorView`.
- **Not affected**: ClickHouse schema, the catalog, configuration, and the aggregate pipeline path — bar
  the one deregistration fix above, which changes no aggregate behaviour an operator can observe except
  the series that should already have been dropped.
