## Context

See proposal.md for the motivation. The facts that shape the approach:

- Ingest reads gzip NDJSON blobs, maps each record through stateless `MappingEngine.toRow` per target, and
  buffers rows per table in a `RowBatchSink`. `RunSink` flushes the payload sink before the
  `dial_usage_log` sink. One `JSONEachRow` INSERT per flush, no insert dedup tokens; duplicates collapse in
  the payload table's `ReplacingMergeTree(_updated_at)` on `ORDER BY event_id`.
- Rows arrive in blob listing order, not time order. Several instances can ingest the same blob at once;
  correctness relies on idempotent rewrites plus the `ReplacingMergeTree` collapse.
- The inline-file rewrite (`sync.inline-files-drop-truncated`, `sync.inline-files-max-size`) already runs inside the
  mapping, on the column value.
- The payload table is `PARTITION BY toYYYYMMDD(request_time)` with a 90-day TTL and
  `ttl_only_drop_parts = 1`; whole days are dropped.
- The record carries `project.id`, `deployment` and `user.id`, and, when the client sent one, the session id
  DIAL Core writes as `session.id`.
- Readers reach payload columns only through the catalog's grain join under qualified names.

An offline measurement on a sample of real traffic, run before this change, showed the following. Almost
every message in a chat request repeats an earlier request of the same conversation. Per-event delta
storage cut request-side storage by several times under the current `ORDER BY event_id`, and by about an
order of magnitude more combined with a session-ordered sort key. Removing `cache_control` before hashing
decided whether consecutive turns linked at all.

## Goals / Non-Goals

**Goals:**
- Deterministic, scope-bounded chain identity that needs no session id and no arrival order.
- No change to the payload table's sort key, partitioning or TTL, and no rewrite of existing rows.
- Bounded ingest cost: no per-record database round trip, and at most one lookup per payload flush, sent in
  bounded chunks.

**Non-Goals:**
- Reading a rebuilt full body (a later change), and re-keying the payload table (a later change, the other
  half of the storage gain).
- Byte-exact reconstruction: rebuilds are JSON-equal, not byte-equal.
- Cross-conversation sharing of system prompts or `tools` (a shared-parts table). Inheriting `tools` along a
  chain is in scope (D9); sharing them between conversations can be added later without changing chains.

## Decisions

### D1. One row per event, new history + parent (not one row per message)

A per-message node table shares more in raw bytes. Measured after compression, though, it was no smaller
than per-event, and it writes more rows and changes the row model. Per-event keeps exactly one payload row
per `dial_usage_log` row, so the grain join, the flush order and the drop rule are untouched.

### D2. Chain identity is a chained hash with the scope, the day and the tools in the seed

`p0 = H(project_id, user_hash, day, tools)`, `pk = H(p(k-1), canonical(hk))`; D9 says why `tools` is there. Putting
the scope and the UTC day into `p0` makes the "never cross a user or a day" rule structural: there is nothing to filter, because a hash from
another scope or day cannot match.
- The payload row carries the record's `project_id`, `deployment`, `user_hash` and `session_id` as plain mapped
  columns. The encoder hashes `project_id` and `user_hash` into `p0`; the lookup needs no scope filter, since the scope
  is in every hash. Plain columns rather than a hash of them, because a future session-ordered sort key needs real
  values to sort and prune on.
- Deployment is left out of the scope: a user switching deployment mid-conversation keeps one chain. The client session
  is left out too: it is client-supplied, may be absent, change or vanish mid-conversation, and in the scope would
  only cut links. It is stored (`session_id`) for the sort key, where it groups a conversation's turns.
- `H` is SHA-256 truncated to 128 bits, from the JDK, so there's no new dependency. A collision would need
  two different histories in one user's day, which is negligible at 128 bits.

Rejected alternatives:
- **Session scope:** not available at ingest.
- **A global scope:** cross-user rows would make rebuilds read other users' data, which conflicts with
  row policies.
- **Cross-day parents:** a child would outlive its parent under daily TTL drops, and fixing that would
  need a re-rooting job.

### D3. Canonical form and normalization

The value is parsed with Jackson into a tree. Each history element is serialized with sorted keys and no
whitespace, after removing `cache_control` members recursively and recording their location (index,
JSON Pointer inside the element) and value. The stored body is serialized the same way, with the history
elements spliced in as their canonical text. Only history elements lose markers; the rest of the body keeps its
own markers (for example on `tools`) as they are.

The canonical form is stable only within a release. If a later release changes it, the first request of
each conversation after the deploy becomes a root, and nothing breaks.

### D4. Parent resolution runs in the payload sink at flush, after mapping

The mapping stays per-record and stateless. It still emits `request_body` after the inline-file rewrite,
plus the identity columns. A new request-body encoder runs when the payload sink flushes. Under `whole` it only stamps
each chat request with its path and depth (D7). Under `delta`, for each flush it:
1. Parses and classifies each `request_body`, and computes its prefix hashes. Non-chat bodies pass
   through untouched.
