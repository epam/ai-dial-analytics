## Context

The audit trail is derived from Envers: `AuditRevisionListener` stamps each `revinfo` row (`AuditRevisionEntity`) with the
caller's identity in `newRevision`, and builds one `audit_activity` row per changed entity in `entityChanged`, written through
the revision's `PERSIST` cascade. Nothing else writes activities — `AuditActivityRepository` is read-only by construction.
`parentActivityId` already links `TableColumn` (and bulk-bumped `Pipeline`) activities to a `Table` activity of the same
revision.

`CatalogImportService.applyPlan` is deliberately not `@Transactional`: it calls `TableCatalogService.create`,
`TableSchemaCreationService.defineSchema`, `PipelineService.create` and, on failure, `PipelineService.delete` /
`TableCatalogService.delete`, each committing its own transaction and so its own revision. The `import_id` it generates reaches
only `ImportResult` and the `catalog_import` log line.

The DIAL admin-backend solves the same problem with a parent `Import` activity bound to the importing transaction. That was
considered and rejected for ADAS (see Decisions); what is wanted is a per-activity field the UI can read.

## Goals / Non-Goals

**Goals:**

- Every activity of every revision an import writes carries that import's id; every other activity carries none.
- The feed can be filtered by that id, so one request returns everything an import did.
- The id is the one `ImportResult` already returns, so the import response links straight to its activities.

**Non-Goals:**

- No activity standing for the import itself, no outcome or bundle metadata in the audit trail (the log line keeps those).
- No change to `parentActivityId` semantics or to the "A `Table` activity SHALL have no parent" rule.
- No backfill of past imports; no tagging of other bulk operations.

## Decisions

### D1 — A named `importId` field, not a generic operation id or a parent row

The field is named for what it marks, so `importId != null` alone tells a reader the activity was part of an import.
Rejected: a generic `operationId` (says nothing on its own; would need an `operationType` beside it to be informative, which
pays now for operations that do not exist); a parent `Import` activity as in admin-backend (needs a free-standing activity
write path the read-only repository forbids, a revision with no `*_aud` rows, a new resource type, and a second nesting level
on top of Table → TableColumn that a single `parentActivityId in` filter can no longer collect). A future bulk operation that
needs the same thing gets its own field.

### D2 — Tag the revision, denormalize onto the activity

`revinfo.import_id` records the fact at its true grain — the revision as a whole was written by the import — and
`audit_activity.import_id` copies it, the same way author, email and timestamp are already copied, so the feed stays a
single-table read with a plain scalar filter column. Rejected: only `audit_activity` (cheaper by one column, but leaves the
revision — what snapshot reads key on — unable to say where it came from); only `revinfo` (the feed would have to join, which
the filter layer deliberately never does).

Both columns are `varchar(36)` null, matching `activity_id` / `parent_activity_id`, mapped as `UUID` with
`@JdbcTypeCode(SqlTypes.VARCHAR)`. `audit_activity.import_id` gets an index, since the feed filters on it; `revinfo.import_id`
does not, since nothing queries it yet.

### D3 — A thread-bound scope opened by `applyPlan`, read in `newRevision`

A small `@Component` in `service/audit` (e.g. `AuditImportScope`) holds the current import id in a `ThreadLocal`, exposed as
`open(UUID) → AutoCloseable` and `current() → Optional<UUID>`. `applyPlan` opens it in try-with-resources around the create
loop and `compensate`; `AuditRevisionListener.newRevision` copies `current()` onto the revision, and `record()` copies the
revision's value onto each new activity.

Why this is sound:

- Envers calls `newRevision` from `AuditProcess.doBeforeTransactionCompletion`, at commit, on the committing thread — the
  same invariant the author stamp already relies on and documents. Every step `applyPlan` drives commits on the request thread
  inside the scope, so every revision it causes is tagged, including revisions of any `REQUIRES_NEW` write a called service
  makes on that thread.
- A concurrent request runs on another thread and sees no scope, so a hand-made change during an import is never tagged.
- The listener's Javadoc rejects a `ThreadLocal` because Envers offers no hook to clear per-revision state. That objection does
  not apply: this scope is not per-revision state, and try-with-resources clears it deterministically, so no residue reaches a
  pooled thread. The listener keeps using the revision entity as its per-revision working set.
- `open` refuses to nest (an `IllegalStateException`): imports never nest, and silently shadowing an outer id would mis-tag.

Rejected: binding to a Spring transaction resource as admin-backend does (dies with the first step's transaction); passing the
id down through every service signature (touches table, schema and pipeline services for an audit concern they should not
know about); Hibernate session properties (one session per step, same problem as the transaction).

`applyPlan` changes its `importId` from `UUID.randomUUID().toString()` to a `UUID` it renders into `ImportResult`, so the log
line, the result and the audit trail carry the identical value. The preview path and `CatalogImportValidator`'s rolled-back
pass open no scope; they write no revision anyway.

### D4 — Feed vocabulary

`AuditActivityService.PAGE_MAPPER` gains `"importId", FilterableColumn.uuid("importId")`, inheriting the identifier column's
operator rules (`eq`/`ne`/`in`; ordering and contains refused; `ne` matches null). `AuditActivityDto` gains a trailing
`UUID importId`, omitted when null by the global `NON_NULL` inclusion; the mapper maps it.

## Risks / Trade-offs

- [A future audited write moved off the request thread (`@Async`, a pool) during import would lose its tag silently] → the
  same hazard already documented for the author stamp; the scope's Javadoc names it, and a test asserts every activity of a
  completed and a compensated import is tagged.
- [A scope left open by a bug would tag unrelated revisions on that thread] → try-with-resources only; `open` refuses nesting,
  so a leaked scope fails the next import loudly rather than mis-tagging quietly.
- [Crash mid-import] → activities committed so far carry the id, which is the point: the feed then shows exactly what was left
  behind. A ClickHouse drop with no catalog revision leaves no activity, tagged or not — unchanged from today.
- [Flyway version collision with another branch] → `V46` is the next free version on this branch; re-check the highest
  version on the default branch before merging.

## Migration Plan

`V46__audit_import_id.sql`: `ALTER TABLE revinfo ADD COLUMN import_id varchar(36)`, the same on `audit_activity`, and
`CREATE INDEX audit_activity_import_idx ON audit_activity (import_id)`. Additive and nullable — no rewrite of existing rows,
no default, safe while an older replica is still writing (it simply leaves the column null).

## Open Questions

None.
