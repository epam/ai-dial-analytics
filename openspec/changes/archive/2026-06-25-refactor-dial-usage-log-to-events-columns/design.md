## Context

`dial_usage_log` is today a config-driven 1:1 landing of the DIAL usage-log emission. Ingest flows
`mapping/dial-usage-log.yaml` → `MappingEngine` (dotted-path walk + `ColumnKind` coerce + closed
`TransformRegistry`) → `UsageLogWriter` (dynamic `JSONEachRow`, column list = row-map keys). A startup
`SchemaValidator` reconciles the mapping against the live table (`DESCRIBE TABLE`); the DDL in
`V1__create_dial_usage_log.sql` doubles as the canonical operator artifact (Flyway). The same table is
the query entity in `DialUsageLogSchema`.

The Analytics 2.0 `events` design (`blob-event-table-structure-mapping-v2.md`) defines an enriched
column set. Each target column is `direct` (1:1, some renamed), `calculated` (derived at ingest),
`blocked` (needs DIAL Core support, unpopulated for now), or `ignore` (dropped). The `direct` columns
fit the existing single-source mapping. The `calculated` columns do not: several read multiple source
fields, re-parse JSON that is embedded as a string, classify via regex, or compute byte lengths — so
the central design problem is *how to express calculated columns* without abandoning the config-driven,
no-arbitrary-code philosophy the `data-sync` spec mandates.

User decisions locked before design: keep the table name `dial_usage_log`; add the 3 blocked columns
now (unpopulated); clean-replace `V1` (no production data); keep the current physical layout and add
`event_id` as a column only.

## Goals / Non-Goals

**Goals:**
- `dial_usage_log` carries the `events` column set, populated where the source allows.
- Calculated columns are produced at ingest by a closed, named, config-referenced mechanism — symmetric with the existing transform registry.
- The startup schema validator, the query surface, the specs, and the docs stay consistent with the new schema.
- Physical dedup/partition/TTL behavior is unchanged.

**Non-Goals:**
- Populating the blocked columns (`operation_duration_ms`, `jwt_claims`, `request_tags`) — deferred until DIAL Core emits them.
- Changing the engine, sort key, partitioning, or TTL.
- Backfilling historical rows (clean replace; the table is recreated and re-ingested).
- Renaming the table to `events`, or adopting the events table's monthly partition / `event_id`-based sort key.

## Decisions

### D1 — Compute calculated columns at ingest in Java, not as ClickHouse MATERIALIZED columns
The mapping doc derives several columns from `assembled_response`, which is **dropped** from the table;
and from `request_body`/`response_body`, which are **encrypted** (`AES_256_GCM_SIV`) and nullable. A
ClickHouse `MATERIALIZED` expression cannot read a column that does not exist, and JSON-parsing
encrypted bodies on every merge is undesirable. At ingest, the raw record still has all fields as plain
JSON, so derivation there is both possible and cheap (once per row). Computing everything at ingest also
keeps a single, uniform mechanism rather than splitting logic between Java and DDL.
_Alternative considered:_ MATERIALIZED/DEFAULT columns for the few purely-from-persisted-column cases
(`success`, `*_bytes`) — rejected to avoid two mechanisms and to keep `success`/`bytes` consistent with
the body-derived fields.

### D2 — A closed "deriver" registry, referenced from YAML by `derive` + `sources` + `args`
Introduce `DeriverRegistry` (a `@Component`, mirroring `TransformRegistry`) holding a fixed, name-keyed
set of `Deriver` functions: `hash-uuid`, `classify-event-kind`, `json-extract-string`,
`json-extract-uint`, `json-array-length`, `byte-length`, `success-from-status`. A derived column entry
in the mapping declares `derive: <name>`, an ordered `sources: [<dotted path>, ...]`, an optional
`args: {...}`, and a `kind`. `MappingEngine` walks each source path to a node, calls the deriver, then
runs the existing `applyFallback(...)` (reusing kind-based zero/null/default handling). This keeps the
spec rule intact — **column names and source paths live in config; only reusable derivation logic lives
in code** — exactly as transforms already do.
_Alternatives considered:_ (a) one bespoke deriver per column with the source paths baked into code —
rejected because it hard-codes source paths, violating the spec; (b) a general expression/scripting
engine — rejected as over-powered and unsafe (the spec forbids arbitrary user expressions).

The JSON-extraction derivers must handle a source node that is **already an object** or a **JSON-encoded
string** (DIAL bodies appear as strings), trying each `sources` entry in order until one yields the
`args.path` value — this is how `response_id` and the body-derived token counts fall back across
`assembled_response` and `response_body`.

