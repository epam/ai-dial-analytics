## Context

`cache_creation_tokens` is a derived column. `mapping/dial-usage-log.yaml` binds it to the
`json-extract-uint` deriver over `sources: [assembled_response, response.body]` at
`args.path: usage.cache_creation_input_tokens`, and the table declares it `UInt32` (non-nullable).

`DeriverRegistry.extract` walks the sources in order, and `asDocument` accepts a source only if it is
already an object/array or if `jsonMapper.readTree` parses its text. When neither source yields the
path, the deriver returns null, `MappingEngine` applies the absent/blank fallback, and a non-nullable
`long` column takes the kind's zero value. Every failure mode of this column therefore terminates in a
stored `0` that is indistinguishable from a measured zero — nothing logs, nothing fails.

Three shapes reach ingest, and the configured path matches one:

| shape | `assembled_response` | where the count lives |
|---|---|---|
| non-streaming Anthropic-native | the message object | `usage.cache_creation_input_tokens` ✅ |
| non-streaming / SSE, DIAL-normalized | normalized message | `usage.prompt_tokens_details.cache_write_tokens` |
| Anthropic-native stream | **verbatim SSE transcript** | `message.usage…` inside `message_start`, unparseable as a whole |

Both misses are silent, and they compound: hundreds of rows can report a non-zero cache write in the body
while only a handful store one. The second row of the table is a naming miss; the third is a parse miss that also
empties `response_id` on nearly every such row.

What all three shapes share is the record's own `token_usage` object. DIAL populates it from its
adapter, not from the wire format, so it is present for streamed and non-streamed calls alike:
`token_usage.prompt_tokens_details = {cached_tokens, cache_write_tokens}`. The adjacent
`cached_prompt_tokens` column already reads `cached_tokens` from it, and its stored values match the
body's `cached_tokens` exactly on streamed rows — direct evidence that the object is populated on the
path where body derivation fails.

## Goals / Non-Goals

**Goals:**

- Populate `cache_creation_tokens` for every response shape, without parsing response payloads.
- Stop conflating "the producer reported nothing" with "the producer reported zero".
- Keep the catalog's `nullable` flag in agreement with the physical column, as `table-management`
  requires.

**Non-Goals:**

- No backfill of historical rows. They keep their stored `0`; correcting them is `data-sync-backfill`'s
  job, on the operator's schedule.
- No SSE-aware deriver, no multi-path deriver, no change to `DeriverRegistry`, `MappingEngine`, or the
  ingest control flow.
- No fix for `reasoning_tokens`, which has the same streaming blind spot but no `token_usage` source to
  move to (the observed records carry no `completion_tokens_details`). It needs a different fix; this
  change does not pretend to be it.
- No change to `V1__create_dial_usage_log.sql` — an applied migration's checksum is fixed.

## Decisions

### Map from `token_usage`, do not derive from the response

Replace the derived entry with a direct mapped column:

```yaml
- column: cache_creation_tokens
  source: token_usage.prompt_tokens_details.cache_write_tokens
  kind: long
  nullable: true
```

This makes the column structurally identical to its sibling `cached_prompt_tokens`, which reads the
neighbouring key of the same object. The value stops depending on transport (streamed or not), on
upstream API dialect (Anthropic-native or DIAL-normalized), and on whether a payload happens to parse.

*Alternative — teach the deriver a second path* (`usage.prompt_tokens_details.cache_write_tokens` as a
fallback): rejected. It fixes only the naming miss; every Anthropic-native stream — the largest failing
population — still yields `0`, because the sources do not parse at all.

