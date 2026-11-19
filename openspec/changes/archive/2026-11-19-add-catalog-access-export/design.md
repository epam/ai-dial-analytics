## Context

See proposal.md for why. Today:

- A table's access lives entirely in PostgreSQL, on its `table_definition` row: the audited `access` grant lists, the row- and
  column-policy documents, and the unaudited `access_generation` / `access_role_generations`. `TableAccessService` writes entries, and
  `ColumnPolicyRewriter` rewrites column policies (moving the generation) on a schema change. `put` runs in the order the spec pins:
  1. lock the row;
  2. refuse read-side state on an enrichment (`requireNoReadSideOnEnrichment`);
  3. parse the client generation;
  4. run `validated` (the role, the system-table refusal, privileges, row and column policies), holding any refusal it raises;
  5. skip an unchanged entry;
  6. check the generation for a conflict;
  7. throw a held refusal, otherwise write and bump.

  A body refusal is therefore reported only after the conflict check. Any refactor around `entryFor` must keep that.
- Import (`CatalogImportService`) is step-committed, not one transaction: ClickHouse DDL cannot join a PostgreSQL transaction. It
  creates sources, enrichments and pipelines in order under an `AuditImportScope`, and on the first failure it compensates in reverse.
  `CatalogImportValidator` runs the real create services inside one always-rolled-back transaction with DDL replaced by a no-op.
- System tables are seeded by migrations, so two ADAS builds of the same version have the same set. OTLP landing tables are ordinary
  non-system tables and stay excluded from every export.

## Goals / Non-Goals

**Goals:**

- One export/import carries every role's privileges, row policies and column policies for the tables it covers, system tables
  included, and stores on the target only what a `PUT .../access` could have stored.
- With both flags off, export and import behave as today, except that exports write `format_version` `4` and import responses carry
  an empty `access` list.

**Non-Goals:**

- Mapping role names between identity providers. Roles travel verbatim, and a rename is a bundle edit.
- Removing roles on the target (mirror mode). Removal stays `DELETE /v1/tables/{name}/access?role=`.
- Access of OTLP landing tables, instance settings, and identity-provider `roles-mapping`. The last is deployment configuration, not
  catalog state.
- Detecting environment-specific literals inside row policies (as `env_specific` does for models). The import preview shows every
  `next` entry for review.
- Carrying left-behind state in the bundle, or echoing it in the import preview. Only the export preview reports it.

## Decisions

### D1. Opt in on both sides, with the same flag name

Export takes `include_access` in the body and import takes `include_access` as a query parameter, both defaulting to `false`.

*Rejected: apply whatever access the bundle carries.* An importer without `TABLE_GRANT` could then never import a bundle that carries
access, and one bundle could not serve both a target that wants the access and one that does not. Asking at import time also makes
writing onto existing system tables an explicit act.

### D2. One top-level `access` member, keyed by table and then by role

`access: { "<table>": { "<role>": { privileges, rows, columns } } }`, with each entry in the shape `GET .../access` already returns.

*Rejected: embed access in `BundleTable` and turn `required_system_tables` into objects.* That changes the shape of two existing
members, and system tables would still need a second home. One new member leaves a bundle without access identical to today's, and
it maps directly onto the access API, so an operator can read it and hand-edit it.

### D3. Per-role upsert, roles verbatim, onto every covered table

These are the maintainers' choices. A bundle entry replaces that role's entry. Roles the bundle does not name are kept. The upsert
lands on created tables, on skipped existing tables and on system tables alike, so re-running a bundle converges.

*Rejected: mirror the source.* It can revoke access the target set up itself.
*Rejected: fill missing roles only.* A changed policy would never be promoted.

### D4. System-table coverage follows the existing visibility rules

When exporting everything, a system table is a candidate if the caller may `get` it. Under a selection, a system table is a candidate
when it is named or the closure reaches it, for example as the input of a selected pipeline. A candidate is covered when it keeps at
least one entry after the reduction (D5). With access on, a system table in a bundle therefore carries whatever portable access it has,
which is what lets a bundle bring a pipeline together with the access
its readers need. An explicit selection
may name a system table, which today is a `422`. Its closure is itself plus the enrichments its kept column policies name (D6): a system
table is otherwise never expanded, so a selected system enrichment does not pull in its source. Every
covered, named or reached system table joins `required_system_tables`, so the import's existing check (it exists, is system-flagged and is ACTIVE) guards
it with no new code path.

*Rejected: require every system table.* A system table with no entry contributes nothing under upsert, and requiring it only adds a way
for an import to fail.

### D5. Only portable state travels, and what stays behind is shown

