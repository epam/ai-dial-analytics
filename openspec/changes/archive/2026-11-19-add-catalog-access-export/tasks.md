## 1. Access service seams

- [x] 1.1 In `TableAccessService`, add a public `entryFor(definition, role, body)` that runs `requireNoReadSideOnEnrichment` and then
      `validated`, returning the entry a `PUT` would store or throwing the `PUT`'s exception. `put` keeps calling the two steps
      separately, either side of `requireGeneration`, so its pinned check order is unchanged (a body refusal is still reported only
      after the conflict check). `entryFor` SHALL NOT be `@Transactional`: the export calls it inside its read-only transaction and
      expects refusals, which would otherwise mark that transaction rollback-only (see the note at `CatalogExportService`)
- [x] 1.2 Add `importEntries(Map<table, Map<role, body>>)`:
      - lock the tables in the codebase's lock order: enrichments before their sources, and by name within each group;
      - trim each role;
      - check each entry with 1.1;
      - refuse an entry with no privilege and no policy (`validation_error`, naming `DELETE`);
      - skip an entry equal to the stored one;
      - write the rest and bump the generation and the role mark once per changed role, with no client-generation check;
      - return per role `created` / `replaced` / `skipped`
- [x] 1.3 Add a portable view of a table's entries for export, applying the five reduction steps of "Access export" in order:
      - an enrichment keeps only `write`/`modify`;
      - `*`, and every entry of a system table, drops `write`/`modify`;
      - the remaining entries run through 1.1 on the exporting ADAS, and what remains of a refused one is left behind with its `error`;
      - an entry naming an enrichment listed under the bundle's `skipped` has what remains left behind (`dependency_skipped`);
      - an emptied entry is omitted.

      Return the left-behind parts as `{ role, entry, error }`. Let the export apply steps 1–3 while it walks the closure, since they
      depend only on the entry, and step 4 after it
- [x] 1.4 Unit-test 1.1–1.3 in `TableAccessServiceTest`:
      - `put`'s order is unchanged;
      - an equal entry writes nothing and keeps the generation;
      - two changed roles move it by two;
      - an empty entry is refused;
      - each reduction case of 1.3, including a stored bundle-name role

## 2. Bundle and request shapes

