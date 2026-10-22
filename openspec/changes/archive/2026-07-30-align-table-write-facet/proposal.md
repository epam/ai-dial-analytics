## Why

An aggregate rollup must be stored so that re-writing a group key **replaces** its row rather than
appending a second one — physically, `ReplacingMergeTree(_updated_at)` ordered by the group keys.
ADAS can only materialize a `source` as a plain `MergeTree`, and a ClickHouse engine cannot be
`ALTER`ed after `CREATE`, so a rollup table created today is permanently the wrong shape. The write
discipline therefore has to become declarable **before** the follow-up `add-aggregate-pipeline`
change can create a single rollup target.

The same design decision that introduces the `write` facet also settles that
**aggregation is not a table kind** — it is a pipeline kind whose output is an ordinary `source`.
The catalog's third table type, `aggregation`, has been accepted-but-inert since it was specified;
it is now known to be dead, and leaving it in place would invite callers to register rollups as a
type that no code path will ever materialize, query, or join.

## What Changes

- **New immutable table facet `write ∈ {append, upsert_by_key}`**, declared at `POST /v1/tables`
  alongside `type` and defaulting to `append`, so every existing caller is unaffected. It is fixed
  at create like an enrichment's `source_table` — the engine it selects is frozen at
  materialization, so `PATCH` cannot change it.
- **Engine selection becomes a function of `(type, write)`**: an `upsert_by_key` source materializes
  as `ReplacingMergeTree(_updated_at)` ordered by its `ordering_key` and carries the hidden
  `_updated_at` system column; an `append` source stays a plain `MergeTree`. Enrichment behaviour is
  unchanged — it remains `ReplacingMergeTree(_updated_at)` keyed on its grain.
- `_updated_at` stays a **hidden system column** (never in `column_mapping`, never exposed in the
  flat query schema) and stays caller-undeclarable, now on upsert sources as well as enrichments.
- **BREAKING: the `aggregation` table type is removed.** `POST /v1/tables` with
  `type: "aggregation"` is rejected as an unknown type instead of creating an inert definition. The
  API is unreleased and the type materialized nothing, so no data or client is carried forward.
- Reads need no change and gain no caveat: the structured query path already applies ClickHouse
  `final = 1` to every read, so an `upsert_by_key` source returns exactly the latest row per key.
  This becomes an explicit, tested guarantee rather than an incidental property.

## Capabilities

### New Capabilities

None — this change modifies existing behaviour only.

### Modified Capabilities

- `table-management`: adds the `write` facet (declaration, immutability, engine selection, the
  `_updated_at` system column and its reservation) and removes the "aggregation type is accepted but
  inert" requirement.
- `structured-query`: the non-queryable-entity rejection reason no longer enumerates `aggregation`.
- `query-schema-discovery`: entity listing and the flat schema no longer mention aggregation tables;
  an `upsert_by_key` source is a queryable entity like any other source.

## Impact

- **Catalog**: `table_definition` gains `write_mode`; the `type` CHECK narrows to
  `('SOURCE','ENRICHMENT')` (migration `V19`).
- **API**: `POST /v1/tables` accepts an optional `write` member; `GET /v1/tables[/{name}]` returns
  it. `type: "aggregation"` now 422s.
- **Code**: `service/table/` (`TableType`, new `TableWriteMode`, `TableDefinitionEntity`,
  `CreateTableInput`, `TableView`, `TableSummary`, `TableDdlRenderer`, `TableSchemaCreationService`,
  `TableColumnRules`), `web/dto/table/`, `web/mapper/table/TableMapper`.
- **Docs**: `openspec/project.md` (the engine sentence and the line asserting aggregation tables are
  not queryable).
- **Downstream**: unblocks `add-aggregate-pipeline`. No Enrichment Runner impact — it neither reads
  nor writes the table type or write facet.
