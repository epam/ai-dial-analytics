## Why

`table_definition.version_column` and `identity_column` — the pair that defines a source's total order
for the governed incremental scan — are invisible and unreachable from the API. They can be set only by
an operator migration, so a caller who creates a source through `/v1/tables` cannot make it scannable,
and a consumer of `GET /v1/tables/{name}` cannot tell which column identifies a row, which column carries
its version, or whether the table is scannable at all.

This change opens exactly that: the two columns become caller-settable where the rest of the physical
schema is declared, and readable on the table read surface. Nothing about how they are used changes.

## What Changes

- **`POST /v1/tables/{name}/schema` accepts `identity_column` and `version_column`** for a `source`,
  persisting them on the definition. Each must name a column declared in the same request. Omitting one
  leaves the stored value untouched, so re-posting a `FAILED` table's schema never clears it.
- **Both were made REQUIRED for a source** (422 naming whichever is missing), so a source could not be
  materialized that no enrichment rule could ever page. `harden-scan-metadata` **withdrew that**: the
  identity half cannot be declared truthfully by a multi-column-grain rollup, and a required-but-false
  identity makes the keyset seek skip rows silently. An `upsert_by_key` source MAY name the
  ReplacingMergeTree version `_updated_at`, which such a table physically carries — that is what makes a
  single-column-grain rollup scannable, and it survives.
- **The columns a declared pair names are frozen**: `PATCH .../schema` rejects a drop or a rename of
  either, since nothing repoints the pair afterwards and the table would be left advertising a column that
  no longer exists.
- **An `enrichment` rejects `identity_column` with 422** (it is keyed on its `grain_key`), and initially
  accepted an optional `version_column`. That allowance had no consumer and was withdrawn by
  `harden-scan-metadata`, which restored "an enrichment declares neither".
- **`GET /v1/tables` and `GET /v1/tables/{name}` return both**, absent when the definition has none.

Explicitly unchanged — this change adds an API surface and no behaviour behind it:

- No migration. Both columns already exist (V13) and stay **nullable**; the seeded values for
  `dial_usage_log` (V13) and `rate_analytics` (V16) are untouched.
- The catalog columns stay nullable, so a definition predating the requirement remains representable and
  the scan's 400 still covers it.
- `grain_key`, `enrichment_grain`, `cardinality`, and the enrichment grain contract are untouched.
- No type or nullability rules on either column — added afterwards by `harden-scan-metadata`, which is
  where making the pair required without them proved to be a runtime failure rather than a declaration —
  no ordering-key relationship, no skip index, no `PATCH .../schema` handling that would *set* the pair
  (only the freeze above), no change to the
  scan, the auto-join, rule resolution, or the rule wire.

## Capabilities

### New Capabilities

<!-- None. -->

### Modified Capabilities

- `table-management`: the schema-define contract accepts the two columns for a source and rejects them
  for an enrichment; the table read surface returns them.
- `enrichment-runtime`: the requirement that the pair is operator-only configuration, "NOT accepted as
  caller input on the table-management API", is relaxed to caller-declarable — the scan's own behaviour,
  including its rejection of a source that has no pair, is unchanged.

## Impact

**APIs.** `POST /v1/tables/{name}/schema` gains two members, rejected for an enrichment;
`GET /v1/tables[/{name}]` gains two response fields; `PATCH .../schema` rejects a drop or rename of a named
column. This change also made the pair required for a source — a breaking change, since reverted by
`harden-scan-metadata` before either reached the default branch, so the shipped contract is additive after all.

**Code.** `DefineSchemaRequest`/`DefineSchemaInput` carry the pair; `TableMapper` maps it in and out;
`TableDto` exposes it; `CatalogSchemaWriter.persistSourceSchema` validates membership and persists it;
`TableSchemaCreationService` requires it for a source and rejects it for an enrichment;
`TableSchemaService.requireMutable` freezes the columns it names. `TableDefinitionEntity` is unchanged
(the fields already exist).

**Database.** None.

**Caller's promise.** The scan's exactly-once guarantee rests on `version_column` being assigned at
ingest, monotonic and never backdated, and on `identity_column` being unique per row — physical
properties nothing in ADAS or ClickHouse can verify. Making the pair caller-settable moves that promise
from an operator writing a migration to a caller writing a schema request; the documentation says so, and
this change adds no check that could enforce it.
