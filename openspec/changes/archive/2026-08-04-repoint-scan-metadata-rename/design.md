## Context

`harden-scan-metadata` settled what a declared scan-metadata pair must be *at declaration* and left two
Open Questions: a repoint path, and whether the rename freeze could be relaxed the way the TTL column's
rename is. This change takes the rename half, and pays for the three prerequisites that came out of
tracing what the pair is actually load-bearing for.

Current state, all in `service/table`:

- `TableSchemaService.requireMutable(sourceName, definition, grainKey)` is called from **both** the `drop`
  loop and the `rename` loop, and rejects: system columns, the patched table's own grain key,
  ordering-key members, and either scan-metadata column. `grainKey` is resolved only when the *patched*
  table is an `ENRICHMENT` (`TableSchemaService:82-84`), so a **source** column that some enrichment names
  as its grain key is unguarded.
- The `update` loop deliberately applies no mutability rule (`TableSchemaService:142-144`), and `update`
  carries `sensitive`.
- `CatalogSchemaWriter.applyPatch` already rewrites one stored column name on rename — the TTL column
  (`CatalogSchemaWriter:227-229`) — inside the single catalog transaction that follows the one
  all-or-nothing `ALTER`.

Three facts constrain the design:

1. **The auto-join is by shared physical name.** `CatalogQueryService` builds
   `source.<grain_key> = enrichment.<grain_key>` (`CatalogQueryService:126-129`), and Class-1 requires the
   target's grain key to *be* the source's `identity_column`
   (`RuleGovernanceValidator.requireIdentityGrain`). The enrichment side of that name is that table's
   `ORDER BY`, which ClickHouse refuses to rename — so the pair of names cannot be kept in step.
2. **Nothing else persists either column name.** Caller cursors are HMAC-signed
   `(format, keyId, entity, values…)` with `entity` = the *table* (`CursorCodec:122`); the pipeline's
   server-owned cursor is `cursor_version`/`cursor_identity`, two typed Postgres columns
   (`PipelineEntity:71-78`). Every read path resolves the column names per request via
   `ScanSourceMetadata.cursorColumns`. `RENAME COLUMN` preserves data. So a rename invalidates nothing.
3. **The version column's name is on the wire.** The scan projects it under its own name
   (`ScanService:140`) and publishes it as `RuleDto.version_column` on read and on the sync feed — but
   `generation` moves only inside `RuleService.apply`, i.e. only on a rule mutation.

## Goals / Non-Goals

**Goals:**

- A source's scan-metadata columns can be renamed, with the catalog repointed atomically with the rename.
- No operation reachable after materialization can leave a declared pair unusable by the scan.
- The join's shared name is immutable on both sides, not just on the enrichment's.
- A version-column repoint is visible to a runner that detects change by `generation`.
- Every rejection stays a define-/patch-time 422 that names the dependent, not a runtime failure.

**Non-Goals:**

- **Repointing to a different column**, or declaring the pair on a source materialized without one. That is
  a new `PATCH` member with its own validation (it must re-run `ScanMetadataRules.validateSource` against
  the live schema, and decide what happens to cursors already issued over the old order — a rename cannot
  invalidate a cursor, but a repoint can). See Open Questions.
- Cascading a source rename into stored rule SQL, `input_bindings`, `ready_when.signal`, or pipeline specs.
  Those reference source columns by name and go stale on **any** column rename today; a rename-time
  dependency check over stored SQL is its own change (Open Questions).
- Tuple identity, which is still what a multi-column-grain rollup needs to be scannable.

## Decisions

### D1 — The grain-key guard comes first, and is what makes the identity rename safe

Allowing the identity rename without it would convert an accidentally-covered case into an allowed one:
for a Class-1 rule, `grain_key == identity_column`, so renaming the identity column breaks (a) every query
referencing that enrichment (unknown identifier `source.<old>`), (b) materialize, which projects the grain
key *from the source model* (`MaterializeService:245`, falling back to a raw qualified field), and (c) the
stored rule's own invariant, which registration would now refuse. None of it is repairable: the enrichment
side is its `ORDER BY`.

