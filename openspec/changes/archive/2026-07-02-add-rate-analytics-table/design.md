## Context

DIAL emits a rating event on `POST /v1/{deployment}/rate`. These already flow into `dial_usage_log`
as ordinary `/rate` rows, but the rating (`rate`, `comment`) is buried in the encrypted
`request_body` and the `response_id` column is empty (its deriver reads the completion `id` from the
response, which a rate call lacks). We want a first-class, queryable `rate_analytics` table.

Current pipeline state (all genuinely-new ADAS code, so in-bounds to refactor):

- `BlobIngestor` reads a blob, parses NDJSON via `LogParser`, maps each record with a **single**
  `MappingEngine`, and appends to a **single** `RowBatchSink`.
- `MappingConfiguration` loads **one** mapping YAML (`sync.mapping-file`) into one `MappingConfig`
  bean; `MappingEngine`, `SchemaValidator`, and `UsageLogWriter` all bind to that one mapping /
  `sync.table`.
- `UsageLogWriter.insert` / `describeTable` target `sync.table()` and use a dedicated writer
  credential; `RowBatchSink` batches into that one writer.
- There is **no** conditional-emit / predicate concept anywhere. The only "skip" is
  `BlobIngestor.missingDedupKey`.
- The query API is catalog-driven: `CatalogQueryService.buildModel` builds field `bindings` strictly
  from Postgres `column_mapping` rows; row mode never emits `SELECT *` and any un-cataloged field is
  rejected with `unknown field`. `dial_usage_log` is seeded in the catalog (`postgres/V1`) because it
  pre-exists in ClickHouse and ADAS must not re-create it.

Constraints: config descriptions live only in `docs/configuration.md`; the full `./gradlew clean
build` requires Docker (Testcontainers ClickHouse); a change is done only when that build passes.

## Goals / Non-Goals

**Goals:**

- A `rate_analytics` SOURCE table populated by the existing blob-ingest pipeline, only for rate
  records, with `response_id` sourced from `request_body.responseId`.
- Keep every distinct rate event; idempotent re-ingest.
- A minimal, generic **multi-target + match-predicate** extension of the ingest pipeline (reusable,
  not a one-off rate hack).
- Expose only the 8 analytics columns via `/v1/queries`; keep structural columns physical-but-hidden.

**Non-Goals:**

- No enrichment/auto-join modeling (decided: standalone SOURCE). No query-time join is added.
- No change to `dial_usage_log`'s schema or behavior; `/rate` rows still land there too.
- No new HTTP endpoint. No masking/redaction beyond the AES codec on `comment`.
- No automatic historical backfill — history is recovered by re-running the existing data-sync
  backfill.

## Decisions

### D1 — Standalone SOURCE table, not an enrichment

Model `rate_analytics` as an independent SOURCE (MergeTree family) rather than an ENRICHMENT of
`dial_usage_log`. **Why:** the `/rate` log record is self-contained (it already carries `deployment`,
`project_id`, `request_time`, `user_hash`, `chat_id`), so a SOURCE needs no join for its own
dimensions; and "populated the same way as `dial_usage_log`" (blob ingest) fits a SOURCE, whereas
enrichments are materialized/populated via the table-management API. **Alternative rejected:**
enrichment on `response_id` — forces ZERO_OR_ONE cardinality (`ReplacingMergeTree` collapses to one
row per key), which conflicts with keep-all-ratings, and diverges from the ingest-population model.

### D2 — Generic multi-target ingest instead of a rate-specific branch

