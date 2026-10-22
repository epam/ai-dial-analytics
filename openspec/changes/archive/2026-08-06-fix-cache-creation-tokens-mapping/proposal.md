## Why

`cache_creation_tokens` is derived at ingest from the response payload at the path
`usage.cache_creation_input_tokens` (Anthropic-native naming), read from `assembled_response` falling
back to `response.body`. That path exists in exactly one of the shapes DIAL actually produces — a
**non-streaming** Anthropic-native `/anthropic/v1/messages` response — so for every other call the
column silently stores `0` while the record plainly reports a cache write.

Measured on a sample of tens of thousands of rows: **hundreds of rows carry a non-zero cache-write count in their response body,
and only a handful of them have a non-zero `cache_creation_tokens`.** The three failing populations:

| response shape | rows with a body-reported cache write | column populated |
|---|---|---|
| non-streaming Anthropic-native | a few | some |
| `data:` SSE / plain JSON, DIAL-normalized | hundreds | 0 |
| `event:` SSE, Anthropic-native stream | hundreds | 0 |

Two independent causes, both silent:

- **The normalized shape uses a different key.** DIAL's own usage object spells it
  `usage.prompt_tokens_details.cache_write_tokens`. The configured path is absent from that document,
  so the deriver returns null and the non-nullable column falls back to `0`.
- **Streamed responses are not JSON documents.** For an Anthropic-native stream, `assembled_response`
  is the verbatim SSE transcript (`event: message_start\ndata: {…}`), not an assembled message.
  `DeriverRegistry.asDocument` fails to parse it, both sources are skipped, and the column falls back
  to `0` again. The same failure empties `response_id` on 8,783 of 8,784 such rows.

Verified on two raw log files. Span `79404839a1faff54` (non-streaming) stores 26409; span
`94f69c895caf387e` (`stream: true`, same endpoint, same model family) stores `0` although the value
1215 appears four times in its record — twice in the SSE transcript, once in the Bedrock invocation
metrics, and once in `token_usage.prompt_tokens_details.cache_write_tokens`.

That last copy is the fix. `token_usage` is DIAL's normalized accounting, populated regardless of
streaming or upstream API shape — the adjacent `cached_prompt_tokens` already reads from it, and its
stored values match the body's `cached_tokens` exactly on streamed rows.

## What Changes

- Re-point `cache_creation_tokens` in `mapping/dial-usage-log.yaml` from the `json-extract-uint`
  deriver over `assembled_response`/`response.body` to a **direct mapped column** sourced from
  `token_usage.prompt_tokens_details.cache_write_tokens`. No body parsing, no dependence on streaming.
- Make the column `Nullable(UInt32)` (ClickHouse `V6`), so "the producer reported nothing" stops
  masquerading as a measured zero — the distinction the sibling token columns already keep, and the
  one the aggregate pipeline's nullable-measure rule reasons from. A record whose `token_usage` omits
  `prompt_tokens_details` entirely (observed) now stores NULL rather than `0`.
- Align the catalog seed (`nullable = true`, PostgreSQL `V23`), as
  `table-management`'s "Seeded column nullability matches the physical table" requires.
- Fix `MappingEngineTest`, whose fixture supplies the Anthropic-native body shape and therefore keeps
  the wrong path green.

Not breaking: the column keeps its name, type family, and meaning. Reads that assumed non-null must
tolerate NULL — the same tolerance already required for `prompt_tokens` and `cached_prompt_tokens`.

Fix-forward only. Historical rows keep their stored `0`; correcting them is the backfill endpoint's
job (`data-sync-backfill`) and is out of scope here.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`: `cache_creation_tokens` moves out of the calculated (derived) column set into the
  direct columns carried from the source, and gains a behavioral requirement that a producer-reported
  cache-write count is persisted — the sibling of the existing cached-prompt-token guarantee. The
  table-provisioning requirement's calculated-column list and its `Nullable` declaration change with
  it.
- `table-management`: the enumerated set of `dial_usage_log` columns seeded `nullable = true` gains
  `cache_creation_tokens`.

## Impact

- `src/main/resources/mapping/dial-usage-log.yaml` — one column entry: `derive`/`sources`/`args`
  replaced by `source`, plus `nullable: true`.
- `src/main/resources/db/migration/clickhouse/V6__dial_usage_log_nullable_cache_creation_tokens.sql`
  (new) — `MODIFY COLUMN cache_creation_tokens Nullable(UInt32)`. This schedules a mutation over
  existing parts, but `T` → `Nullable(T)` hardlinks the stored values and writes only the added
  null-map subcolumn — measured on ClickHouse 25.3 at 13 ms of mutation work over 24M rows, with the
  `ALTER` itself returning in 46 ms. Run it deliberately on a large deployment (cost scales with part
  count), not as an off-peak event. Operators running with
  `clickhouse.migrations.enabled=false` must apply it out of band **before** deploying, or
  `SchemaValidator` aborts startup on the nullability mismatch (that fail-fast is the intended
  behavior, not a regression).
- `src/main/resources/db/migration/clickhouse/V1__create_dial_usage_log.sql` — unchanged (an applied
  migration's checksum is fixed); `V6` complements it, so a fresh install runs `V1` then `V6`.
- `src/main/resources/db/migration/postgres/V23__dial_usage_log_cache_creation_nullable.sql` (new) —
  catalog `nullable` flag.
- `src/test/java/com/epam/aidial/analytics/sync/mapping/MappingEngineTest.java`,
  `SchemaValidatorTest.java` — fixtures and assertions.
- `docs/configuration.md` — the default-mapping description moves `cache_creation_tokens` from the
  calculated list to the direct list.
- Runtime: newly ingested rows carry the value on every shape (streamed or not, Anthropic-native or
  DIAL-normalized). No effect on dedup, on the ingest control flow, or on any other column.

Out of scope, deliberately: `reasoning_tokens` has the same streaming blind spot, but no equivalent
`token_usage` source exists — the observed records carry no `completion_tokens_details` — so it cannot
take this fix and needs a separate, SSE-aware one.
