## Why

ADAS can **compute** an aggregate at query time (`mode: aggregate`, `group_by`, `having`) but cannot
**persist** one. Every rollup is therefore recomputed from raw events on every read, and no rollup can
outlive the raw serving window it was derived from. The tables-and-data-movement design names the missing object: an
**`aggregate` pipeline** — one of three pipeline kinds (`external | aggregate | enrich`) whose output is
an ordinary `source` table. A design/code alignment review calls pipelines *"the missing
pillar"* of the ADAS control plane: there is no pipeline object of any kind today, and `data-sync` is one
hardcoded `external` pipeline.

Two concrete things are blocked:

- **Group entities.** `conversations` is a `conv_rollup` spine (one row per `chat_id`)
  plus enrichment children; `turns` is the same shape on `trace_id`. The spine is what turns "a pile of
  rows sharing a key" into an entity an enrichment can attach to — and it is exactly an `aggregate`
  pipeline. Without it there are no cheap per-group facts to filter on, which is the main
  LLM-cost lever for group entities: a Class-2 group rule calls a model once per conversation, so
  `turn_count >= 2 AND error_count > 0` is the difference between enriching some conversations and
  enriching all of them.
- **Cost/usage rollups** (`usage_daily`-style) that must be retained long-term independently of the
  1-year raw window.

The prerequisite landed in `align-table-write-facet`: a target can now be declared
`write: upsert_by_key` and materializes as `ReplacingMergeTree(_updated_at)` keyed on its
`ordering_key`. The Enrichment Runner needs **no** changes — the runner's design keeps it on `enrich` (sql/llm) and
puts the aggregate path in-DB.

## What Changes

- **New `pipeline` registry object** (`name`, `kind`, `target`, `input_table`, `filter`, `spec`,
  `trigger`, `enabled`, plus runtime state) with an admin-only control plane at `/v1/pipelines`:
  register, read/list, patch, delete, **preview** (dry-run), and **refresh** (operator-triggered run).
  Only `kind=aggregate` is implemented; the discriminator exists so `external`/`enrich` can converge
  onto this object later without a second re-modelling.
- **One pipeline per target table.** A pipeline targets a `source`, an enrichment `rule` targets an
  `enrichment`, so the two registries can never contend for the same table.
- **An aggregate spec** in the design's engine-agnostic shape: `group_by` (plain columns and at most one
  `trunc` time bucket) plus `measures` (one function over one column — no expression grammar inside a
  measure), compiled internally through the existing structured-query translator rather than a second
  SQL builder.
- **`periodic` freshness only, driven by an ADAS-internal scheduler.** A tick picks up due pipelines and
  runs each as **probe-then-write**: one keyset probe fixes the window end, then a single
  `INSERT … SELECT` recomputes every group key the window touched, in full. `incremental` is rejected at
  registration with a pointer to the deferred capability.
- **Register → preview → enable.** A pipeline lands `enabled: false`; `POST …/preview` renders the
  compiled statement and a bounded sample of its `SELECT` half without writing.
- **Input-table delete safety**: `DELETE /v1/tables/{T}` is rejected with 409 while a pipeline reads
  `T`, extending the existing block-only delete guard.
- Sensitive-column governance is enforced on the input at registration **and** at execution, so a
  measure cannot launder a sensitive column into a non-sensitive rollup column.

## Capabilities

### New Capabilities

- `aggregate-pipeline`: the pipeline registry object and its control plane (register/read/patch/delete,
  preview, refresh), the aggregate spec vocabulary (`group_by` / `measures` / `freshness` / `trigger`)
  and its validation, target-shape governance, sensitive-column governance, the scheduled refresh
  execution model and its correctness/idempotence contract, the pipeline's runtime state, refresh
  metrics, and the error contract.

### Modified Capabilities

- `table-management`: deleting a table that a pipeline reads as its input is rejected with 409 (today's
  delete guard blocks only on dependent enrichments); deleting a pipeline's target removes the pipeline
  with it.

## Impact

- **Catalog**: new `pipeline` table (migration `V20`), FK to `table_definition` on both `target`
  (`ON DELETE CASCADE`) and `input_table`.
- **API**: new `/v1/pipelines` surface, `FULL_ADMIN` only. One behaviour change on an existing endpoint
  (`DELETE /v1/tables/{name}` gains a 409 case).
- **Code**: new `service/pipeline/` package (registry, spec model + validator, compiler, refresh
  service, scheduler) and `web/{controller,dto,mapper}/pipeline/`. Reuses
  `StructuredQueryBuilder`/`ExprTranslator` for compilation, `ClickHouseInsertSelectExecutor` for the
  write, `ScanSourceMetadata`/`CursorPredicates`/`CursorColumns` for windowing, `FilterSqlValidator` and
  `SensitiveProjectionGuard` for governance — no new SQL generation and no new scan machinery.
- **Config**: new `aggregate.*` group (enabled, tick cron, safety lag, window limit); descriptions in
  `docs/configuration.md` only.
- **Observability**: refresh metrics are specified in the new capability itself, following the
  convention that each capability owns its own metric requirements (`data-sync-backfill` does the same);
  the `observability` capability stays scoped to OTLP export configuration.
- **Operations**: the writer credential already needs `SELECT` on scan sources for the Class-1
  `INSERT … SELECT`; this path has the same requirement and adds no new credential.
- **Enrichment Runner**: unaffected — no contract it consumes changes.
- **Unblocks**: the `conversations`/`turns` group entities.
