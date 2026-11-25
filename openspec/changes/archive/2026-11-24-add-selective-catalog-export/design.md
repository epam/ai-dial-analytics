## Context

See proposal.md for why. Three facts about the current code shape the approach:

- The export resolves a request with one closure walk (`CatalogExportService.Walk`): a selected user table queues `expand(table)`
  (source, the pipeline that targets it, and with access the column-policy edge `followAccess`), a system table is never expanded
  and, with access, queues only `followAccess`, a selected pipeline queues
  `expand(pipeline)` (input, target, enrichments read). Refusals for what cannot travel (`requireExportable`) and the column
  checks (`requireSensitiveVisible`) run over the tables the walk collected. Access candidates are computed afterwards in
  `withAccess`.
- The import planner already accepts a pipeline whose dependencies are absent from the bundle but present on the target
  (`requireDependency`: in the bundle, or `target.tables()` has it). Only access is strict: `planAccess` fails every entry on a key
  that names neither a bundle table nor a required system table.
- `ImportTargetReader.read` loads only the target tables the bundle names or depends on, and that map is what the planner consults
  for a table's stored entries. The validator re-reads each entry's table by name and compares against the stored entry the planner
  recorded; the apply step (`TableAccessService.importEntries`) finds and locks tables by name.

## Goals / Non-Goals

**Goals:**

- Carry exactly one pipeline, or exactly some roles' entries of one table, through the existing export, preview and import calls.
- Keep the bundle shape and `format_version` unchanged, and leave every export without selectors resolving as today.

**Non-Goals:**

- Choosing what to apply at import time from a full bundle.
- A table definition alone without its closure (`dependencies: false` on a table). Nobody needs it yet; the selector is refused on a
  table so it can be added later without a contract change.
- Mapping role names between environments. Roles stay verbatim, as access import already copies them.
- Picking parts of one role's entry (privileges without policies, or the reverse).

## Decisions

### D1. Selectors on the existing component, not a new component type

`dependencies` on a pipeline component, `access_only` and `roles` on a table component. A policy has no identity of its own — it is
the entry of a role on a table, stored on the table's own row and addressed as table plus role — so it is selected through its table.

- *Rejected: a separate `{ "type": "access" }` component.* It would read as if access were a standalone object, and it duplicates the
  table lookup, `TABLE_GET` check and system-table rules the table component already has.
- *Rejected: carrying the table definition along with the chosen roles and relying on `SKIP_IF_EXISTS`.* Import never overwrites a
  definition, so it is harmless on a target that has the table, but it forces `SKIP_IF_EXISTS` (`FAIL_IF_EXISTS` answers `409`), it
  creates the table on a target that lacks it, and it runs the export's column checks over a definition nobody asked to move. The
  maintainers want a policies-only bundle.

### D2. No `required_tables` list; relax the access-key rule instead

An access-only user table is named only as a key of `access`. Import accepts a key that names a table existing on the target, and
otherwise keeps today's validation error.

- *Rejected: a new top-level `required_tables` member*, mirroring `required_system_tables`, to give each referenced user table its own
  `SKIP`/`FAIL` line in the preview. It changes the bundle shape, so `format_version` would move to `5` and every older ADAS would
  refuse every new bundle, to gain only a separate line for a failure the access entries already report.
- *Consequence:* a `dependencies: false` pipeline lists nothing either — not even the system tables it reads. Import's
  `requireDependency` already checks them against the target, and listing them would make them access candidates and carry their
  entries, which the selector exists to avoid.

### D3. Walk changes

`resolve` registers every component before `walk.run()`, so a closure always reaches a selected name after the selection recorded
it. Both selectors therefore keep their state outside the maps a closure visit checks first.

- A `dependencies: false` pipeline is recorded in `pipelineReasons` and in an `unexpanded` set, without queuing `expand`.
  `pipeline()` returns early for a known name today; it must first check `unexpanded.remove(name)` and, when that succeeds, queue
  `expand` for it, keeping the reason `selected`.
- An `access_only` table is recorded in an `accessOnly` map (name to its definition, also put into `loadedTables`, since `withAccess`
  reads definitions from there), and in neither `tables` nor `systemTables`. A closure visit to the name then misses both maps and
  proceeds as for any table: it lands under `tables` or `systemTables` with the closure's reason, and is expanded, or for a system
  table has `followAccess` queued. After `run()`, the names a closure reached are removed from `accessOnly`; what remains are the
  access-only tables. Keeping access-only system tables out of `systemTables` until then also keeps them out of `requireExportable`,
  which iterates `tables` and `systemTables` and refuses PENDING, FAILED and OTLP tables; `requireSensitiveVisible` iterates `tables`
  only.
