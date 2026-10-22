## Context

`cached_prompt_tokens` is a `Nullable(UInt32)` column in `dial_usage_log`, mapped in
`src/main/resources/mapping/dial-usage-log.yaml` as a scalar `long` from a dotted JSON path under
`token_usage`. The declared source path is `token_usage.prompt_token_details.cached_tokens`, but the
DIAL producer (`GfLogStore`) emits the count under `token_usage.prompt_tokens_details.cached_tokens`
(plural `tokens`). `JsonPaths.walk` resolves the wrong path to a missing node; `MappingEngine.resolve`
then treats the value as absent and, because the column is `nullable`, applies the absent/blank
fallback and emits JSON `null`. Result: every ingested row has stored NULL regardless of the reported
value.

The defect is self-consistent: `MappingEngineTest` seeds its fixture with the same misspelling
(`"prompt_token_details":{"cached_tokens":42}`) and asserts `42`, so the unit test passes while
exercising the wrong path and never catches the mismatch with the real producer output.

## Goals / Non-Goals

**Goals:**
- Correct the default mapping so `cached_prompt_tokens` reads the producer's real path.
- Make the test exercise the producer's real key so it guards the behavior instead of the typo.

**Non-Goals:**
- No schema/DDL change, no new configuration, no change to any other column or to the ingest control
  flow, dedup, or writer.
- No historical backfill. Rows ingested before this fix retain their stored NULL; correcting them, if
  needed, is handled separately via the backfill endpoint.

## Decisions

- **Correct the source path in the shipped default mapping** from
  `token_usage.prompt_token_details.cached_tokens` to
  `token_usage.prompt_tokens_details.cached_tokens`. This is the single behavioral fix; no engine code
  changes, since the engine already resolves and coerces the path correctly once it is spelled right.
- **Fix the `MappingEngineTest` fixture and assertion to the real key.** The test currently locks in
  the bug; correcting the fixture key (`prompt_tokens_details`) turns it into a genuine guard for the
  spec's "reported cached prompt tokens are persisted" scenario. Keep the present-value and
  absent→null cases so both branches are covered.
- *Alternative — leave the test fixture as-is and only fix the YAML*: rejected. The test would then
  assert a value via a path the shipped mapping no longer uses, giving false confidence and re-opening
  the door to the same class of typo.

## Risks / Trade-offs

- **Historical NULLs remain** → Accepted and explicitly out of scope; fix-forward with an optional
  separate backfill. Documented in the proposal.
- **Producer path assumption** → The corrected path matches the client-provided fresh log record
  (`token_usage.prompt_tokens_details.cached_tokens`). Low risk; if the producer ever renames it, the
  behavioral spec scenario (not the literal path) is the durable contract.
