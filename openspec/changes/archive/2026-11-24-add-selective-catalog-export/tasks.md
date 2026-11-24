## 1. Request shape

- [x] 1.1 `ExportComponent`: add optional `dependencies` (`Boolean`), `access_only` (`Boolean`, JSON `access_only`) and `roles`
      (`List<String>`), each with an `@Schema` description. Bind `roles` with `StrictStringListDeserializer`, as `BundleAccessEntry`
      binds its lists, so an item that is not a JSON string is `400` rather than coerced. In the compact constructor, refuse as
      `400 bad_request` by throwing
      `InvalidRequestParameterException` (its message survives Jackson's wrapping, so the `400` names the problem; name the accepted
      selectors, not the rejected value): a non-null `dependencies` on a table, a non-null `access_only` or `roles` on a pipeline, an
      empty `roles`, and a role that is blank once trimmed
- [x] 1.2 Update the doc strings the change makes false, which also feed `docs/openapi.json`: `ExportRequest` (Javadoc and `@Schema`),
      `CatalogTransferController`'s export `@Operation` (it says every selection travels "with their dependency closure" and that each
      table needs `TABLE_GET` and is refused for sensitive columns), `CatalogBundle.access`'s `@Schema` (a key may name a table the
      bundle does not carry), `CatalogBundle.required_system_tables`' `@Schema`, the `CatalogBundle` class Javadoc and the
      `ExportPreview` system-tables `@Schema` (they say the list holds every system table the bundle's objects depend on; reword to
      the system tables the carried closure reaches, those the selection names and, with access, those whose access it carries — a
      pipeline exported with `dependencies: false` brings none in), the `CatalogImportPlanner.planAccess` Javadoc, the `ExportComponent` record Javadoc, the `withAccess`
      Javadoc (its candidate list), the "Closure", "Access" and "What an export cannot carry" sections of the `CatalogExportService`
      class Javadoc, `ExportPreview.AccessItem`'s `leftBehind` (an unlisted role is neither carried nor listed), and the Javadocs of
      `InvalidRequestParameterException` and `DefaultExceptionHandler`'s authored-message lookup and `handleInvalidRequestParameter`
      comment, which name only `@InitBinder` editors and handlers as throwers; the `CatalogExportInvalidException` Javadoc (its list
      of reasons); the `followAccess` Javadoc ("a kept candidate entry"); and, if 2.4 uses `get`, the `TableAccessService` class
      Javadoc's list of methods the catalog transfer calls

## 2. Export

- [x] 2.1 In `CatalogExportService.resolve`, after the `TABLE_GRANT` check and before the component loop (where names are resolved):
      `422 catalog_export_invalid` for `access_only: true` or `roles` without `include_access`, and for two components naming one
      object with different selections, compared with defaults applied and `roles` as a set of trimmed names. Identical selections
      count once
- [x] 2.2 `Walk`: record a `dependencies: false` pipeline in `pipelineReasons` and an `unexpanded` set without queuing `expand`,
      through its own idempotent method (never through `pipeline()`, so a second identical component does not expand it);
      `pipeline()` checks `unexpanded.remove(name)` before its early return and, when it succeeds, queues `expand`, keeping the
      reason `selected`
- [x] 2.3 `Walk`: record an `access_only` table in an `accessOnly` map and in `loadedTables`, but in neither `tables` nor
      `systemTables`, so a closure visit to the name proceeds as for any table (under `tables` or `systemTables` with the closure's
      reason, expanded, or `followAccess` queued for a system table). After `run()`, drop from `accessOnly` every name a closure
      reached; the rest are the access-only tables
- [x] 2.4 After loading a table component's table and checking `TABLE_GET` (a `404` naming no role), refuse a listed role with no
      stored entry as `404 not_found` naming the table and the role, in the component loop before the walk. `tableAccess.get(table,
      role)` already gives that `404` with the one-role `GET`'s trim-and-exact rule (or read `list(table).roles()`)