2. Sorts chat rows by `request_time`, then resolves each against:
   - (a) paths already resolved in this flush,
   - (b) the instance's path cache,
   - (c) one lookup per flush for the rows (a) and (b) cannot link at all: `SELECT request_path,
     request_ancestors FROM dial_usage_log_payload WHERE request_time in the days' range AND
     toYYYYMMDD(request_time) IN (…) AND request_path IN (…)`. It reads without `FINAL`, because any copy of a
     path is equivalent. It asks about each row's 8 deepest prefixes (`TABLE_CANDIDATES`), and about 16,384
     prefixes per flush at most (`MAX_TABLE_CANDIDATES`); a row beyond that is a root. A typical follow-up's
     parent is 1–3 elements shallower, so a deeper miss falls back to a root.

     The client sends query parameters in the request URI, which the server bounds, so the candidates go out
     in chunks of 1,000 per query. A lookup that fails is logged and counted, and its rows become roots: a
     read problem never fails a flush. Rejected: failing the flush, because a lookup that fails the same way
     on every retry (a too-long URI, say) would stall ingest until the mode is switched back.
3. Rewrites each row's request columns (the parent is the deepest match with `k < n`, see D6), cuts `request_body` to what the parent does not supply (D9), and hands the batch to the existing
   writer.
4. Adds the flush's paths to the cache **only after the INSERT succeeds**. A path therefore becomes a
   parent candidate only once it's stored (step 2a covers the same insert).

Rejected: resolving in `MappingEngine`, which is per-record with no access to the batch, the cache or
ClickHouse. Also rejected: resolving in `BlobIngestor` before the sink, which would duplicate the sink's
batching and break the flush-order contract.

### D5. The path cache stores linked entries, not arrays

Each cache entry is `(path, parentEntry)`, so ancestors are rebuilt by walking references and are never
copied per entry. The cache is a bounded LRU keyed by path (`PATH_CACHE_ENTRIES`, 200k). An evicted parent is
still reachable through its children's references, so walking stays correct. A node read from the table
carries that row's ancestor hint packed as two longs per path. It enters the cache only as the parent of a
node written in the flush, never on its own, so the cache holds one small entry per written row. Memory is a
few hundred bytes per entry, plus at most one packed hint (128 paths, 2 KiB) per conversation that was linked
through the table.

### D6. The chain is the parent links; `request_ancestors` is only a read hint

Rows sharing a path can differ in parent and delta: a re-ingested event may link to a parent that has been
stored since, and an event stored as a root can share its path with an identical one stored as a child. A
stored ancestor list could therefore name a link that no longer exists. A rebuild follows `request_parent`
through whichever row it finds for each path instead. Every row with a given path rebuilds to the same history,
and each parent is strictly shallower (the parent's depth `k` is always below `n`, and a row is never its own
parent), so the walk terminates and is exact. `request_ancestors` remains useful for reading the whole chain
in one `IN` query in the common case. It is capped at the 128 nearest ancestors: being a hint, it need not be
complete, and a rebuild reads further only where a chain is longer. Uncapped, it grows quadratically with chain
length; even so, its cost under `ORDER BY event_id` was measured at a few percent of the request side.

Rejected: an identical retry taking the original as its parent (`k = n`, empty delta). The retry carries the
original's path, so a rebuild reading "any row with that path" could land on the retry and loop on itself.

### D7. Default `whole`, opt-in `delta`

`delta` changes what `request_body` holds for chat rows, which breaks readers of the message history. An
upgrade must not do that silently, so operators opt in, as with `drop` for inline files. Switching back
needs no migration.

Under `whole` the encoder still parses each body and stamps a chat request's row with its path and depth,
while keeping the body. Without that, rolling back would break chains. A delta row's parent that is
re-ingested under `whole` (a backfill after the rollback, or a rolling deploy with one replica on each mode)
would replace the only row carrying the parent's path with a row carrying none, and the child could no
longer be rebuilt. With it, the whole row carries the path and supplies its history from the body. The same
stamp lets a switch to `delta` link new requests onto rows stored whole before it. The cost is one parse per
body under the default mode, which is linear in body size: the encoder test measures tens of MB per second on
one core. Rejected: parsing only under `delta`, which would leave the documented rollback unsafe.

What this does not cover is a re-ingest whose history comes out different: after a change of
`sync.inline-files-drop-truncated` or `sync.inline-files-max-size`, or a release that changes the canonical form. The new copy then carries a different
path, and a child that was not re-ingested with it is orphaned. Backfill and reconciliation re-read whole days,
and parent and child share a day, so this needs a partial-day re-ingest after such a change. The spec states it
as the operator's to avoid.

### D8. Schema changes

- **ClickHouse (next free version):**
  - `ALTER TABLE dial_usage_log_payload ADD COLUMN` for:
    - `project_id` and `deployment` `LowCardinality(String)`, `user_hash String`, `session_id Nullable(String)`;
    - `request_path` and `request_parent`, both `UUID`, so they travel as plain strings in `JSONEachRow`;
    - `request_depth UInt32`;
    - `request_ancestors Array(UUID)`;
    - `request_inherited Array(LowCardinality(String))` (D9);
    - `request_cache_markers Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV)`.
  - `ADD INDEX idx_request_path request_path TYPE bloom_filter(0.001) GRANULARITY 1`. An `IN` set keeps a
    granule when any of its values may match, so pruning weakens with the number of candidates: hence the low
    false-positive rate and only 8 candidates per request. When it does not prune, the lookup reads
    `request_path` and `request_time` of the day, two small columns.

  All of these are metadata-only on existing parts. The index is not materialized for old parts, which
  hold no paths.
