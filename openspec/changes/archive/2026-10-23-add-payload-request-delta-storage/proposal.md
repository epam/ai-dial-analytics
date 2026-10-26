## Why

Every request of a chat or agent session re-sends the whole conversation so far — system prompt, earlier
user turns, earlier assistant replies and tool results — and `dial_usage_log_payload.request_body` stores
each one whole. A session of N turns therefore stores on the order of N²/2 message copies, and request
bodies dominate the payload table's storage. The blobs are written by infrastructure outside this service
and existing blobs are never rewritten, so the repetition can only be removed here, at ingest.

## What Changes

- Ingest can store a chat request in `request_body` cut to the messages its parent request did not already have,
  without the system prompt and the `tools` it shares with that parent, and link it to that parent, instead of
  storing the whole body. The first request of a conversation is stored complete. The parent is found by a chained hash over the canonical messages, so two requests share stored
  history only when their whole history up to that point is identical — never on equal message text alone.
- The link never crosses a UTC day (the table's partition and TTL unit) nor a
  `(project_id, user_hash)` scope, so a parent never expires before its child and a request
  never links to another user's history.
- `cache_control` markers are left out of the hash and the stored history, and kept per event in their own column,
  so every request remains rebuildable with exactly the markers it was sent with.
- Bodies that are not chat-shaped (MCP, embeddings, anything unparseable, bodies truncated at 4 MiB) are
  stored whole in `request_body`, as today.
- New ingest setting `sync.request-body-mode` (`SYNC_REQUEST_BODY_MODE`): `whole` (default, today's
  behaviour) or `delta`. Under `whole` a chat row still carries its hidden path, so rolling back to `whole`
  never breaks a chain and a later switch to `delta` links onto earlier rows.
- ClickHouse migration: new columns (and a skip index for the parent lookup) on the existing
  `dial_usage_log_payload`; its sort key, partitioning and TTL are unchanged. Existing rows are not touched.
- Catalog migration: the payload enrichment exposes one new sensitive, heavy column, `request_cache_markers`,
  under the qualified name `"dial_usage_log_payload.request_cache_markers"`, and the seeded `request_body`
  description mentions the delta form. The chain columns stay hidden.
- **BREAKING (only under `delta`)**: for a later turn of a chat written in delta form, `request_body` holds only
  the messages that turn added, and no system prompt or `tools` when its parent has the same. A query, evaluator
  or pipeline that reads the full message history or the tools of such a turn from
  `"dial_usage_log_payload.request_body"` no longer finds them there; top-level fields such as `model` read as
  before.
- Not in this change: a single-event full rebuild (endpoint or SQL function), converting existing rows,
  changing the payload table's sort key, and deduplicating response bodies. This change stores everything
  the rebuild needs.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`: the request body may be stored as its new history + parent link, inheriting unchanged tools; new setting
  `sync.request-body-mode`; parent resolution, scope and day rules; cache-marker preservation; bodies
  stored whole; the payload table's declared columns grow.
- `table-management`: the seeded payload enrichment exposes one more column.

## Impact

- **Ingest:** the payload sink resolves parents at flush time (batch, then an in-memory cache, then one
  batched ClickHouse lookup per flush, sent in bounded chunks). Runs after the inline-file rewrite, so dropped files never enter a
  hash.
- **ClickHouse:** one migration adding columns and a skip index to `dial_usage_log_payload` (next free
  version).
- **Catalog (Postgres):** one migration adding a `column_mapping` row to `dial_usage_log_payload` and extending
  the seeded `request_body` description (next free version).
- **Config:** `SYNC_REQUEST_BODY_MODE` → `docs/configuration.md`.
- **Readers:** query surfaces (DSL, REST SQL, Flight SQL), enrichment pipelines, evaluators and aggregate
  refresh see the new qualified column through the existing grain join; nothing in them changes. Under
  `delta`, readers of a later turn's whole message history or tools wait for the rebuild.
- **Rollback:** setting the mode back to `whole` stops new delta rows; rows already written in delta form
  keep their cut `request_body` and are not reassembled until the rebuild ships.
