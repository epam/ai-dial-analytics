## 1. Specs

- [x] 1.1 Update the Purpose paragraph of `openspec/specs/enrichment-registry/spec.md`: the runner syncs the unfiltered `GET /v1/pipelines?kind=enrich&view=compiled` and executes only `enabled: true` entries
- [x] 1.2 `openspec validate single-pipeline-run-control --strict`

## 2. Code

- [x] 2.1 `PipelineService.patch`: a patch whose only accepted change is `enabled` moves `updated_at` and does not bump `generation` or log a generation change; a re-declaration combined with an enable or disable still bumps once and logs `patch(re-declare,enable|disable)`; the enable and disable INFO lines report the current generation
- [x] 2.2 Tests: a flag-only patch leaves `generation` unchanged and moves `updated_at`; a combined patch bumps once; an enrich pipeline's materialized position is still current after a disable/enable cycle
- [x] 2.3 `PipelineService`: rewrite the `patch` Javadoc rationale that says the runner drops a disabled pipeline from its cache and its dead-letter items lose their labels, and the disable log comment, to describe the runner following `enabled` and parking queued work
- [x] 2.4 `RunnerContactMetrics`: rewrite the class Javadoc that says the runner's feed is `enabled=true`; note that a breaker pause is not reported to ADAS
- [x] 2.5 Controller OpenAPI text for `GET /v1/pipelines` that names `kind=enrich&enabled=true&view=compiled` as the runner's feed: name the unfiltered listing
- [x] 2.6 Test: `GET /v1/pipelines?kind=enrich&view=compiled` returns an armed-then-disabled pipeline with `enabled: false` and the same resolved entry, and omits a deleted one (add only if no existing test pins it)
- [x] 2.7 Test: disabling and re-enabling an `aggregate` pipeline keeps its cursor (add only if no existing test pins it) — added `stoppingAndRestartingAnAggregateKeepsItsCursor`
- [x] 2.8 Confirm the only `PipelineConflictException` reachable from `…/rows` and `…/materialize` is the disabled refusal, and pin it with a test asserting `pipeline_conflict` there for a disabled pipeline — confirmed: only `requireEnabledToWrite` (EnrichWriteService, MaterializeService); already pinned by `DraftPipelineIntegrationTest`
- [x] 2.9 `PipelineDto` OpenAPI text for `generation` ("bumped on every accepted mutation") and `enabled`, and the `PipelineEntity`/`PipelineDto` Javadoc that calls `generation` bumped on every mutation: describe it as the declaration's revision, untouched by a flag-only patch
- [x] 2.10 An enable whose arming gate stores a derived member other than the stored one still bumps once (`patch(re-derive,enable)`), compared through the codec; tests for both outcomes

## 3. Docs

- [x] 3.1 Upgrade note in the upgrade document of the release that ships this change: before upgrading the Enrichment Runner, disable in ADAS each pipeline its `GET /v1/pipelines/paused` lists with origin `operator`
- [x] 3.2 `docs/release-0.2-upgrade.md` mentions the `enabled=true` feed as historical context; leave it, as it describes that release
- [x] 3.3 Public-repo hygiene sweep of the change and edited files (`git grep -nIP` per CLAUDE.md)

## 4. Verification

- [x] 4.1 `./gradlew clean build`
