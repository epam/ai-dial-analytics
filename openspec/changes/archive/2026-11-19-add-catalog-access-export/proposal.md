## Why

A catalog bundle carries tables and pipelines but never their access, so after every promotion an operator re-creates each
role's privileges, row policies and column policies by hand, one `PUT /v1/tables/{name}/access?role=` at a time. System
tables carry access too (`get`, `read`, `sensitive`, row and column policies), and today no bundle can carry it at all. An
opt-in access section lets one export/import move a whole environment's table access, system tables included, through the
same validated, audited path.

## What Changes

- `POST /v1/catalog/export` and `/export/preview` accept `include_access: true` in the `ExportRequest` body. The bundle then
  carries a top-level `access` member: for each covered table, every role's entry in the shape `GET .../access` returns
  (`privileges`, `rows`, `columns`). Without the flag the bundle is as today and has no `access` member.
- With `include_access`, coverage reaches system tables. Every system table in the bundle carries its access, including one the
  closure reaches as a pipeline's input. Exporting everything covers every system table the caller may get that keeps an entry
  once unportable state is left behind. An explicit selection
  may name a system table (today `422 catalog_export_invalid`) to export its access, together with any enrichment its column policies name. Each such table joins
  `required_system_tables`; its definition is still never exported.
- With `include_access`, a column policy naming `<enrichment>.<column>` pulls that enrichment into the export closure, as a
  pipeline filter token already does, with everything the enrichment pulls in itself. Every enrichment a carried entry names is therefore in the bundle
  or required, so a fresh bundle meets no missing enrichment on a target that has the same system tables. Apart from that, a system table, selected or reached, is never expanded.
- Only state a `PUT` would accept now travels. Leftover state written by an earlier build stays behind and is listed in the
  export preview's `left_behind`, with the `error` a `PUT` of it would answer. That covers an enrichment's read-side privileges or
  policies, `write` or `modify` in a `*` entry or on a system table, an entry for a role that is now a bundle or privilege name, and an entry whose
  column policy names an enrichment the export lists under `skipped` (`dependency_skipped`).
- Exporting or previewing an export with access requires global `TABLE_GRANT`, because the entries disclose which provider roles
  exist.
- `POST /v1/catalog/import` and `/import/preview` accept the query parameter `include_access` (default `false`). When true,
  and the caller holds `TABLE_GRANT`, each role in the bundle replaces that role's entry on the target table (per-role
  upsert). Roles the bundle does not name are left untouched, an import never removes an entry, and role names are copied
  verbatim. Access lands on created tables, skipped existing user tables and required system tables alike.
- Import validates every entry with the same rules as `PUT .../access` in the shared rolled-back validation pass, reports it
  in a new `access` group of `ImportPreview` / `ImportResult` (`import_action` `CREATE`, `REPLACE`, `SKIP` or `FAIL`), and
  applies all access in one transaction after every object is created, so access either lands whole or not at all. The
  validation pass applies the access before arming, so `armable` reflects it.
- Bundle `format_version` goes to `4`. An older ADAS refuses a version-4 bundle with the readable
  `catalog_bundle_version_unsupported`; version 1–3 bundles still import unchanged.
- `docs/catalog-promotion.md` gains the access path in its "Grant access" step.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `catalog-export-import`: export gains the opt-in access section and system-table coverage. Import gains the opt-in per-role
  upsert, its preview/result entries, its authorization, and a validation pass that applies access before arming. The bundle
  format moves to version 4. The "never mutate an existing object" and "no grants" rules gain the access exception. "Import
  entries" names the new group, "Export dependency closure" gains the column-policy edge and states that a system table is otherwise never expanded, and the
  runbook gains the access review step.
- `table-management`: "Table definitions are promoted … through catalog export and import" says an import never patches an
  existing table's definition, while an access import writes onto existing tables' access.
- `table-access-control`: "Catalog import writes no grants" becomes conditional on the import requesting access, "Access
  configuration generation" counts an access import among what moves the generation, and "Caller-effective privilege reporting"
  names the catalog transfer among the `TABLE_GRANT`-gated places a policy can be read.

## Impact

- **API**: `ExportRequest` gains `include_access`; both import endpoints gain the `include_access` query parameter;
  `ExportPreview` gains an `access` list; `ImportPreview` / `ImportResult` gain an `access` group; `ImportAction` gains
  `REPLACE`. When the flags are off, the only changes are that every export writes `format_version` `4` and import responses carry
  an empty `access` list.
- **Code**: `service/catalog/transfer/*` (`CatalogExportService`, `CatalogImportPlanner`, `CatalogImportValidator`,
  `CatalogImportService`, `ImportTargetReader`), `web/dto/catalog/*`, `CatalogTransferController`, and
  `TableAccessService` (an in-process entry write without a client generation).
- **Data**: no migration. Access writes go to the existing `table_definition` access columns, bump the access generation as a
  `PUT` does, and are audited as table revisions tagged with the import's `import_id`.
- **Config**: no new property.
- **Compatibility**: a version-4 bundle cannot be imported by an ADAS that reads only version 3.
