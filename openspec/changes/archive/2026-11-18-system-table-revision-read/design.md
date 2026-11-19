## Context

`AuditSnapshotService.snapshot` runs three guards before it builds a `TableSnapshot`:
1. `requireExistingRevision`.
2. `requireCreationWithinTheTrail`, which needs an Envers `ADD` row at or before the revision.
3. `reader.find(...) != null`.

System tables are seeded by migrations, so guard 2 always fails for them. `TableMapper.toDto(TableSnapshot, …)` then
builds the DTO from `view.columns()`, filtered by `SensitiveColumnAccess.visibleColumnsAtRevision`. The permissions
come from `TableCallerPermissions.resolve(entity, visibility, ownColumns)`, whose `columns_restricted` is
`hides(visibility, ownColumns)`.

Every row of `table_definition_aud` carries `system`. The flag column (V4) predates the audit tables (V27), so the flag
on the revision row is reliable. System tables are never deleted, so a system table's life is always its live row.

## Goals / Non-Goals

**Goals:**
- Make the access history of a system table readable through the existing revision read, with no new endpoint and no
  backfill.

**Non-Goals:**
- Reconstructing a system table's columns at a revision.
- Changing the guard for non-system tables that predate the trail, or for pipelines and saved queries.
- Recording the definition changes that migrations make.

## Decisions

**1. Branch on the revision row's `system` flag, not the live row's.** Load `reader.find` first. When the revision row
is `system`, skip the creation guard; otherwise run the guard exactly as today. The revision row is already loaded and
says what the table was at that revision. The live row would answer the same for every table that exists, because no
table changes its `system` flag through the API. For a user table the outcomes are unchanged: a 404 is still a 404.
Only the message of the "did not exist" case can differ, and the message is not part of the contract.

**2. Omit `columns` rather than serve the current schema or an empty list.** `TableView` gets `null` columns for a
system snapshot, and the mapper maps `null` to `null`. `TableDto` is `NON_NULL`, so the member disappears.
- *Alternative: the live columns.* Rejected. Migrations change system-table columns outside the trail, such as
  sensitivity seeds and payload column moves, so the live schema is not the schema at the revision. Serving it would
  be the "partial state asserted as complete" that the guard exists to prevent.
- *Alternative: an explicit `columns_tracked: false` member.* Rejected as a new contract member with one value. A
  client already has `system: true` beside an absent `columns`.

**3. `columns_restricted` without a column list is `!visibility.isAll()`.** In `TableCallerPermissions.hides`, a
`null` `ownColumns` means "untracked". The result is then whether any column policy confines the caller.
- It fails closed: a policy that happens to list every column would report `true` where the exact answer is `false`.
  That over-states a restriction and never hides one.
- *Alternative: judge over the live columns.* Rejected for the reason in Decision 2, and because it costs a lookup.
- *Alternative: `false`.* Rejected, because it would tell a confined caller that nothing is hidden.

**4. Keep `visibleColumnsAtRevision` and the enrichment source path as they are.** `dial_usage_log_payload` is a system
enrichment, so its snapshot still resolves `sourceLife` and the read-side flags from its source. No column list is
needed for that.

**5. No baseline migration.** One could write a synthetic `ADD` revision, with columns, for each system table. It would
land after every revision that already exists, so the revisions clients are asking about would still answer 404. It
would also be an audit entry attributed to no administrative act, which the audit-trail spec rejects for the fold
migration.

## Risks / Trade-offs

- [A client that assumes `columns` is always present breaks on a system-table revision] → The upgrade note
  in `docs/release-0.24-upgrade.md` states the shape. Before this change no client could get a 200 for these tables,
  so nothing that works today stops working.
- [A system-table snapshot shows the definition as of the last audited write, not as changed by a later or earlier
  migration] → This is stated in the requirement. It matches the existing limitations for the fold and the
  enrichment clean-up.
- [`AuditTrailAuthIntegrationTest` probes `dial_usage_log/revision/1` and expects 404] → It stays 404 unless
  revision 1 itself wrote that table's definition. The test is changed to probe a revision that holds no state for it
  (revision `0`), so the authorization assertion no longer depends on test order.

## Migration Plan

Deploy as usual. There is no schema or config change. Rollback restores the 404 for system tables with no data effect.