- [x] 2.1 `ExportRequest`: add optional `include_access` (default `false`)
- [x] 2.2 `CatalogBundle`:
      - add the optional `access` member (table → role → entry), bound strictly with the `PUT` body's `privileges`/`rows`/`columns`
        types and no `generation`, and serialized with `rows` and `columns` always present (`@JsonInclude(ALWAYS)`, as
        `AccessEntryBodyDto` does, since the mapper's default is `NON_NULL`). The same applies to `left_behind[].entry`;
      - reject `null` table and role values with `400`;
      - raise `FORMAT_VERSION` to `4` and extend its Javadoc version list;
      - update the `requiredSystemTables` schema text ("never creates or changes one")
- [x] 2.3 `ExportPreview`:
      - add `access` items `{ table, roles, left_behind: [ { role, entry, error } ] }`, sorted by table and absent without the flag;
      - allow the `required_system_tables` reason `access`, and name it in the `ExportEntry.reason` schema text
- [x] 2.4 `ImportPreview` / `ImportResult`:
      - add the `access` list `{ table, role, import_action, prev, next, problems, ineffective_grants }`, plus `status`
        (`created` / `replaced` / `skipped` / `rolled_back`) on the result;
      - add `AccessImportAction` (`CREATE` / `REPLACE` / `SKIP` / `FAIL`) for the access entries, leaving `ImportAction` as it is;
      - update the `ImportEntry.status` description
- [x] 2.5 `CatalogTransferController`:
      - bind `include_access` on both export calls (body) and both import calls (query parameter, default `false`);
      - rewrite the `@Operation` texts that say grants never travel (export: "Grants, row policies and column policies never travel";
        import: "tables, without grants") or that import never changes an existing object (`POLICY_DESCRIPTION`);
      - mention `access` in the export-preview `@Operation` text

## 3. Export

- [x] 3.1 `CatalogExportService`: with `include_access`, both `/export` and `/export/preview` require global `TABLE_GRANT`, checked after
      `REGISTRY_READ` and before the selection is resolved (`403 forbidden`, even for an unknown component); skip the check when
      security is not enforced. Rename `CatalogImportForbiddenException` (already mapped to `403 forbidden` in
      `DefaultExceptionHandler`) to a catalog-transfer name, and reuse it for the export
- [x] 3.2 Allow an explicit system-table selection only with `include_access`, keeping `422 catalog_export_invalid` without it. List the
      selected system table under `required_system_tables`, never under `tables`. Update the class Javadoc ("selecting one explicitly
      is a 422")
- [x] 3.3 Closure, with `include_access`:
      - for every candidate, system tables included (reached, selected and, when exporting everything, every gettable one, which
        `Walk.table` and the export-everything seed skip today), visit every enrichment that an entry kept by steps 1–3 names in its
        column policy, with the reason `column policy of <table>`, so it brings its source, its pipeline and that pipeline's inputs;
      - add no need for it: a skipped enrichment must not drop the candidate as `dependency_skipped` in `withoutUnexportable`;
      - the pulled-in enrichment meets the existing refusals (PENDING or FAILED under a selection, hidden or sensitive columns), and a
        system enrichment joins `required_system_tables`;
      - otherwise keep "a system table is never expanded" for a selected system table too, a system enrichment included;
      - run step 4 after the walk, leaving behind an entry naming an enrichment listed under `skipped`
- [x] 3.4 Build `access` from 1.3 for every covered table, as "Access export" defines coverage after the reduction:
      - add every covered, selected or closure-reached system table to `required_system_tables`, with the reason `access` for a
        system table covered only by exporting everything;
      - write `access: {}` when nothing is covered, and omit the member without the flag
- [x] 3.5 Fill `ExportPreview.access`, including `left_behind`

## 4. Import

- [x] 4.1 `CatalogImportService`: with `include_access`, require global `TABLE_GRANT` once the bundle is parsed (`403 forbidden`,
      before the target's access is read, the object gates of `authorize` and the collision check); skip the check when security
      is not enforced
- [x] 4.2 `CatalogImportPlanner`, with `include_access`:
      - add a validation error when the bundle has no `access` member, names a table outside `tables` and `required_system_tables`,
        has a role key that is blank once trimmed, or has two role keys of one table that trim to the same role;
      - plan one access entry per table and role key, sorted by table, trimmed role and key as written, with `prev` from the target
        (`ImportTargetReader`);
      - `FAIL` every entry under a bundle-level access problem (blank key, duplicate trimmed key, table not carried), naming it;
      - count access `FAIL`s in `ImportPlan.hasProblems()` and in the `422` message;
      - mark an entry `FAIL` when its table's entry is refused (a `FAIL_IF_EXISTS` conflict excepted).

      Without the flag, plan none
- [x] 4.3 `CatalogImportValidator`, after the tables and pipelines are created in the rolled-back pass:
      - `FAIL` every access entry on a table in the pass's `refused` set, naming the table;
      - check every other access entry with 1.1, turning a refusal or an empty entry into a `FAIL` whose problem starts with the
        `PUT`'s `error` code (`<code>: <message>`; `CatalogImportService.describe` alone gives only the message);
      - if `importEntries` fails inside the pass, `FAIL` every access entry with the failure and give every pipeline the `not checked`
        arm problem instead of arming it;
      - if no access entry is `FAIL`, apply the `CREATE` and `REPLACE` ones with `importEntries` and compute `ineffective_grants`
        on the entries as applied; otherwise apply none and leave every `ineffective_grants` empty;
      - arm in either case, so `armable` reflects the imported access whenever it would be written;
      - set `CREATE` / `REPLACE` / `SKIP`;
      - when the pass stops before the access checks, mark every access entry `FAIL` "not checked"
- [x] 4.4 Apply: after every table and pipeline step, run `importEntries` for every covered table inside one transaction, under the open
      `AuditImportScope`:
      - a failure counts as a failed step, runs the existing compensation, and marks every access entry `rolled_back` with the failure;
      - an earlier failure marks every access entry `skipped` with the not-attempted problem;
      - `outcome` is `rolled_back`, or `rollback_failed` when a compensation delete fails, as for an object failure, and the status is
        `200`;
      - record each entry's `status`
- [x] 4.5 Update every text that says grants never travel or that import never writes an existing table, to state the access
      exception:
      - the `CatalogImportService` class Javadoc ("Import never mutates an existing object", "no grants");
      - the `BundleTable` Javadoc ("Grants never travel");
      - the `CatalogExportService.bundleTable` Javadoc;
      - the `CatalogImportPlanner.planSystemTable` Javadoc;
      - the `CatalogImportValidator` class Javadoc (the pass now applies access before arming);
      - the `CatalogBundle` class Javadoc ("names of the system tables they depend on": a system table may now travel for its access);
      - the `ImportPreview.validationErrors` schema text ("format, version, closure, limits": access problems join them);
      - the `CatalogImportPlanner.compare` Javadoc ("Grant lists compare as sets…"), if it describes table grants;
      - the `ExportPreview` schema text ("would require on the target rather than carry");
      - the `CatalogExportService` 422 message for a selected system table ("never exported; select the tables or pipelines that use
        it"), to mention `include_access`;
      - the import-preview `@Operation` text in `CatalogTransferController`, to mention `access`;
      - the `CatalogTransferAuthIntegrationTest` class Javadoc ("grants never exported");
      - the `ImportTargetReader` class Javadoc;
      - the `ImportResult.outcome` schema text ("when an object failed");
      - `ResolutionPolicy` and `ResolutionPolicyUnsupportedException` Javadoc, and the `OVERWRITE` refusal message in
        `CatalogImportService` ("import never changes an existing object");
      - the `TableAccessService` class Javadoc (authorization at the web layer only: the import now calls it in-process behind its own
        `TABLE_GRANT` gate)

## 5. Tests

- [x] 5.1 `CatalogTransferIntegrationTest`: one test per scenario of "Access export", "Access import" and "Access import entries", plus:
      - the modified catalog-export-import scenarios: explicit system-table selection with and without the flag, `Grants never
        exported`, `Imported table has no grants`, `System-table grants untouched`, `Existing access does not conflict`, `An imported row
        policy that confines the importer is reported at arm time`;
      - the table-access-control delta scenarios: `Imported table starts ungranted`, `Imported access obeys the PUT rules`, `A catalog
        import moves the generation as PUTs would`;
      - the table-management delta scenario `An access import changes no existing table's columns or settings`, the `Export preview` reason
        `access`, `A selected system enrichment is not expanded`, and `Arming runs without the access when an access entry fails`
- [x] 5.2 `CatalogTransferAuthIntegrationTest`:
      - `TABLE_GRANT` refusal on `/export`, `/export/preview` and both import calls with `include_access`;
      - the `403` beats an unknown component's `404` and the collision's `409`;
      - security mode `none`
- [x] 5.3 Bundle version tests:
      - a version-5 bundle is `422` naming 5;
      - a version-4 bundle with an unknown access-entry member, or a `null` table or role value, is `400`;
      - version 1–3 bundles still import;
      - update every assertion that expects export `format_version` `3`
- [x] 5.4 Round trip:
      - export a table and `dial_usage_log` with access, including a column policy naming `dial_usage_log_payload`;
      - export only a user table whose column policy names one of its enrichments, and assert that the import into a clean target is
        `completed` with no `FAIL`;
      - import into a clean target, and assert that `GET /v1/tables/{name}/access` returns the same `roles` on both sides;
      - assert that a second import leaves every `generation` unchanged
- [x] 5.5 Atomicity: inject a failure into the access step, and assert that created tables are compensated, existing tables' access and
      generation are unchanged, and every access entry is `rolled_back`
- [x] 5.6 `CatalogImportPlannerTest`: the planner validation errors of 4.2

## 6. Docs and specs

- [x] 6.1 `docs/catalog-promotion.md`:
      - add `include_access: true` to the export in step 1 and `include_access=true` to the preview in step 2 and the import in step 3;
        step 4 ("Grant access") points back to them;
      - in step 2's checklist, add the access review the runbook requirement now names: roles and row-policy values in the import preview, the export
        preview's `left_behind` (the only report of what stayed behind), handling the bundle as access configuration, and `DELETE` for
        a role to remove;
      - say to review every `REPLACE` whose `prev` holds state its `next` drops, such as a leftover row policy on an enrichment
        (replacing it can widen reads);
      - note that an access-only import still needs `TABLE_CREATE`;
      - say that a column policy naming an enrichment brings that enrichment, and its pipeline, into the export, so the import then
        also needs the privileges for creating them;
      - say not to edit or import access on the covered tables while a preview with access runs (its locks make them answer
        `409 table_busy` after `tables.lock-timeout`);
      - update the "Grants never travel" line, the "Import never changes an existing object" line and the "What does not travel" list
- [x] 6.2 `docs/release-0.24-upgrade.md`: rewrite the passages that say bundles are `format_version` `3` (the summary, the
      catalog-bundle section and the checklist row) to say `4`, and say to upgrade a target before importing a bundle exported by 0.24; add the opt-in
      `include_access`, and that import responses now always carry an `access` list and `import_action` may be `REPLACE`
- [x] 6.3 `CLAUDE.md` "What this is": change "(never grants)" to say grants travel only on request. In
      `docs/recommended-catalog/README.md`, keep the "Grant access" note (the recommended bundle carries no access) and mention
      that a bundle exported with `include_access` brings its own
- [x] 6.4 At archive, update directly the `## Purpose` of `openspec/specs/catalog-export-import/spec.md` (it says system tables are
      "never written" and "Import never changes an existing object") and of `openspec/specs/table-access-control/spec.md` (it names only the role-keyed API as managing entries),
      since a delta cannot change a Purpose
- [x] 6.5 No `docs/configuration.md` change: the change adds no configuration property

## 7. Verify

- [x] 7.1 `openspec validate add-catalog-access-export --strict`
- [x] 7.2 `.claude/skills/pr-ready/hygiene-sweep.pl` on the branch
- [ ] 7.3 `./gradlew clean build` passes
