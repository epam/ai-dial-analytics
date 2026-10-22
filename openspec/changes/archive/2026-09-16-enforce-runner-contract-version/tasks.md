## 1. The contract constant

- [x] 1.1 Add `RunnerContract` (`service/enrichment/support/`) holding `VERSION = 1` and
      `HEADER = "Adas-Contract-Version"`, with javadoc stating that only equality is defined, that it is a
      build property rather than configuration, and when it must be raised.
- [x] 1.2 Add `RunnerContractShapeTest`: assert a golden list held beside the constant covering **both**
      halves of what the contract names — the compiled projection's member set, and the component names of
      the four data-plane endpoints' request and response records (`ScanRequest`/`ScanResponse`,
      `GroupFetchRequest`/`GroupFetchResponse`, `MaterializeRequest`/`MaterializeResponse`,
      `WriteRowsRequest`/`WriteRowsResponse`) — so changing either fails the build naming the version bump.

## 2. Refusal on the runner-only data plane

- [x] 2.1 Add `RunnerContractException` and its `@ExceptionHandler` in `DefaultExceptionHandler` mapping to
      HTTP 409 `runner_contract_required` / `runner_contract_mismatch`, the mismatch message naming both
      the version served and the version declared.
- [x] 2.2 Add `RunnerContractHeaderFilter` (`OncePerRequestFilter` on `/v1/pipelines/*`, registered via a
      `FilterRegistrationBean` with an order **ahead of the security filter chain**): set the response
      header on every response under the prefix. A filter rather than the interceptor because a `401`, a
      URL-level `403` and an unmapped-path `404` never reach a `HandlerInterceptor`, and an absent echo is
      what a client reads as "this build predates the contract" — see design decision 2. **Note the two
      pattern languages**: the filter's mapping is a servlet URL pattern, where `/v1/pipelines/*` is a
      path-prefix mapping that also matches the bare `/v1/pipelines` (the feed's own path); `**` there is a
      literal and would match nothing, and the resulting failure is silent — no echo anywhere, and every
      caller concludes this ADAS predates the contract. 2.4's interceptor mapping is a Spring path pattern
      and is a different scope as well as a different language.
- [x] 2.3 Add `@RunnerContractExempt` (marker annotation in `web/contract/`, beside the interceptor that
      reads it) and apply it to `PipelineDataController.preview` and `refresh`.
- [x] 2.4 Add `RunnerContractInterceptor` + its `WebMvcConfigurer` registration (modelled on
      `CorrelationIdInterceptor` / `LoggingWebConfiguration`), mapped to the Spring path pattern
      `/v1/pipelines/*/**` — the pipeline-scoped prefix, **not** `/v1/pipelines/**`, which matches zero
      trailing segments too and would refuse `POST /v1/pipelines` registration: on a
      `POST /v1/pipelines/{name}/…` handler method that is not `@RunnerContractExempt`, require a matching
      request header in `preHandle` — before the pipeline lookup, the per-call grant check and any
      ClickHouse read. It sets no header; 2.2 owns that.
- [x] 2.5 Cross-reference the two path lists that must stay in step: a comment on
      `OidcSecurityConfiguration`'s four `authenticated()` runner paths naming the interceptor, and one on
      the interceptor naming them. They stay separate lists deliberately (design decision 3).
- [x] 2.6 Document the header on the four runner endpoints in their springdoc annotations so the published
      OpenAPI document names it (adas-mcp regenerates from that document).

## 3. Version-independent guards

- [x] 3.1 `EnrichWriteValidator`: require every row to name at least one of the pipeline's declared output
      columns — presence, not value, so an output column carrying `null` is accepted — rejecting with a new
      `WriteOutputColumnRequiredException` → 422 `write_output_column_required`, naming the pipeline and the
      requirement. Its own code rather than `write_column_not_allowed`, whose remedy is the opposite one.
      The guard cannot lock a pipeline out: `EnrichSpecValidator.deriveOutputBindings` refuses a
      declaration whose evaluator resolves to no target column and sets a non-blank `column` on every
      binding it derives, on both lanes, so the permitted-output set of a registered pipeline is never
      empty — assert that with a test rather than leaving it to be re-derived.
- [x] 3.2 `PipelineController`: refuse `?view=compiled` without `kind=enrich` (400 `bad_request` via
      `InvalidRequestParameterException`, naming the combination) and a compiled single read of a
      non-`enrich` pipeline (422 `pipeline_validation_failed`, naming the kind); remove the comment that
      records the silent-downgrade behaviour.

## 4. Runner-contact visibility

- [x] 4.1 Add `RunnerContactMetrics` (`service/enrichment/support/`): record a last-contact instant per
      `enrich` pipeline, publish staleness as a gauge derived at scrape time and registered lazily on first
      contact, count refusals tagged `required` / `mismatch`, and expose a `forget(pipeline)` that drops
      the series — following `AggregateMetrics`' watermark and deregistration reasoning. Record the instant
      in `PipelineDataController`, after each of the four handlers returns: the spec says *successfully*
      called, and that is the one point which is both after the outcome is known and common to all four —
      recording inside the four services would put it in four places and ahead of the response.
- [x] 4.2 Call `forget` from every path that stops a pipeline being called, next to the existing aggregate
      deregistration: `PipelineService.delete`, `PipelineService.patch` when it disables the pipeline, and
      `deletePipelineTargetingTable`. The last of these does not call `AggregateMetrics.forget` today — a
      live instance of the leak that method's javadoc says it exists to prevent — so fix it for both.

## 5. Tests

- [x] 5.1 `@WebMvcTest` slice: each of `scan`, `group-rows`, `materialize`, `rows` refused 409
      `runner_contract_required` with no header and 409 `runner_contract_mismatch` with a wrong value;
      served normally with a matching value; `preview` and `refresh` unaffected with no header.
- [x] 5.2 Assert the response header is present on the bare listing path `GET /v1/pipelines` (the feed's
      own path, which `/v1/pipelines/*` must match), on a single read, on a refusal, and — through the full
      filter chain rather than an MVC slice — on a `401` from an unauthenticated call. Assert the
      data-plane refusal happens before the pipeline lookup (an unknown pipeline name with no header still
      answers 409, not 404).
- [x] 5.3 Integration test: a write-back of grain + provenance only is refused 422
      `write_output_column_required` and writes no row of the batch; a row naming a forbidden column still
      answers `write_column_not_allowed`; a row naming an output column with a `null` value is accepted and
      stores `null`.
- [x] 5.4 Controller tests for the projection refusals: compiled listing without `kind=enrich` → 400,
      compiled read of an `aggregate` pipeline → 422, `view=source` and the default unchanged for every
      kind.
- [x] 5.5 Metrics test: no series before the first contact; staleness grows between observations with no
      call made; a refusal increments its tagged counter; deleting a pipeline removes its series, and so
      does disabling one.

## 6. Specs and docs

- [x] 6.1 Confirm the delta specs match what shipped (`enrichment-runtime`, `class1-enrichment`,
      `pipeline-registry`) and run `openspec validate enforce-runner-contract-version --strict`.
- [x] 6.2 Record the coordinated deploy in the deploy runbook: both orders after this
      change, and the DLQ requeue note for write-backs archived during an ADAS-first window. No
      `docs/configuration.md` row — this change adds no configuration property.
- [x] 6.3 Note in the change that the Enrichment Runner half (`analytics-enrichment-runner`) ships in the
      same window and is not optional, and that `adas-mcp` needs a docs-only follow-up: it passes `view`
      through and documents `compiled` as a no-op for an `aggregate` pipeline, which becomes a 422.

## 7. Verification

- [x] 7.1 `./gradlew clean build` passes (compile + checkstyle + tests).