### D3 — Keep the physical layout; `event_id` is an added column, not a new dedup key
`event_id` = a deterministic UUIDv5 over `trace_id‖core_span_id`, so it is a 1:1 function of the existing
`(trace_id, core_span_id)` dedup key. Keeping `ReplacingMergeTree(_ingested_at)` with
`ORDER BY (project_id, deployment, request_time, trace_id, core_span_id)` and daily partitioning means
dedup semantics, query pruning, and TTL behavior are all unchanged — this stays a *column* refactor.
_Alternative considered:_ adopt the events table's monthly partition + `(…, event_kind, ts, event_id)`
sort key — rejected as out of scope and a behavioral change to a proven layout.

### D4 — Clean-replace `V1` rather than an additive `V2` migration
No production data exists to preserve, so the simplest correct path is to rewrite
`V1__create_dial_usage_log.sql` to the new schema and recreate the table fresh. Calculated columns are
populated on (re-)ingest by the daily reconciliation + poller; there is nothing to backfill.
_Alternative considered:_ an additive `V2` with `ALTER TABLE` (rename/drop/add) — unnecessary here, and
old rows could not have their body-derived columns backfilled anyway (parse inputs like
`assembled_response` are not retained).

### D5 — Blocked columns exist now, unmapped; enable the ClickHouse `JSON` type operationally
`operation_duration_ms UInt32`, `jwt_claims JSON`, `request_tags JSON` are created in the DDL but left
out of the mapping, so ClickHouse fills column defaults and `SchemaValidator` (which only checks *mapped*
columns) ignores them. This means no second migration when Core support lands. The `JSON` type on the
pinned `clickhouse/clickhouse-server:24.8` image needs `allow_experimental_json_type=1` (a profile
setting); it is enabled the same way the AES encryption key already is — a mounted config/users XML
(Testcontainers `withCopyFileToContainer` + `local_env` compose volume) — and documented for operators.

### D6 — Query surface exposes the new scalar dimensions; bodies / JSON / prices stay excluded
`DialUsageLogSchema.orderedColumns()` is rewritten to the new column set, preserving today's exclusion
philosophy (bodies, JSON enrichment, monetary columns, `parent_deployment`, `core_parent_span_id` remain
non-queryable). `QueryFieldType` already has `UUID`, `BOOLEAN`, and `DECIMAL`, so `event_id`→UUID,
`success`→BOOLEAN map cleanly. `SchemaValidator.classify(...)` gains `UUID` → `STRING` so `event_id`
(string kind) reconciles without a warning.

## Risks / Trade-offs

- **ClickHouse `JSON` type unsupported/awkward on 24.8** → enable `allow_experimental_json_type` via the existing config-mount mechanism; if it proves troublesome at build time, fall back to declaring `jwt_claims`/`request_tags` as `String` placeholders and switch to `JSON` in a later change when the image is bumped. Resolved at the integration-build checkpoint.
- **Editing `V1` changes its Flyway checksum** → valid only because all environments are recreated fresh; any pre-existing instance must be dropped and re-provisioned (documented in the migration plan).
- **`event_id` UUID inserted as a string via `JSONEachRow`** → ClickHouse parses a canonical UUID string into `UUID`; verified by the integration test that inserts and reads back.
- **Derivers parse bodies once per row at ingest** → acceptable: it is ingest-time only (not per query/merge), and bodies are already serialized for the insert.
- **`LowCardinality(String)` on `event_kind`/`mcp_method`/`mcp_tool_call_name`** → assumes low distinct counts; the classifier and JSON-RPC method names are bounded, so this holds.
- **Broad test ripple** → the structured-query integration/render/validation tests and fixtures reference `api_type`/`user_id`; they must be retargeted to `event_kind`/`user_hash`. Caught by the compiler and the integration suite.

## Migration Plan

1. Land the rewritten `V1` DDL, the new mapping, the deriver mechanism, validator/query-surface updates, and the JSON-type config in one change.
2. In each environment (no production data): drop the existing `dial_usage_log` table (and `flyway_schema_history` row for V1) so the rewritten `V1` re-provisions the new schema on startup; or apply the canonical DDL out of band when migrations are disabled.
3. Let the daily reconciliation + poller (re-)ingest, populating direct and calculated columns; blocked columns remain at defaults.
4. **Rollback:** revert the change and redeploy; recreate the table from the prior DDL. Since there is no preserved data, rollback is a clean re-provision.

## Open Questions

- Should the monetary columns (`total_price`, `deployment_price`) become queryable now? Current decision keeps them excluded to match existing behavior; revisit if consumers need cost filtering.
- Should the pinned ClickHouse image be bumped to a version where the `JSON` type is GA, removing the experimental flag? Out of scope here; track separately.
