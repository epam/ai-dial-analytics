## Why

Rendering a conversation's message history on the UI needs one row: the newest chain-entry hop's
`request_body.messages` (the client-accumulated transcript) merged with the producer's
`assembled_response` (the final assistant answer). ADAS reads `assembled_response` at ingest today —
it is where `response_id` comes from — but the mapping deliberately drops it, so the last answer is
recoverable only by reassembling an SSE transcript in the browser. The large majority of chat
entry hops carry an `assembled_response` that parses.

Persisting it adds a **third** heavy, unbounded-size column to `dial_usage_log`, which makes an
existing gap materially worse: ADAS applies no ClickHouse execution settings on the query path, so a
query projecting a body column with no `request_time` bound sweeps every daily partition (GBs of
bodies against a server with a few GiB of memory). The caller's HTTP client gives up first while ClickHouse keeps
executing, and the failure reaches the caller as an empty-message transport error rather than as a
statement about the query. The two ship together because the first raises the odds of the second.

## What Changes

**Persist the assembled response**

- `dial_usage_log` gains an `assembled_response` column (nullable string), mapped straight from the
  record's `assembled_response` node. No new deriver: `DeriverRegistry` and `JsonPaths` are untouched.
- The column is seeded `sensitive: true` and `heavy: true`, the same class as `request_body` /
  `response_body`, so it stays `FULL_ADMIN`-only and out of wildcard projections.
- **BREAKING (spec-level)**: `data-sync` currently *requires* that `assembled_response` is never
  persisted. That requirement and its scenario are reversed, and the allowlist requirement's PII note
  is rewritten to say why the privacy posture moved.
- History is populated by an operator-run `data-sync-backfill` pass, not by this change.
- Documented and not fixed here: on an Anthropic-native stream `assembled_response` is itself an SSE
  transcript (~4.8% of entry hops), so the column stores an unparseable payload — the same producer
  blind spot that already leaves `response_id` empty on those rows.

**Bound the cost of a query**

- The structured-query execution path runs its statement under configurable ClickHouse
  `max_execution_time` and `max_memory_usage` server settings, mirroring the shipped aggregate-preview
  budget. The bound applies to the query surface only; the enrichment scan and aggregate refresh paths
  share the executor but carry their own limits and are untouched.
- A ClickHouse refusal under those ceilings becomes a stable `ErrorView` naming the ceiling that was
  hit and what to narrow, instead of a client-side timeout with no server response.
- Explicitly **not** proposed: a static "a heavy projection must carry a partition predicate" rule.
  Capping the resource that actually runs out is the precedent the preview budget set; a scan-shape
  rule would refuse legitimate bounded-but-wide queries and still admit cheap unbounded ones.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `data-sync`: reverses "assembled_response is not persisted" — the field becomes a mappable,
  persistable column, and the allowlist requirement's heavy-payload note names three columns.
- `table-management`: adds `dial_usage_log.assembled_response` to the seeded `sensitive` set and to
  the seeded `heavy` set.
- `structured-query`: adds a query execution budget — time and memory ceilings on the executed
  statement, and a stable error for a refusal.
- `rest-sql-endpoint`: the same budget governs `POST /v1/queries/execute-sql`, which shares the
  pipeline; adds the scenario that pins it.

## Impact

- **Schema**: new ClickHouse migration adding `assembled_response` to `dial_usage_log` (a new
  migration file — applied checksums are fixed); catalog migration seeding the column's
  `sensitive`/`heavy` flags and description.
- **Ingest**: `src/main/resources/mapping/dial-usage-log.yaml` gains one mapped column. Rows ingested
  before the backfill carry `NULL`.
- **Query surfaces**: `POST /v1/queries/execute`, `POST /v1/queries/execute-sql`, and the Flight SQL
  endpoint all run through `StructuredQueryService` and inherit the budget. Schema discovery reports
  the new column to `FULL_ADMIN` callers only.
- **Code**: `StructuredQueryExecutor` / `StructuredQueryService`, a new query-budget properties record,
  a new exception plus its `@ExceptionHandler` on `DefaultExceptionHandler`.
- **Config**: new env-driven query-budget properties, described only in `docs/configuration.md`.
- **Operations**: a `data-sync-backfill` pass is required to populate history. Re-ingest advances
  `_ingested_at`, which re-dirties `conversation_insights` groups and triggers a re-labelling pass.
- **Storage**: `assembled_response` duplicates content already held in `response_body`; across entry
  hops that is on the order of GBs raw before compression.