- [x] 2.5 Add one Walk helper that builds a table's `PortableAccess` (`tableAccess.portable`) and, for a table whose component lists
      `roles`, keeps only those roles in `entries()` and `leftBehind()`. Use it at both build sites: `followAccess` (so an unlisted
      role's column policy pulls nothing in) and the `computeIfAbsent` in `withAccess`
- [x] 2.6 `withAccess`: add the access-only tables to the candidates, and each access-only system table to `required` with the reason
      `selected`
- [x] 2.7 `preview`: list a `dependencies: false` pipeline with the reason `selected`, and leave an access-only user table out of the
      objects (it appears in `access` only, when covered or with something left behind)
- [x] 2.8 Confirm an export with no selector resolves exactly as before (existing export tests stay green unchanged)

## 3. Import

- [x] 3.1 `ImportTargetReader.tableNames`: give the static method an `includeAccess` parameter and, when it is true, also load the
      tables the bundle's `access` keys name; mention access keys in its Javadoc and in `ImportTarget.tables`'
- [x] 3.2 `CatalogImportPlanner.planAccess`: accept a key with no bundle owner when the target has the table; keep the validation
      error, reworded to say the table is in neither the bundle nor the target, when it does not, still quoting the table name
      (`bundleLevelAccessProblemsFailEveryEntryUnderThem` and `importValidatesAccessWithThePutRules` assert it)
- [x] 3.3 No other import change is expected: the validator, apply step, audit and compensation already work by table name. The
      round-trip tests of 4.2 pin it

## 4. Tests

- [x] 4.1 `CatalogTransferIntegrationTest`, export: one test per export scenario of "Selective export" — the pipeline alone (with
      and without access), a pipeline alone whose target is PENDING, that reads an OTLP landing table (the existing OTLP test creates a
      sink) or whose stored declaration no longer resolves (through `jdbc`, in a way `PipelineMapper.toBundle` still maps and the
      `source_table_iff_enrichment` check allows, e.g. `SET type = 'SOURCE', source_table = NULL` on the target of an enrich pipeline
      with no `input_table`), a pipeline another component reaches (and the
      `422` when its input is PENDING), an access-only OTLP landing table, the role `404` winning over a closure's `422`, one role's policy alone
      (bundle and preview, no `left_behind` for the unlisted role), a column policy travelling without its enrichment, an access-only
      system table, an access-only PENDING table, `roles` on a table that travels whole, an unlisted role's column policy pulling
      nothing in, an access-only user table and an access-only system table another component reaches (reason, column-policy edge),
      the malformed-selector `400`s, the `422`s with their order against `404`, identical selections counting once, and the role `404`.
      This class runs with security off, so the orderings against `403` belong in 4.3
- [x] 4.2 `CatalogTransferIntegrationTest`, round trip: a pipeline-only bundle imported into a target that has its tables
      (`CREATE`, disabled, `completed`) and into one that lacks its target (`FAIL` naming it, `422`); a policies-only bundle imported
      under `FAIL_IF_EXISTS` (`REPLACE`, other roles kept, definition unchanged, generation moved, one audited `Update` with the
      `importId`), with an entry naming a column the target lacks (`FAIL`), and onto a target lacking the table (validation error,
      entries `FAIL`); an access-only `dial_usage_log` bundle whose column policy names `usage_sentiment.label`, imported into a target
      without `usage_sentiment` (`FAIL` naming it); a pipeline whose input is in neither the bundle nor the target (`/import` `422`),
      and the same pipeline beside a valid entry for a target-only table (the preview still reports that entry `REPLACE`).
      For the pipeline round trip use a fixture pipeline that was never enabled (`cx-dul-live`): deleting one that was enabled marks
      its name reused, and the import then needs `acknowledge_reused_names`
- [x] 4.3 `CatalogTransferAuthIntegrationTest` (security on): an access-only table the caller cannot get is `404` and names no role;
      a caller whose column policy hides a column still exports the table access-only; a `dependencies: false` pipeline exports for a
      caller with no `get` on its target; conflicting selections from a caller with `include_access` but no `TABLE_GRANT` are `403`;
      a malformed selector from a caller without `REGISTRY_READ` is `400`, while `access_only` without `include_access` from that
      caller is `403`
- [x] 4.4 `CatalogImportPlannerTest`: a key naming a table only the target has gives no validation error and no failed entry, and its
      `prev` is the target's stored entry (the action is set by the validation pass, so `CREATE`/`REPLACE`/`SKIP` belong to 4.2); a key
      naming a table in neither place is a validation error with every entry `FAIL`. The pipeline-dependency rule is already covered by
      `missingDependencyFailsThePipeline` and `deletedNameThatNeverRanIsNotReused`
- [x] 4.5 Regenerate `docs/openapi.json` with `./gradlew updateOpenApi` for the new `ExportComponent` members and doc strings

## 5. Docs and specs

- [x] 5.1 `docs/catalog-promotion.md`: add a section on promoting one pipeline (`dependencies: false`) or some roles' access
      (`access_only`, `roles`) into an environment that already has the tables — the import preview shows a missing table as `FAIL` on
      the pipeline or access entry, and enrichments a carried column policy names must already exist on the target. Revise the
      passages the selectors make partly false: that system tables the bundle needs travel under `required_system_tables`, that the
      export carries every role's entry on each covered table, and that a column policy always brings its enrichment into the export
- [x] 5.2 `docs/release-0.24-upgrade.md`: add a §15 bullet for the export selectors, and qualify §15's "every covered table's role
      entries" with `roles`
- [x] 5.3 No `docs/configuration.md` change: the change adds no configuration property
- [x] 5.4 At archive, edit the `## Purpose` of `openspec/specs/catalog-export-import/spec.md` directly: it says pipelines are exported
      "with their dependency closure", which a selector now opts out of, and a delta cannot change a Purpose

## 6. Verify

- [x] 6.1 `openspec validate add-selective-catalog-export --strict`
- [x] 6.2 `.claude/skills/pr-ready/hygiene-sweep.pl` on the branch
- [x] 6.3 `./gradlew clean build` passes
