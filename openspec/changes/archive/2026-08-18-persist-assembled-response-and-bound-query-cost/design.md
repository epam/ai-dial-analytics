## Context

`dial_usage_log` stores two verbatim payload columns, `request_body` and `response_body`, both
`Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV)`, both seeded `sensitive` and `heavy`. The producer
also emits `assembled_response` — the merged streaming response — which ingest resolves as a deriver
source (`response_id`) but the mapping deliberately drops, and which `data-sync` today *requires* not
to be persisted.

That drop is what blocks a conversation-history UI. On a streamed call `response_body` holds the raw
SSE chunk transcript, so the assistant's answer is not readable from it without reassembly; in practice
chain-entry chat hops are almost always streamed. `assembled_response` is the merged form, and it
parses for the large majority of chat entry hops — the remainder are upstream-native streams where
the producer itself emits a transcript.

The second half of the change addresses what persisting a third payload column makes worse. ADAS sets
no ClickHouse execution settings on the query path — `max_execution_time` / `max_memory_usage` appear
only in `service/pipeline/PreviewBudget.java`, which bounds the aggregate dry-run preview. A query
projecting a body column with no `request_time` predicate prunes no partitions and reads every body in
the table (GBs of bodies, against a server with a memory ceiling of a few GiB). The caller's HTTP client times out first; the
statement keeps running; the caller sees a transport error with no message. Reproduced against a running deployment: the
identical query succeeds when a `request_time` window is added and fails without one.

## Goals / Non-Goals

**Goals:**

- Make the producer's merged response readable as an ordinary (heavy, sensitive) column, so a consumer
  can render an assistant answer without reassembling a stream.
- Keep the capture decision where every other column's lives: the mapping.
- Give an over-expensive query a deterministic, self-describing refusal instead of a silent
  server-side burn and a client-side timeout.
- Leave the enrichment scan, group-fetch, materialize, and aggregate-refresh paths exactly as they are.

**Non-Goals:**

- No SSE assembly anywhere in ADAS. The ~4.8% of records whose `assembled_response` is itself a
  transcript stay as the producer sent them.
- No backfill of historical rows as part of this change — that is `data-sync-backfill`'s job on the
  operator's schedule.
- No new derived text columns (`user_message` / `assistant_message`). See Decision 3.
- No change to what a query *returns*: the budget bounds cost, never result shape.
- No static query-shape rule (e.g. "a heavy projection must carry a partition predicate"). See
  Decision 7.

## Decisions

### 1. Persist the producer's value verbatim, whatever its shape

The column stores exactly what the producer emitted. For a non-streamed call and a DIAL-normalized
stream that is a merged JSON message; for an upstream-native stream it is an SSE transcript.

*Alternative rejected — normalize at ingest (assemble the transcript into a message).* Ingest has
consistently refused to own payload shape: `cache_creation_tokens` was moved *off* body derivation for
exactly this reason, and `reasoning_tokens` still carries the same blind spot. An assembler would have
to track upstream dialects and stream framings per producer version, and its failures would be silent
— a column that looks like an answer but is a partial one is worse than a transcript that visibly is
not JSON. The spec therefore pins "stored as reported" and tells consumers to parse defensively.

### 2. Capture it alongside `response_body`, not instead of it

The two overlap for streamed calls. Keeping both costs one more unbounded-size column per row.

*Alternative rejected — drop `response_body` from the mapping and keep only the merged form.* That is
a live option for an installation that wants the storage back, but it is an operator's call, not this
change's: the raw body carries transport-level detail (chunk framing, error chunks, truncation) that
diagnosing a malformed stream needs, and removing it would silently change what existing consumers
read. Recorded in the spec as the removal path, not taken here.

### 3. A column, not a derived `user_message` / `assistant_message` pair

An earlier draft proposed extracting the turn's user text and the answer into two flat text columns.

*Rejected as the primary design.* Flattening throws away roles, ordering, tool calls, and attachments
— the structure the UI actually renders — and the answer half would still need SSE assembly, which
Decision 1 refuses. The derived pair remains worth adding later for a *different* reason: it would make
message text queryable (filter, group, feed a Class-1 enrichment) rather than only renderable.
`JsonPaths.walk` already resolves numeric array indices, so `choices.0.message.content` needs no code;
only a negative index (`messages.-1.content`) would.

### 4. DDL must mirror the sibling body columns exactly

`assembled_response Nullable(String) CODEC(ZSTD(3), AES_256_GCM_SIV)`, added by a new ClickHouse
migration (`V7__…`) rather than by editing `V1` — an applied migration's checksum is fixed, and the
same file is the canonical operator DDL when `CLICKHOUSE_MIGRATIONS_ENABLED=false`.

**Operational invariant:** the encryption codec requires a server-side key (`encryption_codecs` in
`config.d`). Without it the `ALTER` fails, exactly as `CREATE TABLE` in `V1` does. `ADD COLUMN` is
metadata-only — no mutation, no part rewrite — so the cost is a schema bump, unlike `V6`'s
`MODIFY COLUMN`. The sort key is untouched, so dedup behavior is unchanged.

**Ordering invariant:** `SchemaValidator` refuses at startup a mapping that declares a column the
physical table lacks. The migration and the mapping entry therefore ship together, and an operator
running with migrations disabled applies the DDL before the new build.

### 5. Seed `sensitive: true` and `heavy: true`

Same class as the two existing bodies: verbatim user-visible content, unbounded per-row size. Seeding
happens through a `column_mapping` catalog migration (`V29__…` on Postgres), which issues no
ClickHouse `ALTER`.

### 6. Attach the budget at the query-service boundary, not inside the executor

