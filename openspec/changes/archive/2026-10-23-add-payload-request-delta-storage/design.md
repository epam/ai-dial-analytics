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
- The client session id is not known at ingest. A later pipeline derives it, but the record does carry
  `project.id`, `deployment` and `user.id`.
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
- Cross-conversation sharing of system prompts or `tools` (a shared-parts table), and storing `tools` only
  when they change from the parent. Under the current sort key the latter would save a modest share of the
  envelope, but once a session-ordered sort key lands, compression removes the repetition itself, so it
  would add columns and rules for little lasting gain. Either can be added later as new columns without
  changing chains.

## Decisions

### D1. One row per event, envelope + delta + parent (not one row per message)

A per-message node table shares more in raw bytes. Measured after compression, though, it was no smaller
than per-event, and it writes more rows and changes the row model. Per-event keeps exactly one payload row
per `dial_usage_log` row, so the grain join, the flush order and the drop rule are untouched.

### D2. Chain identity is a chained hash with the scope and the day in the seed

`p0 = H(request_scope, day)`, `pk = H(p(k-1), canonical(hk))`. Putting the scope and the UTC day into `p0`
makes the "never cross a user or a day" rule structural: there is nothing to filter, because a hash from
another scope or day cannot match.
- `request_scope` is a new mapped column, written by the existing `hash-uuid` derive over
  `(project.id, deployment, user.id)`. That keeps the mapping stateless; the lookup itself needs no scope filter, since the scope is
  in every hash.
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
JSON Pointer inside the element) and value. The envelope is serialized the same way, with a small
`__adas_history` record of which keys were present: the message array (`messages`, `input` or `contents`)
and the top-level system keys (`system`, `instructions`, `system_instruction`, `systemInstruction`). Only history
elements lose markers; the envelope keeps its own markers (for example on `tools`) as they are.

The canonical form is stable only within a release. If a later release changes it, the first request of
each conversation after the deploy becomes a root, and nothing breaks.

### D4. Parent resolution runs in the payload sink at flush, after mapping

The mapping stays per-record and stateless. It still emits `request_body` after the inline-file rewrite,
plus `request_scope`. A new request-body encoder runs when the payload sink flushes. Under `whole` it only stamps
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
3. Rewrites each row's request columns (the parent is the deepest match with `k < n`, see D6), sets `request_body` to `NULL`, and hands the batch to the existing
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
    - `request_scope UUID`;
    - `request_path` and `request_parent`, both `UUID`, so they travel as plain strings in `JSONEachRow`;
    - `request_depth UInt32`;
    - `request_ancestors Array(UUID)`;
    - the three `Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV)` columns.
  - `ADD INDEX idx_request_path request_path TYPE bloom_filter(0.001) GRANULARITY 1`. An `IN` set keeps a
    granule when any of its values may match, so pruning weakens with the number of candidates: hence the low
    false-positive rate and only 8 candidates per request. When it does not prune, the lookup reads
    `request_path` and `request_time` of the day, two small columns.

  All of these are metadata-only on existing parts. The index is not materialized for old parts, which
  hold no paths.
- **Postgres catalog (next free version):** three `column_mapping` rows on `dial_usage_log_payload`
  (`string`, nullable, sensitive, heavy, with descriptions and display names).
- **Mapping:** `dial-usage-log-payload.yaml` gains `request_scope`. The startup schema validation then
  covers it.

## Risks / Trade-offs

- **[Readers of `request_body` break under `delta`]** → Opt-in mode (D7). The breaking note goes into the
  specs and the proposal, and envelope fields stay readable from `request_envelope`.
- **[Ingest CPU: every request body is parsed fully, and canonicalized, under either mode]** → Bodies are
  already in memory, and the work is linear in body size. A test measures encoder throughput on a large
  synthetic agent session. Under `whole` nothing is linked or looked up; see D7 for why it still parses.
- **[Flush memory under `delta`]** → Parents are resolved across the whole batch, so a naive encoder would hold
  every request's history strings and envelope until the flush ends: about two to three times the batch, beyond
  `SYNC_MAX_BATCH_BYTES`, which operators budget per ingest path. Instead the first pass keeps only the prefix hashes
  (16 bytes per history element, packed), and writing a row parses its body again for the delta, envelope and markers.
  The envelope is rendered to a string during that parse and never kept as a tree. A chat body is parsed twice under
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
- **[Rollback]** → Switching the mode to `whole` stops new delta rows. Existing delta rows stay readable
  through the new columns, and the later rebuild can reassemble them.

## Migration Plan

1. Deploy with both migrations. The default `whole` changes nothing observable except three new empty
   columns. Chat rows also start carrying their hidden path.
2. Set `SYNC_REQUEST_BODY_MODE=delta`, and move readers that need the message history to
   `request_delta`/`request_envelope`.
3. Roll back by setting `whole`. The migrations stay: the columns are additive and empty on old rows.

## Open Questions

- The exact values of `PATH_CACHE_ENTRIES`, `TABLE_CANDIDATES` and `MAX_TABLE_CANDIDATES` can be tuned after observing cache hit
  and root rates. Making either a configuration property later would not change the specs.