Introduce a `SyncTarget = (tableName, MappingEngine, RecordMatcher)` and drive `BlobIngestor` over a
`List<SyncTarget>`. `MappingConfiguration` produces the list; `SyncProperties` grows a `targets` list
(env-driven, backward-compatible: a single default target = today's `dial_usage_log`). `UsageLogWriter`
is parametrized by table name (`insert(table, rows)`, `describeTable(table)`); `RowBatchSink` binds to
`(writer, tableName)`; `DataSyncService` / `BlobPollingService` hold one sink per target and flush all
at end of run; `SchemaValidator` validates each target. **Why:** the predicate/second-table need is
inherently general; a config-driven target list matches the existing config-driven mapping philosophy
and keeps `dial_usage_log` untouched. **Alternative rejected:** hardcode a second rate writer inside
`BlobIngestor` — less testable, not reusable, bakes rate semantics into the pipeline.

### D3 — Conditional emit via a mapping-level `match` block

Add an optional top-level `match:` to the mapping YAML: an ordered list of `{path, op, value}`
(ops `ends-with`, `equals`, `contains`), ANDed together. `MappingFileLoader` parses and validates it
into a compiled `RecordMatcher`; a mapping without `match` matches all records (so `dial-usage-log.yaml`
is unchanged). `BlobIngestor` emits a target's row only when its matcher passes. **Why:** mirrors the
closed transform/deriver-registry, fail-fast philosophy; keeps emission rules declarative and in the
same file as the columns. **Alternative rejected:** a dedicated Java predicate per target — less
declarative, needs code for each new table.

### D4 — `response_id` and `rate` derivers

`response_id` reuses the existing `json-extract-string` deriver on `request.body` with `path:
responseId`. `rate` needs a **new** `json-extract-rate` deriver (signed int; `true`→1, `false`→0,
number→int, absent→null) — existing `json-extract-uint` is unsigned and can't hold `-1`, and neither
handles the boolean form seen in real data. It is registered in `DeriverRegistry` and added to
`MappingFileLoader`'s known-deriver set. **Why:** the smallest closed-registry addition that covers the
polymorphic `rate` field.

### D5 — Hidden physical columns for correct storage

`trace_id`, `core_span_id`, `_ingested_at` are physical in the ClickHouse DDL (sort key + RMT version)
but are **not** seeded into Postgres `column_mapping`. Verified in code: `bindings` come solely from
`column_mapping`; row mode enumerates bindings (never `SELECT *`); un-cataloged references are
rejected. So they are invisible to `/v1/queries` yet still make dedup/keep-all correct. `event_id` and
`_source_file` are dropped as unnecessary for this table. **Why:** clean analytics schema without
sacrificing storage correctness — the same "hidden physical column" idea the catalog already uses for
enrichment grain keys.

### D6 — Table + catalog created by migrations, seeded ACTIVE

`clickhouse/V2__create_rate_analytics.sql` creates the table (`ReplacingMergeTree(_ingested_at)`,
`PARTITION BY toYYYYMMDD(request_time)`, `ORDER BY (project_id, deployment, request_time, trace_id,
core_span_id)`, `TTL … + INTERVAL 1 YEAR`, bloom index on `response_id`). `postgres/V2` seeds
`table_definition` (SOURCE, ACTIVE, partition `request_time`/DAY), `table_ordering_key` (full physical
sort key), and `column_mapping` for the 8 exposed columns only. Seeded ACTIVE — matching how
`dial_usage_log` is treated — so ADAS never re-CREATEs it. **Why:** consistency with `dial_usage_log`;
migrations are the established provisioning path. **Alternative rejected:** create via the
table-management API — heavier, and its validator rejects the reserved/hidden columns this table needs.

## Risks / Trade-offs

- **Join-key match rate is partial** (sample: 2 of 5 rate `responseId`s matched a stored
  `response_id`). → Accepted and expected: unmatched ones are completions outside retention;
  `response_id` is stored best-effort and matching is a query-time concern. Documented, not blocking.
- **Refactor touches tested, load-bearing ingest code** (`BlobIngestor`, `UsageLogWriter`,
  `RowBatchSink`, `SchemaValidator`, sink owners). → Keep the single-target default path behaviorally
  identical; cover with `BlobIngestorTest` asserting `dial_usage_log` output is unchanged and rate
  rows appear only for matched records.
- **`comment` is PII.** → Store with the AES codec like `dial_usage_log` bodies; requires the
  server-side encryption key already configured for this deployment (or `CREATE TABLE` fails — same
  constraint as V1).
- **`rate` polymorphism / `false` semantics.** → Normalizer fixes `false`→0 (confirmed with the
  maintainers); numbers pass through; documented in the deriver spec.
- **Config surface change (`sync.targets`).** → Backward-compatible: absent config keeps the single
  default target; descriptions added only to `docs/configuration.md`.
- **`response_id` non-nullable empty-string fallback** means unmatched/absent `responseId` rows still
  persist with `response_id = ""`. → Intended (best-effort capture); consumers filter on non-empty.

## Migration Plan

1. Deploy the two Flyway migrations (ClickHouse V2 creates the table; Postgres V2 seeds the catalog).
   Both are additive; `dial_usage_log` and existing catalog rows are untouched.
2. Ship the pipeline changes with `sync.targets` defaulting to both `dial_usage_log` (no match) and
   `rate_analytics` (rate match). New ingests populate `rate_analytics` immediately.
3. Backfill history by re-running the existing data-sync backfill (`DataSyncBackfillController`) over
   the desired date range — it re-parses the same blobs and now also emits `rate_analytics` rows;
   idempotent via `ReplacingMergeTree`.
4. **Rollback:** remove the `rate_analytics` target from config (pipeline reverts to single-target,
   `dial_usage_log` unaffected). The table and catalog rows can be dropped independently; nothing else
   references them.

## Open Questions

None outstanding. Prior open items resolved with the maintainers: table model = SOURCE; multiplicity =
keep-all; exposed columns = analytics set with structural columns hidden; `rate false → 0`;
`_source_file` dropped; join key verified against the live instance.