- `withAccess` adds the access-only tables to the candidates, and adds each access-only system table to `required` with the reason
  `selected`.
- A table's `PortableAccess` is built at two sites: `followAccess` during the walk, which caches it in `followed`, and the
  `computeIfAbsent` in `withAccess`. Both go through one Walk helper that calls `tableAccess.portable` and then, for a table whose
  component lists `roles`, keeps only those roles in `entries()` and `leftBehind()`. Every reduction step is per role, so filtering
  after the reduction equals filtering before it, and `TableAccessService` needs no change. Filtering at `followAccess` is what stops an
  unlisted role's column policy from pulling its enrichment in.
- The `unresolvable` map is filled only by `expand(pipeline)`, so an unexpanded pipeline is never refused as unresolvable.

### D4. Validation placement

- Wrong-type selectors, an empty `roles` and a blank role are refused while the body is bound: a compact-constructor check on
  `ExportComponent` throws `InvalidRequestParameterException`; Jackson wraps it in a `ValueInstantiationException` inside the
  `HttpMessageNotReadableException`, and `DefaultExceptionHandler` walks that cause chain and answers `400 bad_request` with its
  message (a plain `IllegalArgumentException` would give only the generic body message). A DTO constructor is a new kind of thrower
  for that exception, whose Javadoc and the handler's name only `@InitBinder` editors and handlers, so both are updated; and
  method security (`@RequiresPrivilege`) runs after binding, so these precede every privilege check. A compact constructor is
  preferred to an `@AssertTrue` getter, which could leak into the OpenAPI schema.
- `access_only`/`roles` without `include_access`, and conflicting duplicates, are `CatalogExportInvalidException` (`422`), thrown in
  `resolve` after the `TABLE_GRANT` check and before the component loop, which is where names are resolved (`findById` and
  `walk.pipeline` throw their `404`s there). Selections are compared with defaults applied and `roles` as a set of trimmed names, so
  `{t}` and `{t, access_only: false}` are one selection.
- An unknown role is a `TableNotFoundException`-style `404 not_found` thrown once the table is loaded and its `TABLE_GET` checked, so a
  caller who may not get the table learns nothing about its roles. Role comparison reuses the trim-and-exact rule of the access API's
  one-role `GET`.
- An access-only table with no entry the export can carry is `CatalogExportEmptyException`, `422` `catalog_export_empty`, thrown in
  `withAccess` once the reduction has run, on the preview too. It is `422` because the body is well formed and only stored state leaves
  it nothing to carry, as with `catalog_export_invalid`; this module keeps `400` for refusals made while the body is read. Its own
  code tells a client the selection is not wrong but empty. The preview refuses as the export does, as for every other export
  refusal; the message names each role that stays behind with its error code, and the preview of the same table without
  `access_only` gives the structured `left_behind`.

### D5. Import

- `ImportTargetReader.tableNames` also adds the bundle's `access` keys when `includeAccess` is true, so a target-only table is loaded
  with its stored entries.
- `requireDependency` requires a dependency the bundle neither carries nor lists to be ACTIVE on the target, not only to exist there.
  Before, only a hand-edited bundle had such a dependency; a pipeline exported alone has nothing else. A PENDING or FAILED one would
  plan the pipeline `CREATE` as a draft that cannot be armed, and a table a pipeline reads cannot be deleted for repair while the
  pipeline exists, so the object fails in the preview instead.
- `planAccess` treats a key with no owner as valid when `target.tables()` holds it as a user table. Nothing else on the import side changes: the
  planner already reads the stored entries for every key before its owner check, the validator's table-refused check scans only plan
  objects (so it never refuses a table named only by access), the apply step and its audit work by table name under the import scope,
  compensation touches only created objects, and a policies-only bundle has no object that could conflict under `FAIL_IF_EXISTS`.

## Risks / Trade-offs

- [Format 4 is new in the current release, so no released ADAS reads these bundles at all] → Nothing to mitigate beyond what the
  release's upgrade notes already say about format 4: upgrade the target first.
- [A pipeline-only bundle lists no tables, so a missing table shows only on the pipeline entry] → The entry is `FAIL` naming the
  table, in the preview, before anything is written; arming still reports column mismatches through `arm_problems`.
- [An access-only bundle carries a column policy naming an enrichment the target lacks] → The access `PUT` checks in the validation
  pass already refuse it, naming `<enrichment>.<column>`.
- [Role names differ between the two environments' identity providers] → Out of scope; the runbook already tells operators to review
  role names in the import preview.

## Migration Plan

No migration and no config. Rollback is a plain redeploy: no stored state changes, and bundles stay format 4.