*Alternative — parse the SSE transcript* (split on `data:` lines, read `message_delta`'s usage):
rejected. It is a new deriver, a new class of format coupling, and materially more expensive per record
— to recover a number the record already states in a stable field.

### Make the column `Nullable(UInt32)`

Three of the four records in the samples carry `prompt_tokens_details`; one omits it entirely. Under a
non-nullable column that record stores `0`, which reads as "no cache write happened". Nullable is what
the sibling token columns already do (`prompt_tokens`, `completion_tokens`, `total_tokens`,
`cached_prompt_tokens` are all `Nullable(UInt32)`), and it is what the aggregate pipeline's
nullable-measure rule reasons from — a `sum(cache_creation_tokens)` over an empty bucket should surface
NULL, not a zero someone will believe.

The mapping's `nullable: true` and the physical type must move together: `SchemaValidator` rejects the
mismatch in either direction at startup. That coupling is the point — it is what stops a half-applied
deployment from ingesting silently wrong data.

*Alternative — keep `UInt32`*: rejected. It preserves exactly the ambiguity this change exists to
remove, and leaves `cache_creation_tokens` the only token column whose zero is unreadable.

### `V6` alters, `V1` stays untouched

New `V6__dial_usage_log_nullable_cache_creation_tokens.sql`:

```sql
ALTER TABLE dial_usage_log
    MODIFY COLUMN cache_creation_tokens Nullable(UInt32) CODEC(T64, ZSTD(1));
```

The `CODEC` is restated deliberately: `MODIFY COLUMN` replaces the column declaration, so omitting it
would silently drop back to default compression. `Nullable(UInt32) CODEC(T64, ZSTD(1))` is already
proven on this table — `cached_prompt_tokens` is declared exactly that way in `V1`.

A fresh install runs `V1` then `V6` and arrives at the same shape as an upgraded one, which is the
pattern `V5` established for `rate_analytics`.

### Catalog alignment as a paired PostgreSQL migration

`V23__dial_usage_log_cache_creation_nullable.sql` sets `column_mapping.nullable = TRUE` for
`(dial_usage_log, cache_creation_tokens)`. Same shape as `V21`, which aligned the other eleven columns.
It issues no DDL — the catalog describes the physical column, it does not create it.

### The deriver registry keeps `json-extract-uint`

`reasoning_tokens` still uses it, and so do `response_id`, `mcp_method`, and `mcp_tool_call_name`. Only
this column's binding changes. The spec delta records the general lesson — a value the producer also
reports outside the payload is mapped, not derived — without removing the deriver.

## Risks / Trade-offs

- **The type change schedules a mutation over existing parts** → Measured, not assumed: on ClickHouse
  25.3, `T` → `Nullable(T)` hardlinks the existing column files and writes only the added null-map
  subcolumn, so it is far cheaper than a type change usually implies. Against a scratch
  `ReplacingMergeTree` of 24M rows in 4 parts (~95 MiB) the `ALTER` returned in 46 ms wall-clock, the
  mutation was already done when queried, `part_log` showed the `MutatePart` events at 13 ms total with
  0 rows rewritten, part size was unchanged, and reads summed to exactly the pre-`ALTER` total with no
  NULLs invented. Cost still scales with part count, so run it deliberately on a large deployment — but
  it is not the off-peak event `V3`'s `MATERIALIZE INDEX` is.
- **Operators with `clickhouse.migrations.enabled=false`** → Startup fails until `V6` is applied out of
  band, because `SchemaValidator` sees `nullable: true` against a bare `UInt32`. This is the designed
  fail-fast, and the spec delta now states it as a scenario; call it out in the release note so it is
  not discovered at deploy time.
- **Consumers that assumed non-null** → Reads can now return NULL. This is the same tolerance already
  required for four sibling token columns, and schema discovery will publish the flag, so a consumer can
  see it. The aggregate pipeline will reject a `sum` into a non-nullable target — a rejection, not a
  silent wrong number.
- **The column becomes bimodal across the fix boundary** → Rows ingested before this change carry `0`
  where they should carry a value; rows after carry the value or NULL. A time-series over
  `cache_creation_tokens` will show a step at deploy. Backfill is the remedy and it is out of scope
  here, so the step must be stated in the release note rather than left for someone to discover in a
  chart.
- **`token_usage` is the producer's accounting, not the upstream's wire value** → If DIAL ever stops
  populating `cache_write_tokens`, the column goes NULL rather than wrong, and the behavioral spec
  scenario (not the literal path) remains the durable contract. That is the right failure direction.

## Migration Plan

1. Deploy with migrations enabled: Flyway applies `V6` (ClickHouse) and `V23` (PostgreSQL) at startup,
   ordered before `SchemaValidator`, which then reconciles the mapping's `nullable: true` against the
   now-`Nullable` column.
2. Operators with migrations disabled apply `V6` out of band **before** rolling the service.
3. Verify on a live instance: a streamed Anthropic-native record ingests with a non-zero
   `cache_creation_tokens`; a record without `prompt_tokens_details` ingests as NULL.
4. Optional and separate: backfill historical rows via the `data-sync-backfill` endpoint, off-peak.

**Rollback**: revert the mapping entry and the catalog flag; the physical column may stay `Nullable`
(the mapping would then have to keep `nullable: true`, or `V6` be reverted with a matching
`MODIFY COLUMN … UInt32`, which converts stored NULLs to `0`). Reverting only the service without the
catalog leaves discovery overstating nullability — harmless in the safe direction, but do both.

## Open Questions

- Should the historical backfill be scheduled as part of this rollout, or left to the operator? The
  change assumes the latter, matching the `cached_prompt_tokens` precedent.
- Pre-existing gap, noted not fixed: the archived `2026-07-09-fix-cached-prompt-tokens-mapping`
  requirement ("Reported cached prompt tokens are persisted") is not present in
  `openspec/specs/data-sync/spec.md`. It appears to have been lost rather than superseded. Worth a
  separate sync; this change adds its sibling requirement and does not silently restore it.
