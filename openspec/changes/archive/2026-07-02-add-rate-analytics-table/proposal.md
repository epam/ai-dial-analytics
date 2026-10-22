## Why

DIAL emits a feedback/rating event every time a user rates a response (`POST /v1/{deployment}/rate`).
Today these events are only retrievable as raw `/rate` rows inside `dial_usage_log`, where the rating
(`rate`, `comment`) is buried in the encrypted `request_body` blob and the `response_id` column is
empty (the deriver reads the completion `id` from the response, which a rate call has none of). There
is no first-class, queryable surface for "how are responses being rated" analytics. This change gives
ratings their own narrow, queryable table keyed on the rated `response_id`.

## What Changes

- Add a new ClickHouse **SOURCE** table `rate_analytics`, populated by the existing blob-ingest
  (data-sync) pipeline — the same path that fills `dial_usage_log` — but only for records matching
  the rate-request condition (`request.uri` ends with `/rate` **and** `request.method == POST`).
  A rate record continues to produce its `dial_usage_log` row as well; `dial_usage_log` behavior is
  unchanged.
- Populate `rate_analytics.response_id` from the client-supplied `responseId` in `request_body`
  (not from the response), so ratings match the rated completion in `dial_usage_log`.
- **Keep-all-ratings** semantics: one row per rate event; repeated ratings of the same
  `response_id` are preserved as distinct rows. Re-ingest stays idempotent via `ReplacingMergeTree`
  on the sort key.
- Extend the data-sync ingest pipeline from single-mapping/single-table to **multi-target with a
  per-mapping match predicate** — a genuinely new conditional-emit capability (no predicate exists
  today).
- Add a `json-extract-rate` deriver that normalizes the polymorphic `rate` value
  (number passes through; `true`→1, `false`→0; absent→null).
- Register `rate_analytics` in the query catalog so it is discoverable and queryable via
  `/v1/queries`, exposing only its 8 analytics columns (structural columns stay physical-but-hidden).

## Capabilities

### New Capabilities
- `rate-analytics`: the `rate_analytics` table — its schema, the rate-event capture semantics
  (trigger condition, `response_id` sourced from the request body, keep-all-ratings, exposed vs.
  hidden columns), and its registration as a discoverable/queryable SOURCE entity.

### Modified Capabilities
- `data-sync`: the ingest pipeline gains **multiple ingest targets** and **conditional per-target
  row emission** via a mapping-level match predicate. A single parsed record MAY now produce rows in
  more than one target table, and a target MAY declare a predicate that limits which records it
  emits. The existing single default target (`dial_usage_log`, no predicate) is preserved.

## Impact

- **New migrations**: `db/migration/clickhouse/V2__create_rate_analytics.sql` (creates the table);
  `db/migration/postgres/V2__seed_rate_analytics_catalog.sql` (seeds `table_definition`,
  `table_ordering_key`, and the 8 exposed `column_mapping` rows).
- **New config resource**: `src/main/resources/mapping/rate-analytics.yaml`.
- **Ingest pipeline code** (all genuinely-new ADAS code): `MappingConfig`/`MappingFileLoader`
  (parse a `match:` block), a new `RecordMatcher`, a `SyncTarget` abstraction,
  `MappingConfiguration` (produce a list of targets), `SyncProperties` (targets list, env-driven,
  backward-compatible primary), `UsageLogWriter` (parametrize table name),
  `RowBatchSink` (bind to a table), `BlobIngestor` (iterate targets), `SchemaValidator` (per target),
  `DeriverRegistry` (`json-extract-rate`).
- **Query path**: no requirement change — `query-schema-discovery` and `structured-query` already
  expose any ACTIVE SOURCE table from the catalog; `rate_analytics` is exercised, not modified.
- **Docs**: `docs/configuration.md` gains the `sync.targets` config group (authoritative
  descriptions live only here).
- **Rollout**: `rate_analytics` receives only new ingests after deploy; history is populated by
  re-running the existing data-sync backfill over the date range (idempotent via `ReplacingMergeTree`).
- **Operational prerequisite**: the AES-encrypted `comment` column requires the server-side
  encryption key already configured for this deployment (same requirement as `dial_usage_log` bodies).
