## Why

A catalog export always carries each selected object's whole dependency closure, and a table's access always travels for every
role. So an operator who wants to move one pipeline, or one role's policy on a table, into an environment that already has the
tables gets a bundle that also carries the neighbouring tables, the other pipelines writing to them and every role's entry on every
covered table. Importing it under `SKIP_IF_EXISTS` leaves existing definitions alone, but it still creates any neighbour missing on
the target and replaces every carried role's entry, not only the one meant to move. An export needs a way to carry exactly the
selected pipeline, or exactly the selected roles' entries of a table, and nothing else.

## What Changes

- An `ExportRequest` component gains three optional selectors:
  - `dependencies` (pipeline only, default `true`): `false` exports the pipeline alone. No input, target or enrichment it reads is
    carried or listed, and no access travels with it; import already accepts a pipeline whose tables exist on the target.
  - `access_only` (table only, default `false`): `true` carries the table's access entries and nothing else — no table definition,
    no source table, no pipeline, and no enrichment its column policies name. The table is named only as a key of the bundle's
    `access` member.
  - `roles` (table only): carries only the listed roles' entries of that table. Without it every role's entry travels, as today.
- `access_only` and `roles` need `include_access: true` (`422` `catalog_export_invalid` otherwise). A selector on the wrong
  component type, an empty `roles` or a blank role is `400` `bad_request`. A listed role with no stored entry on the table is `404`
  `not_found`. Two components naming one object with different selections are `422` `catalog_export_invalid`.
- `roles` also stops an unlisted role's column policy from pulling its enrichment into the closure.
- An access-only table or a `dependencies: false` pipeline carries no definition of anything it does not name, so the rules that
  refuse a definition that cannot travel (a PENDING, FAILED or OTLP landing table reached, an unresolvable declaration) do not
  apply to it. A table selected `access_only` still needs `TABLE_GET` (`404` `not_found` otherwise); a `dependencies: false`
  pipeline needs only `REGISTRY_READ`, which already exposes its whole declaration.
- Import relaxes one rule: an `access` key may name a table that exists on the target, besides one the bundle carries under
  `tables` or `required_system_tables`. A key naming a table that is in neither place stays a validation error. Everything else
  about access import (per-role upsert, `PUT` checks, all-or-nothing write, audit) is unchanged.
- The bundle's shape does not change, so `format_version` stays `4`, the version the current release introduces.
- `docs/catalog-promotion.md` gains a section on promoting one pipeline or some roles' access into an environment that has the
  tables.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `catalog-export-import`: a new "Selective export" requirement defines the three selectors. "Catalog export endpoint" names them
  in the body. "Export dependency closure" and "Excluded and system tables on export" exempt an access-only table and a
  `dependencies: false` pipeline. "Access export" counts an access-only table as a candidate whose entries pull nothing in, and
  filters a table's entries by `roles`. "Access import" lets an `access` key name a table that exists on the target. "Import
  entries" states the existing rule that a dependency in neither the bundle nor the target refuses the object, and "An SQL
  declaration's import dependencies are read from its statement" defers an unlisted system-table dependency to it. The runbook gains
  the selective-promotion step.
- `table-access-control`: "Pipeline and export paths refuse a hidden column" limits the export path to tables a bundle carries under
  `tables`, since an access-only export carries no columns.

## Impact

- **API**: `ExportComponent` gains `dependencies`, `access_only` and `roles` on `POST /v1/catalog/export` and `/export/preview`.
  Import endpoints keep their shape; a bundle whose `access` names a target-only user table now imports instead of being
  refused, and a dependency the bundle does not carry must be ACTIVE on the target, not only exist there.
- **Code**: `web/dto/catalog/ExportComponent`, `service/catalog/transfer/CatalogExportService` (selection and walk),
  `CatalogImportPlanner` (access key check), `ImportTargetReader` (read the tables `access` names), and the export and bundle doc
  strings that feed `docs/openapi.json`.
- **Data**: no migration.
- **Config**: no new property.
- **Compatibility**: bundles stay `format_version` `4`; an export without the new selectors is byte-for-byte as today.
