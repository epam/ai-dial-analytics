## 1. Declare, store and serve the knob

- [x] 1.1 Add `@JsonProperty("input_token_budget") Integer inputTokenBudget` to `PipelineRequests.AdvancedRequest`,
      with an `@Schema` description: `0` switches the runner's trimming off, a positive value is the pipeline's own
      ceiling, absent means the runner's default, and the member is valid only on a `group` pipeline
- [x] 1.2 Add `@JsonProperty("input_token_budget") Integer inputTokenBudget` to `EnrichSpec`, documented beside the
      other knobs (null means the runner's configured default)
- [x] 1.3 `PipelineMapper.toSpec`: store the value as sent, after refusing a negative one with
      `PipelineValidationException` naming `advanced.input_token_budget` (beside `sampleFractionOf`)
- [x] 1.4 `PipelineMapper.toAdvancedDto`: serve the member, and keep omitting `advanced` only when every knob,
      this one included, is null. Update every other `new AdvancedRequest(...)` call site
- [x] 1.5 Add `advanced.input_token_budget` to `ENRICH_ONLY` and to `enrichFields`, so an `aggregate` pipeline
      declaring it is refused as another kind's member

## 2. Group-only validation

- [x] 2.1 `EnrichSpecValidator.validateGroupTrigger`: refuse `advanced.input_token_budget` on a non-group trigger
      with `"advanced.input_token_budget is only valid for a group pipeline"`, beside the `trigger.group_by`,
      `trigger.ready_when` and `trigger.member_select` refusals

## 3. Raise the runner contract to 6

- [x] 3.1 `RunnerContract.VERSION_VALUE` → `"6"`, with a Javadoc line naming `advanced.input_token_budget` as the
      reason
- [x] 3.2 `RunnerContractShapeTest`: add `"input_token_budget: Integer"` to the `AdvancedRequest` golden list
- [x] 3.3 Update every test that pins the literal version (`RunnerContractHeaderIntegrationTest`,
      `PipelineDataContractTest`, `RunnerCalls`, `AppRoutePipelineIntegrationTest`, and any other hit of a pinned
      `5`) to `6`

## 4. Tests

- [x] 4.1 `PipelineMapperTest`: the member round-trips; `0` is served as `0`, and a block holding only it is not
      omitted; a negative value is refused naming the member; an `aggregate` declaration carrying it is refused as
      the `enrich` kind's member
- [x] 4.2 Validator test: a `schedule` pipeline declaring it is refused with 422 `pipeline_validation_failed`; a
      `group` pipeline declaring it is accepted
- [x] 4.3 Feed test: the enrich feed (`view=compiled`) and `GET /v1/pipelines/{name}` (`view=source`) both carry
      `advanced.input_token_budget`, and the response carries `Adas-Contract-Version: 6`
- [x] 4.4 Catalog export/import round-trip carries the member

## 5. Docs

- [x] 5.1 `docs/release-0.24-upgrade.md`: the contract now moves from `4` to `6` in this release (intro bullet,
      §9, summary table); add a short section for `advanced.input_token_budget` (group-only, `0` opts out, rollback
      drops it), and a summary row
- [x] 5.2 No `docs/configuration.md` change: the change adds no config property

## 6. Verify

- [x] 6.1 `openspec validate add-group-input-token-budget --strict`
- [x] 6.2 Run `.claude/skills/pr-ready/hygiene-sweep.pl` over the branch
- [x] 6.3 `./gradlew clean build`