So the rule is on the *dependency*, not on the pair: **a source column named as any `ACTIVE` enrichment's
`grain_key` is immutable.** That formulation also closes the live hole — today the same breakage is
reachable for any non-Class-1 enrichment grain (a conversation key, say), which the identity freeze never
covered. One check, two problems, and the identity rename becomes "allowed unless something is keyed on
it".

`ACTIVE` only, matching `CatalogQueryService`'s own filter: a `PENDING`/`FAILED` enrichment contributes no
join, and its grain key is re-resolved against the source when it materializes.

*Alternative rejected:* cascade the rename into `enrichment_grain.grain_key` plus the enrichment's
ClickHouse column. ClickHouse rejects renaming a key-expression column, so the cascade cannot complete —
it would fail mid-`ALTER` on the second table, after the source's `ALTER` had already succeeded, i.e. the
one outcome the single-statement design exists to prevent.

*Alternative rejected:* guard only when the grain key is also the `identity_column`. That leaves the
larger hole open and makes the rule about the pair rather than about the join, which is what actually
breaks.

### D2 — `requireMutable` splits into droppable and renameable

Drop and rename no longer share a rule set: a scan-metadata column is renameable but not droppable. Two
methods (`requireDroppable`, `requireRenameable`) over one shared `requireMutable` core (system / grain /
ordering-key / grain-key-of-an-enrichment), with the scan-metadata rejection moving into the drop half
only. Keeping one method with a boolean flag would put the asymmetry inside the method that is supposed to
express it.

### D3 — The repoint is the TTL column's mechanism, unchanged

In `CatalogSchemaWriter.applyPatch`, beside the existing TTL line: if the renamed physical name equals
`definition.getIdentityColumn()` / `getVersionColumn()`, set it to `rename.to()`. Same transaction, same
place, after the single `ALTER` has succeeded. Nothing else is needed, because `renameColumn` copies type,
nullability, sensitivity and ordinal, so the properties `ScanMetadataRules.validateSource` verified at
declaration are preserved by construction — the rename cannot produce a state that define would reject.

Ordering and partition keys need no new handling: an ordering-key member is already rejected, and a
partition-key member is rejected by ClickHouse itself, leaving the catalog untouched (the `ALTER` runs
before any catalog write).

### D4 — `update` gains exactly one restriction, not a mutability rule

`sensitive: true` on a scan-metadata column is rejected; everything else about `update` is untouched,
including `sensitive: false`, and including every attribute on system/grain/ordering-key columns. The
narrow shape is deliberate: `update` is catalog-only metadata and its freedom is a feature. `sensitive` is
singled out because it is the one attribute the scan reads — `ScanService` projects both cursor columns
through `SensitiveProjectionGuard.enforce`, so flipping it turns every non-entitled caller's scan into a
403 — and because it is precisely what `ScanMetadataRules.requireUsable` refuses at declaration. Nullability
is not patchable at all, and type changes are already rejected, so `sensitive` is the whole gap.

The check runs in the `update` resolution loop against the post-op exposed name resolved to its physical
name, so `rename` + `update` in one request is judged on the state the request leaves behind.

### D5 — The generation bump shares the catalog transaction, via a port

The bump must be atomic with the repoint: a bump that lands without the rename (or vice versa) is exactly
the silent staleness being fixed. Both writes are Postgres, so this is free — put it inside
`CatalogSchemaWriter.applyPatch`.

That crosses a package boundary the wrong way (`service.enrichment` → `service.table` today, nine files;
never the reverse), so the writer depends on a small port declared in `service.table` —
`RuleGenerationBump { void onVersionColumnRepointed(String source); }` — implemented by a
`service.enrichment` bean that resolves the source's `ACTIVE` enrichments, loads their rules
(`RuleRepository.findByTargetEnrichmentIn`), and increments `generation` while setting `updated_at`.
Spring wires it; the table package keeps its dependency direction.

