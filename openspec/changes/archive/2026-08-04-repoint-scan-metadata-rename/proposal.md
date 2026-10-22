## Why

`harden-scan-metadata` left the scan-metadata pair frozen against both a drop and a rename, and parked the
rename as an Open Question ("a rename could rewrite the stored name, as the TTL column's rename does"). Two
things came out of looking at what it would take:

- **The rename is safe for the version half and cheap for both.** Nothing persists the *name* except
  `table_definition`. The caller's cursor and the pipeline's server-owned cursor are *values* — `CursorCodec`
  signs `(format, keyId, entity, tupleValues…)` where `entity` is the table, and `pipeline.cursor_version` /
  `cursor_identity` are two typed columns — and ClickHouse `RENAME COLUMN` preserves data, so no cursor is
  invalidated by a rename. Every read path resolves the name at query time through
  `ScanSourceMetadata.cursorColumns`, and `CatalogSchemaWriter.renameColumn` copies type, nullability and
  sensitivity, so a rename cannot break `ScanMetadataRules`' invariants either. The repoint is the same two
  lines the TTL column already gets.
- **But the identity half is load-bearing for something the freeze was protecting by accident, and the same
  hole is already open next to it.** The auto-join fires on the *shared physical name*
  (`source.grain_key = enrichment.grain_key`), and a Class-1 rule requires the target's `grain_key` to equal
  the source's `identity_column`. `TableSchemaService.requireMutable` resolves `grainKey` only when the
  *patched* table is the enrichment, so a **source** column that an enrichment names as its grain key is
  droppable and renameable today — silently breaking that enrichment's join for every query that references
  it, and breaking Class-1 materialize (which projects the grain key *from the source*). Today the identity
  freeze happens to cover the Class-1 case and nothing covers the rest.

A third invariant hole surfaced in the same area: `PATCH .../schema`'s `update` operation deliberately
carries no mutability restriction, and it can set `sensitive: true`. So a materialized source's identity or
version column can be marked sensitive in one PATCH — exactly what `ScanMetadataRules.requireUsable` rejects
at define time — after which `ScanService`'s projection guard answers 403 for every caller without the
sensitive entitlement. The property this project just paid to enforce at declaration is undone by a metadata
patch.

And a rename that *is* allowed needs to reach the runner: the scan page carries the version column **under
its own name**, and the name is published on `GET /v1/rules/{id}` and on the sync feed — but `generation`
bumps only on a rule mutation, so a source-side repoint changes the page's key without any signal to a runner
that refetches by generation.

## What Changes

- **A source column named as any `ACTIVE` enrichment's `grain_key` becomes immutable** — neither droppable nor
  renameable (422, naming the enrichment). This closes the live hole above and is what makes the identity
  rename safe to allow: the case that cannot be repaired (the enrichment side of the join is that table's
  `ORDER BY`, which both `requireMutable` and ClickHouse refuse to rename) is now rejected up front.
- **A `rename` of the column named by `identity_column` or `version_column` is allowed**, and the stored name
  is rewritten in the same transaction as the catalog write — the TTL column's treatment, applied to the pair.
  A **`drop` stays rejected**: there is nothing to repoint it to.
- **The `update` operation gains one restriction**: `sensitive: true` on the column named by
  `identity_column`/`version_column` is rejected (422). Every other `update` on those columns, and every
  `update` on system/grain/ordering-key columns, stays permitted — this is not a general mutability rule but
  the one attribute that breaks the scan.
- **Repointing a source's `version_column` bumps `generation` and advances `updated_at` on every rule whose
  target enrichment reads that source**, in the same transaction, so the next incremental sync carries the
  rule with its new `version_column`. (`updated_at` too, because the sync feed filters on it — a bumped
  generation the feed does not return would be no signal at all.)

Not in scope: a repoint to a *different* column, or declaring the pair on a source materialized without it.
`POST .../schema` is 409 once `ACTIVE` and `PATCH` carries no `identity_column`/`version_column` member, so
that gap stays open; it is a new PATCH member with its own validation, not a consequence of the rename.

## Capabilities

### New Capabilities

<!-- None. -->

### Modified Capabilities

- `table-management`: the PATCH mutability rules — a scan-metadata column may be renamed (with the catalog
  repoint) but not dropped; a source column named as an enrichment's grain key is immutable; `update` rejects
  marking a scan-metadata column sensitive.
- `enrichment-registry`: `generation` also bumps when the rule's source repoints its `version_column`, so the
  name a runner caches cannot go stale without a signal.
- `enrichment-runtime`: a declared pair's usability invariants hold for the life of the table, not only at
  declaration; and a rename repoints the pair without invalidating any cursor in flight.

## Impact

**APIs.** `PATCH /v1/tables/{name}/schema` accepts a `rename` of a scan-metadata column (previously 422) and
rejects two new things: any `drop`/`rename` of a source column that an `ACTIVE` enrichment is keyed on, and an
`update` setting `sensitive: true` on a scan-metadata column. No new members, no new response fields. The rule
read/sync surfaces are unchanged in shape; a repoint moves `generation` and `version_column` together.

**Code.** `TableSchemaService.requireMutable` splits into a droppable/renameable pair and gains the
enrichment-grain check (it already injects `definitions` and `grains`); the `update` loop gains the sensitive
check; `CatalogSchemaWriter.applyPatch` repoints the two stored names beside the TTL column's line and, when
the version column moved, bumps the affected rules through a small port so the bump shares its transaction
without inverting the `service.enrichment` → `service.table` package dependency.

**Database.** No schema change. One `UPDATE` over the affected rules when a version column is repointed;
`RuleRepository` gains a `findByTargetEnrichmentIn`.

**Callers.** One rejection removed (rename), two added (grain-keyed source column, sensitive on a scan
column). The two additions reject operations that silently broke a table before, so a caller relying on either
was already producing a broken join or an unscannable source.

**Docs.** `openspec/project.md`'s scan-metadata paragraph currently states the pair's columns are frozen
against "a drop or a rename"; it becomes drop-only plus the repoint.