Export reduces each entry to what a `PUT` accepts now:
- An enrichment keeps only `write`/`modify`.
- A `*` entry, and every entry of a system table, drops `write`/`modify`.
- Every remaining entry then runs through the `PUT` body check on the exporting ADAS. A role stored by an earlier build that is now a
  bundle or privilege name is therefore left behind whole, instead of failing every import of the bundle.
- An entry whose column policy names an enrichment the export lists under `skipped` then has what remains of it left behind (D6).
- An entry that ends up empty is omitted.

The export preview's `left_behind` gives each dropped part in entry shape, with the `error` a `PUT` of it would answer.

*Rejected: export entries as stored.* The import's `PUT` rules would refuse them, so a bundle from a table carrying leftover state
could never be imported.
*Rejected: refuse the export.* Inert state from an earlier build would then block every promotion.

The cost: a leftover row policy on an enrichment, which still narrows reads on the source environment, does not travel. The preview
makes this visible.

### D6. A column policy pulls in the enrichment it names

A column policy may name an enrichment's column as `<enrichment>.<column>`, and that enrichment's source is always the entry's table.
With access on, the export treats such a name as one more closure edge, exactly like the `"<enrichment>.<column>"` tokens the closure
already follows in pipeline filters, measures and SQL. The enrichment then brings its own closure (its pipeline, and that pipeline's
inputs) through the same transitive walk. Every enrichment a carried entry names is therefore in the bundle or required, so a
fresh bundle meets no missing enrichment on a target that has the same system tables and none of the bundle's tables.

Only entries the reduction keeps count. The first three steps of D5 depend only on the entry and the exporting ADAS's catalog,
so the walk applies them as it visits a table, system-table candidates included, and an entry they leave behind pulls nothing in. The
`dependency_skipped` step is the one that needs the closure, and it runs after the walk. The edge pulls the enrichment in without
making the table need it, so a skipped enrichment drops only the entry, never the table. Needs therefore never depend on entries, and
there is no fixpoint.

A pulled-in enrichment meets the closure's existing rules and needs no new one: it is gettable whenever its source is; under a selection
a PENDING or FAILED one is `422 catalog_export_invalid`, and one with a column the caller may not see is refused `403`, as for any table
the closure reaches. A system enrichment such as `dial_usage_log_payload` joins `required_system_tables` like every system table the
closure reaches, and is never exported.

Apart from that edge, a system table, selected or reached, is never expanded, as `CatalogExportService` already does. A selected
system enrichment therefore does not bring its source. Row policies name only their own table's columns and caller values, so they add
nothing.

*Rejected: let the entry travel and refuse it on import.* An export of a table without the enrichment its column policy names would
then write a bundle that fails on every target lacking that enrichment.
*Rejected: leave such an entry behind.* The role would silently lose its grant on the target, and the import would still succeed.

### D7. Reuse the PUT's validation and write path in `TableAccessService`

Split `put` along its existing seams, keeping its pinned order:

- A public `entryFor(definition, role, body)` runs `requireNoReadSideOnEnrichment` and then `validated`, and returns the entry a `PUT`
  would store. Import and export use it. `put` keeps calling the two steps separately, either side of `requireGeneration`, so the
  scenario "The enrichment refusal precedes the generation and the body" still holds.
- An `importEntries(Map<table, Map<role, body>>)` locks the covered tables in the codebase's lock order: enrichments before their
  sources (the order `CatalogSchemaWriter.persistSchema` and `TableCatalogService.delete` use), and by name within each group. That
  keeps it from deadlocking with a schema write or an enrichment delete, and makes two imports lock in the same order. If PostgreSQL
  still aborts the step, the abort is a failed access step: compensation runs and nothing is written. It then:
  - checks each entry with `entryFor`;
  - refuses an entry with no privilege and no policy, so an import never removes one;
  - skips an equal entry;
  - writes the rest and bumps the generation once per changed role, exactly as `put` does, but without the client-generation check.

The import is authoritative per role, so a `PUT` committed between preview and import is overwritten (last writer wins). The bump
then makes that client's next stale `PUT` answer `409 access_conflict`, as with any concurrent writer.

*Rejected: call `put` with the generation read in the plan.* A concurrent change would fail the whole import late, after objects
were created, and the import would have to retry around generations it does not own.

### D8. Validate in the shared pass, apply last in one transaction

The validation pass runs in this order, all inside its rolled-back transaction:
1. Create the tables and pipelines, so a column policy can name a column of a table the same import creates.
2. Check every access entry with `entryFor`. Refusals become entry `FAIL`s carrying the `PUT`'s `error` code.
3. If no access entry is `FAIL`, run the same `importEntries` the apply step runs. A refused pipeline, or a refused table the bundle
   carries no access for, does not block this step. A refused table that carries access does block it, because step 2 `FAIL`s that
   table's entries through the pass's `refused` set. If `importEntries` itself fails here (for example on a lock timeout), every access
   entry is `FAIL` with the failure, and every pipeline reports `not checked`.
