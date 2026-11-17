## Why

Under `sync.request-body-mode=delta` a body that does not parse as a chat request is stored whole. A chat body cut
at the 4 MiB limit is such a body, and once a conversation has outgrown the limit every later turn sends the same
cut text, so the largest bodies a deployment ingests are stored again on every turn and delta storage saves
nothing on them.

## What Changes

- A request body that is not JSON and holds at least 65,536 UTF-16 code units gets a **body hash** over its
  project, user, UTC day and exact text. A JSON body of any shape is never hashed this way, so its top-level
  members stay readable from `request_body`.
- Under `delta`, the first such request keeps its body and carries the hash in `request_hash`; a later request
  with the same hash is stored with an empty `request_body` and the hash in `request_parent_hash`.
- Under `whole-with-path`, such a row carries its hash and its whole body, so a rollback from `delta` keeps copies
  readable.
- `dial_usage_log_payload.request_body_full` returns a copy's body from the row that holds it, as `rebuilt`.
- No new setting, column, ClickHouse migration or endpoint. One PostgreSQL migration rewords the catalog
  description of `request_body`. Bodies below the size bound and rows stored before the change are untouched.

Not in this change: reading a truncated body leniently so it can join a chat chain. Truncated bodies that share a
start but differ before the cut would need it; exact repeats do not.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`: "The request body storage mode is configurable" and "A request body that is not a chat request is
  stored whole" gain the exception; "A rebuilt request body is readable beside the stored one" gains the copy
  case; "Pipelines binding the stored body are re-pointed before delta storage is enabled" gains the upgrade
  note; "A parent never crosses a UTC day or a user scope" and "Cache markers are kept per event" are scoped so
  they stay true of a copy; a new requirement, "A large request body equal to an earlier one is stored
  once", defines the body hash, the carrier and copy rows, and how a copy reads back.

- `table-management`: "Seeded payload enrichment on dial_usage_log" gains the reworded `request_body`
  description.

## Impact

- Ingest: the request-delta encoder and its payload-table lookup (`sync/delta`). A flush carrying an eligible body
  runs one more lookup statement, deduplicated, for the carriers of its hashes.
- Read: the request body rebuild gains one case; the two-phase chain read, its bounds and its statements are
  unchanged.
- Stored data: `request_body` can now be the empty string on a row with a parent. A consumer reading
  `request_body` directly sees it; `request_body_full` returns the body.
- `docs/release-0.24-upgrade.md` gains the note for deployments already running `delta`, and
  `docs/heavy-columns.md` the copy case. No `/v1` contract or config key changes; the catalog changes in one
  description only.