`StructuredQueryExecutor` is shared by `StructuredQueryService` (the query surface, including SQL and
Flight SQL) *and* by `ScanService`, `GroupFetchService`, `MaterializeService`, and
`AggregateRefreshService`. The settings are therefore attached where a caller-supplied query is
executed, using the executor's existing per-call `QuerySettings` seam (the one `final = 1` already
uses), so the enrichment and refresh callers are untouched.

*Alternative rejected — set the ceilings globally on the ClickHouse client.* It would bind every path,
including a refresh whose legitimate large window would then fail as though it were malformed. This is
the same boundary the preview budget drew, for the same reason.

### 7. Cap the resource that runs out, not the shape of the query

*Alternative rejected — reject a heavy-column projection that carries no partition-pruning predicate.*
It is tempting because it names the exact failure observed, but it is both too strict and too loose: a
bounded-but-wide query over a small partition set is refused while a cheap unbounded one over a narrow
table is admitted, and it cannot see the aggregation-state blowups that a scan-shape rule never
models. `PreviewBudget`'s javadoc argues the same case for `max_rows_to_read`. Capping time and memory
prices the thing that actually exhausts the server.

Both ceilings are set, plus a spill threshold strictly below the memory ceiling, so an
aggregate-mode query whose state outgrows memory goes to disk and finishes slowly rather than being
refused — the preview's "spill before refusing" rule.

### 8. Match numeric ClickHouse error codes; answer 422, not 503

Translate `ServerException` codes 159 `TIMEOUT_EXCEEDED` and 241 `MEMORY_LIMIT_EXCEEDED` into a new
`QueryBudgetExceededException`, handled on `DefaultExceptionHandler` as `422` with `error`
`query_budget_exceeded`. Matching on the numeric code rather than message text is the less brittle
half; anything unrecognised falls through to the existing `ClickHouseQueryException` → 400 path, so a
driver change costs the specific error and nothing more.

422 rather than 503 because the refusal is deterministic: re-running the identical query is refused
identically, and a client treating it as a transient failure would loop. This mirrors
`pipeline_preview_budget_exceeded` exactly.

### 9. Default the wall-clock ceiling below a typical client read timeout

New config block `query.budget.*` (`QUERY_BUDGET_TIMEOUT` 20s, `QUERY_BUDGET_MAX_MEMORY` 2GB,
`QUERY_BUDGET_SPILL_THRESHOLD` 1GB), described only in `docs/configuration.md`.

The 20s default is chosen against the client, not against ClickHouse: `adas-mcp` reads with a 30s
timeout and browsers/BI tools are usually in the same range. A ceiling above the client's own timeout
would leave the caller seeing the same empty transport error the change exists to remove, so the
server must give up first and say why.

### 10. Backfill is an operator step

Existing rows read `NULL` until re-ingested. `data-sync-backfill` re-reads the blobs and re-inserts on
the same `ReplacingMergeTree` key; the query path already reads with `final = 1`, so a backfilled row
is visible before merge collapses its predecessor.

## Risks / Trade-offs

- **A third verbatim-content column widens the PII surface** → It inherits the existing controls
  unchanged: `sensitive` (FULL_ADMIN-only on every query surface), `heavy` (never in a wildcard),
  at-rest encryption via the same codec, and the table's 1-year TTL. The allowlist stays the off
  switch, and the spec says so.
- **Storage grows by content already stored once** → on the order of GBs raw across entry hops before
  compression; ZSTD(3) on near-duplicate text compresses well. An installation that objects takes
  Decision 2's removal path.
- **Backfill re-dirties `conversation_insights`** → re-ingest advances `_ingested_at`, which the group
  rule reads as new activity and re-evaluates (its `idle: 30m` guard exists for precisely this). Cost
  is a re-labelling pass; schedule the backfill deliberately rather than alongside a drain.
- **The budget binds Flight SQL too, whose clients tolerate longer waits** → the ceilings are
  deployment-configurable, and a BI tool pulling a large result is the case most likely to need a
  higher one. See Open Questions.
- **A refusal can mask a genuine regression** → "this query got slower" and "this query is refused"
  become the same event. The message names the ceiling and its configured value, so the distinction is
  in the response rather than in a log dive.
- **A stored `assembled_response` that does not parse (~4.8%)** → specified as expected, not as an
  ingest failure; consumers parse defensively.

## Migration Plan

1. Ship the ClickHouse migration (`ADD COLUMN`, metadata-only) and the mapping entry together; verify
   `SchemaValidator` passes at startup.
2. Ship the catalog migration seeding `sensitive`/`heavy` and the column description.
3. Ship the query budget with defaults; confirm an unbounded body projection now returns
   `query_budget_exceeded` instead of hanging.
4. Operator runs `data-sync-backfill` over the window that needs history, coordinated with the
   `conversation_insights` rule's state.

**Rollback:** remove the `assembled_response` entry from the mapping (or point `SYNC_MAPPING_FILE` at
a mapping without it) — ingest reverts to today's behavior and the column stops being written; the
column itself can stay in place harmlessly. Raise or unset the budget properties to restore unbounded
execution. Neither rollback needs a migration reversal.

## Open Questions

- Should Flight SQL carry its own ceiling rather than sharing the query surface's? Deferred: one
  budget now, split only if a real BI workload proves it necessary.
- Should an `assembled_response_bytes` companion column be added, mirroring
  `request_body_bytes` / `response_body_bytes`, so a caller can size the payload before fetching a
  heavy column? Deferred — it is one mapping line plus a catalog entry and can be added without
  disturbing anything specified here.
- Is `response_body` still worth capturing once the merged form is stored? Not decided here; Decision 2
  keeps both and leaves the removal to an installation.
