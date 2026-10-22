## Why

The `cached_prompt_tokens` column has been silently NULL for every ingested row. Its data-sync
mapping points at a typo'd source path (`token_usage.prompt_token_details...`) that the DIAL producer
never emits — the real key is `token_usage.prompt_tokens_details.cached_tokens` (plural "tokens"). The
path resolves to a missing node and, because the column is `nullable`, the engine writes NULL instead
of the reported cache-hit count. A client relying on cached-token accounting sees no data.

## What Changes

- Correct the `cached_prompt_tokens` source path in `src/main/resources/mapping/dial-usage-log.yaml`
  from `token_usage.prompt_token_details.cached_tokens` to
  `token_usage.prompt_tokens_details.cached_tokens`.
- Correct the `MappingEngineTest` fixture and assertion, which currently bake the same typo
  (`prompt_token_details`) and so kept the test green against the wrong path.
- No schema/DDL change, no new configuration, no change to any other column.
- Fix-forward only: historical rows keep their stored NULL. Backfilling corrected values, if needed,
  is out of scope and handled separately via the backfill endpoint.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `data-sync`: add a behavioral regression guard requiring that a producer-reported cached prompt
  token count is persisted to `cached_prompt_tokens` (rather than always NULL). The guard is phrased
  in terms of observable output, not a hard-coded JSON path, so it does not conflict with the
  configurable-mapping requirement.

## Impact

- `src/main/resources/mapping/dial-usage-log.yaml` — one-line source path correction.
- `src/test/java/com/epam/aidial/analytics/sync/mapping/MappingEngineTest.java` — fixture + assertion
  corrected to the real producer path.
- Runtime: `cached_prompt_tokens` starts carrying the producer's reported value for newly ingested
  records. No effect on any other column, the ingest control flow, or dedup.