- **Postgres catalog (next free version):** one `column_mapping` row on `dial_usage_log_payload`,
  `request_cache_markers` (`string`, nullable, sensitive, heavy, with a description and display name), and the
  seeded `request_body` description extended while it is still the seeded text.
- **Mapping:** `dial-usage-log-payload.yaml` gains the four identity columns. The startup schema validation then
  covers it.

### D9. One body column, and tools inherited from the parent

A delta row keeps everything in `request_body`: the request with its message array cut to the elements its
parent does not have. A first turn is therefore stored complete, and a non-zero `request_parent` is what says a
body is partial. Rejected: an envelope column and a delta column beside a `NULL` `request_body`. Under `delta` no
column holds a later turn's whole history either way, so a `NULL` body protects no search, while the single column
keeps top-level fields where readers already look and lets a text search find each message usually once.

- The system keys lead the history, so a parent always covers them (`k` at least their count) and a child's body
  leaves them out; `request_inherited` names them, so the rebuild puts each value back under the child's own key.
- `tools` is part of the chain seed `p0`, so every row carrying a path has the same tools, and a child always leaves
  them out. The rebuild takes them from the chain's root, which it reads anyway. A request whose tools changed finds
  no parent and is a root, re-storing its history once. Rejected: comparing a separate tools hash with the rows
  carrying the parent's path. It keeps a chain across a tools change, but rows sharing a path can then differ in
  their tools, so a rebuild must look them up by hash across the day's partition, and the lookup, the chain nodes
  and the table each carry tools hashes. Tools rarely change within a conversation, so re-storing a history at
  each change costs little.
- The left-out keys are listed rather than written as `null`: a client can send `"tools": null`, absent `tools`, or
  `"tools": []`, and each must rebuild as sent.

## Risks / Trade-offs

- **[Unstable `tools` re-root every turn]** → `tools` is in `p0` as canonical JSON, with object keys sorted but
  array order and any `cache_control` member inside it kept, so a client that reorders its tool list or moves a
  tool's marker on every turn makes every turn a root, and stores whole histories as `whole` would. Accepted:
  clients send the same tools along a conversation, and the cost is space only. Should one not, moving tool-level
  markers into `request_cache_markers` is the fix.
- **[Readers of `request_body` break under `delta`]** → Opt-in mode (D7). The breaking note goes into the
  specs and the proposal; top-level fields stay readable from `request_body`, and a text search finds each
  message once, in the turn that first sent it.
- **[Ingest CPU: every request body is parsed fully, and canonicalized, under either mode]** → Bodies are
  already in memory, and the work is linear in body size. A test measures encoder throughput on a large
  synthetic agent session. Under `whole` nothing is linked or looked up; see D7 for why it still parses.
- **[Flush memory under `delta`]** → Parents are resolved across the whole batch, so a naive encoder would hold
  every request's history strings and stored body until the flush ends: about two to three times the batch, beyond
  `SYNC_MAX_BATCH_BYTES`, which operators budget per ingest path. Instead the first pass keeps only the prefix hashes
  (16 bytes per history element, packed), and writing a row parses its body again for the stored body and markers. A chat body is parsed twice under
  `delta`, once under `whole`. The peak is one batch, plus the hashes, plus the table lookup's answers (at most a
  16,384-path lookup, each answer with a hint of up to 2 KiB), plus one body's parse. Should a second parse ever
  disagree with the first, the row is stored whole with its path rather than failing the flush.
- **[Lookup query cost on a table not sorted by path]** → Partition pruning to the flush's days plus a
  bloom-filter skip index. The lookup runs once per flush and only for rows the flush and the cache cannot link,
  bounded per row and per flush, and chunked.
- **[Parent lost to concurrency or ordering → extra roots]** → Accepted: it costs space, never
  correctness. Sorting within a flush and the cache cover the common case.
- **[Compression gain is limited by `ORDER BY event_id`]** → Known. The session-ordered re-key is a
  separate later change.
- **[Rollback]** → Switching the mode to `whole` stops new delta rows. Existing delta rows keep their cut
  bodies, and the later rebuild can reassemble them.

## Migration Plan

1. Deploy with both migrations. The default `whole` changes nothing observable except one new empty
   column. Chat rows also start carrying their hidden path.
2. Set `SYNC_REQUEST_BODY_MODE=delta` once no reader needs a later turn's whole history or tools from
   `request_body`.
3. Roll back by setting `whole`. The migrations stay: the columns are additive and empty on old rows.

## Open Questions

- The exact values of `PATH_CACHE_ENTRIES`, `TABLE_CANDIDATES` and `MAX_TABLE_CANDIDATES` can be tuned after observing cache hit
  and root rates. Making either a configuration property later would not change the specs.