4. Arm, unless step 3 failed, in which case every pipeline reports `not checked`.

`armable`, `arm_problems` and `ineffective_grants` therefore describe the target with the bundle's access applied. For example, an
imported row policy that confines the importing operator shows up in the preview as the confinement arm problem, not at the first
real `PATCH enabled=true`. Because step 2 checks every entry before step 3 writes any, one refusal does not hide the others. When
some entry is `FAIL`, arming judges the target without the bundle's access, and `ineffective_grants` stay empty: a partial
application would describe a state the import can never produce.

The preview's cost: from step 3 until the pass rolls back, the pass holds row locks on every covered existing table, including system
tables such as `dial_usage_log`. A concurrent `PUT .../access` or schema write on those tables, or the background enrichment relayout
marking one aligned, waits up to `tables.lock-timeout` (`TableDefinitionLockingImpl`) and then fails; a request answers
`409 table_busy`. A concurrent `/import`'s access step fails in the same way, and
its compensation removes what it created. The runbook therefore says to edit access on, or import access into, the covered tables only
when no preview with access is running. A preview is an operator action, and the pass already holds such locks on the tables it
creates.

The pass checks every entry (step 2) before it writes any (step 3). If step 3 still fails partway, the access may be partly applied,
so every pipeline gets `not checked` instead of being armed against a state the import can never produce.

In apply, once every table and pipeline is committed, one `TransactionTemplate` runs `importEntries` for every covered table. Access is
PostgreSQL-only, so this step, unlike the object steps, can be atomic: either all of it commits or none of it does. A failure is
treated as a failed step and runs the existing compensation over the created objects. Because the transaction rolled back, existing
tables' access is untouched and nothing needs undoing. Running under the open `AuditImportScope` tags each table revision the commit
writes with the import's `import_id`.

*Rejected: commit access per table, interleaved with table creation.* That leaves partial access on existing system tables, which
compensation cannot restore without a snapshot of what it overwrote.

### D9. `TABLE_GRANT` gates both directions

It is the access API's own gate: an entry discloses which provider roles exist and what each may do. On `/export` and
`/export/preview` alike it is checked after `REGISTRY_READ` and before the selection is resolved. The preview's `access` items name
roles too. On import it joins `authorize(plan)`, so it comes before the collision check
and the validation pass. Both checks are skipped when security is not enforced, like every other gate on these endpoints.

### D10. `format_version` `4`, and `REPLACE` in the response enum only

Every export writes `4`, so an older ADAS refuses a new bundle readably (the pattern versions 2 and 3 set). Unreleased builds
may already have written version-3 bundles. The rule that one version names one shape is what makes the refusal reliable, so access
gets its own version rather than being folded into 3. `ImportAction.REPLACE`
appears only in responses, never in a bundle, so it needs no format bump. Access entries use it for an upsert that changes an existing
entry, which is neither `CREATE` nor `SKIP`.

## Risks / Trade-offs

- [Role names differ between identity providers, so a verbatim copy grants a role nobody holds, or the wrong group] → The preview
  lists every entry with `prev`/`next`, and `docs/catalog-promotion.md` tells the operator to review roles and edit the bundle before
  applying.
- [Row-policy literals such as project ids may mean nothing on the target] → Same review step. Policies built on `caller.user_hash` /
  `caller.project` travel safely.
- [A bundle with access is a map of who may read what] → It is produced only with `TABLE_GRANT`, and the runbook says to handle it like
  the access API's output.
- [Upsert never removes, so a role revoked on the source stays on the target] → Stated in the spec and the runbook. Removal is a
  per-role `DELETE`.
- [Leaving an entry behind can widen reads on the target] → This happens with a leftover enrichment row policy, or with a whole
  entry dropped for `dependency_skipped` or a refused role when that role holds a global read. `left_behind` in the export preview
  names each one, and the runbook says it is the only place that does.
- [A `REPLACE` drops whatever the target's entry holds beyond the bundle's, such as a leftover read side or row policy on an
  enrichment, which can widen reads there] → An import writes each entry whole, as a `PUT` does. The preview shows `prev` beside
  `next`, and the runbook says to review every `REPLACE` whose `prev` holds state its `next` drops.
- [Last-writer-wins against a concurrent `PUT`] → The generation bump makes the concurrent writer's next stale `PUT` conflict. A
  promotion is an operator action, not a hot path.

## Migration Plan

- There is no schema migration and no new configuration property.
- Upgrade the target before importing into it: a target that reads only version 3 refuses every bundle a version-4 ADAS writes, with
  `catalog_bundle_version_unsupported`, including bundles that carry no access. Rolling the exporter back restores version-3 bundles.
- The flags default to off, so existing promotion scripts keep their behaviour.