**Both fields, not just `generation`:** `RuleService.sync(updatedSince)` selects on
`updated_at >= updatedSince`, so a rule whose generation moved but whose `updated_at` did not would never
be returned by an incremental sync — the bump would be invisible to the very caller it exists for.

*Alternative rejected:* a `@TransactionalEventListener(BEFORE_COMMIT)`. Same transaction, less traceable —
the bump would not appear in `applyPatch`'s call graph, and this is a correctness coupling, not a
notification.

*Alternative rejected:* bump after the commit, in `TableSchemaService`. A failure there leaves the rename
applied and the runners stale, which is the pre-existing bug with extra steps.

*Scope note:* only a `version_column` repoint bumps. The `identity_column` is not on the rule wire and not
projected in a page under its own name, so a runner has nothing cached to invalidate; and a rename of it
is only reachable when no enrichment is keyed on it (D1), which excludes every Class-1 rule.

### D6 — Drops stay rejected

A drop has nothing to repoint to. The caller's path is unchanged: the pair is declared at define time, and
a source that wants to stop being scannable has no endpoint for it (nor a reason to need one — see Open
Questions on the repoint member, which would subsume it).

## Risks / Trade-offs

- **A rename still breaks stored SQL that references the column** (rule `filter_sql`, `ready_when.signal`,
  Class-1 output-var expressions, pipeline `group_by`/`measures`/`filter`) → Unchanged by this change:
  true for any column rename today, and nothing validates stored SQL against a schema change. The version
  column is a plausible referent, so this raises the odds of hitting a pre-existing sharp edge. Mitigation
  is a rename-time dependency scan over stored SQL, which is its own change; noted in Open Questions.
- **A runner that ignores `generation` and holds a configured column name still breaks** → It always did;
  the contract has always been to read `version_column` from the rule. The bump makes the change
  *detectable*, not the runner correct.
- **The grain-key guard forbids something that used to succeed** → It "succeeded" by silently breaking a
  join. A caller that genuinely wants to rename such a column drops the enrichment, renames, and
  re-creates it — the only sequence that was ever consistent.
- **The `update` check is a special case in a deliberately unrestricted operation** → Accepted, and kept to
  one attribute with the reason stated at the call site. The alternative (apply full mutability to
  `update`) would forbid tagging an ordering-key column, which the spec explicitly permits.
- **Two Postgres writes now share the patch transaction** → Bounded: at most one rule per enrichment
  (`UNIQUE` on `target_enrichment`), and only when a version column actually moved.

## Migration Plan

None. No schema change, no data change, no deploy ordering. One rejection removed, two added; both
additions reject operations that previously left a table in a state nothing could repair.

## Open Questions

- **A repoint member on `PATCH .../schema`.** The larger gap this change does not close: an `ACTIVE`
  source cannot change its pair to a *different* column, nor declare one it materialized without
  (`POST .../schema` is 409). It needs `ScanMetadataRules.validateSource` re-run against the live schema
  plus a decision on cursors already issued over the old order — unlike a rename, a repoint changes the
  order itself, so an in-flight cursor's tuple is meaningless against the new pair. Probably it must
  invalidate them (a cursor generation on the definition, or refusing the repoint while any consumer has a
  position).
- **A rename-time dependency check over stored SQL.** Rule filters/signals/expressions and pipeline specs
  all reference source columns by name and are checked only at registration. The same shape as D1's guard,
  applied to parsed SQL rather than to a stored key — considerably more work, and it would also want to
  cover `drop`.
- **Whether `identity_column` should be on the rule wire** alongside `version_column`. It is not today, so
  D5 has nothing to notify; if it ever is, the bump condition widens to both members.
