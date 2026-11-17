## Context

`advanced` carries a pipeline's execution knobs: `rows_per_scan`, `rows_per_call`, `rate_rpm` and `sample_fraction`.
`PipelineMapper.toSpec` stores each knob as sent into `EnrichSpec`, which is persisted as the pipeline's `spec` JSON.
`toAdvancedDto` serves them back, and omits the block when every knob is null. ADAS writes no default, because a
default echoed back would be indistinguishable from an operator's choice. Range checks run in the mapper; the
`sample_fraction` rule is the precedent. Group-only trigger members are refused on a `schedule` pipeline:
`trigger.group_by` and `trigger.ready_when` in `EnrichSpecValidator.validateGroupTrigger`, only when the pipeline is
armed, and `trigger.member_select` on every write, drafts included, in `PipelineService.requireNoTriggerContradiction`.

The Enrichment Runner trims a group's input to `group.input-token-budget`, estimated at four characters per token.
Its paired change reads `advanced.input_token_budget` as a per-pipeline override and speaks contract `6`.
`RunnerContractShapeTest` pins `AdvancedRequest`'s members, so adding one is a contract change by rule.

## Goals / Non-Goals

**Goals:**

- A group pipeline can switch the runner's trimming off, or set its own ceiling, and the runner receives the
  value in the enrich feed.
- An invalid or meaningless declaration is refused at write time, not passed to the runner.

**Non-Goals:**

- Any change to ADAS's own group caps (`enrichment.group-fetch.max-rows`, the response-byte cap,
  `member_select`).
- A default derived from the preset. The runner resolves an absent knob to its own configured value, whatever the
  preset.
- A real tokenizer, or any token accounting on ADAS's side.

## Decisions

### An integer in `advanced`, meaning what the runner's setting means

`input_token_budget` has the same shape and meaning as the runner's `GROUP_INPUT_TOKEN_BUDGET`: `0` is off and `N`
is the ceiling. One integer covers switching off and raising or lowering the ceiling per model or application.

- *Alternative: a boolean (`trim_input: false`).* Rejected. It covers switching off only, and a large-context model
  would need a second knob later.
- *Alternative: a member of `transform`.* Rejected. `transform` describes what is called, `transform.params` is
  overlaid onto the request body, and execution knobs live in `advanced`.

### Stored as sent, served as sent

Like the other knobs, a null is stored and served as absence. `toAdvancedDto` still omits `advanced` when every
knob, the new one included, is null. `0` is a value, not an absence, so it is stored and served.

### Negative values are refused with 422

The runner reads a negative value as absent, but that is a defensive fallback for a producer defect, not a
meaning. Refusing at write time keeps the stored declaration honest. The check sits in the mapper beside
`sample_fraction`'s and raises the same `PipelineValidationException` (`pipeline_validation_failed`).

### Group-only, refused on a `schedule` pipeline

Only the group lane has an input budget, so on a `schedule` pipeline the knob would be accepted and do nothing.
That is exactly why `trigger.group_by`, `trigger.ready_when` and `trigger.member_select` are refused there. This
knob follows `member_select` into `PipelineService.requireNoTriggerContradiction`, so it is refused on every write,
a disabled draft included, rather than only when the pipeline is armed: a draft is authored intent too, and the
member does nothing on a `schedule` pipeline whatever its state. A patch that changes a pipeline's trigger from `group` to
`schedule` must drop the knob in the same patch, as it must drop the other group-only members.

- *Alternative: accept it on any trigger as inert.* Rejected. An accepted member that changes nothing reads to its
  author as having taken effect.

### Contract `6`, not folded into `5`

Contract `5` (the `dial_app_route` preset) is not in a tagged release yet, so the knob could ride on it. It does
not. Folding is safe only if no build that speaks `5` without the knob can ever meet one that serves it, and a
version number cannot guarantee that once builds carrying `5` have been deployed. If such a pair met, a runner
would ignore the member, and a pipeline set to `0` would keep being trimmed with nothing to refuse the pair.
Raising to `6` keeps the rule `RunnerContractShapeTest` enforces, which makes no exception for unreleased versions:
any change to a pinned shape bumps the version.

## Risks / Trade-offs

- [The 0.24 release moves the contract two steps, `4` → `6`] → Operators see one coordinated deploy either way.
  The upgrade notes state the final number.
- [A pipeline moving from `group` to `schedule` now has one more member to drop] → The refusal names the member,
  as it does for the existing group-only members.
- [Rollback] → A build older than this change has no `input_token_budget` member in `AdvancedRequest`. Stored specs
  keep parsing because `EnrichSpec` tolerates unknown properties, but the knob is silently dropped on the next
  rewrite and is not served. Rolling back ADAS also means rolling back the runner to a contract it shares.

## Migration Plan

1. Deploy ADAS and the Enrichment Runner together on contract `6`, as for every bump. No catalog migration. The
   member changes the catalog bundle's pipeline shape, so the bundle `format_version` rises to `3` (see
   [`catalog-export-import`](../../../specs/catalog-export-import/spec.md)); an older ADAS refuses a new bundle
   readably.
2. Declare `advanced.input_token_budget` on the group pipelines that need it, for example `0` on a
   `dial_app_route` pipeline whose application trims its own input.
3. Rollback: roll both back together. The stored member is ignored by the older build.

## Open Questions

(none)
