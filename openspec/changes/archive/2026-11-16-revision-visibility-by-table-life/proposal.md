## Why

A revision snapshot hides a column when either the revision's column policies or the table's current ones hide it.
"Current" is read from the live table by name, which fails in two cases. When the table has been deleted, no current
policies are found and nothing is hidden, so a revision recorded before column policies existed shows every column
to a caller who was restricted. When the name has been reused, an unrelated new table's policies judge the old
table's revisions.

## What Changes

- The "current" side of a revision's column visibility becomes the policies of the table's life that contains the
  revision: the live table while that life has not ended, otherwise the table's state as recorded at the deletion that
  ended it. A table created again under the same name starts a new life and no longer judges an earlier one's
  revisions.
- An enrichment snapshot's columns are judged by its source's life containing the revision, resolved the same way.
  When no recorded state of the source can be found, every column of the enrichment is hidden.
- Live reads, the revision's own-policy side, read-side flags and the `roles` member are unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `table-access-control`: "Hidden columns are absent from every read surface" defines the policies a revision is
  judged by now.
- `audit-trail`: "Historical table snapshots" refers to that definition for tables and enrichments.

## Impact

- `GET /v1/tables/{name}/revision/{revision}`: fewer columns for a column-restricted caller reading a deleted or
  recreated table's revisions, and an enrichment's whose source has been deleted. No shape change.
- Code: the audit snapshot service resolves the life's state from the audit trail and hands it to the mapper; the
  column visibility decision takes it instead of looking the table up by name.
- No catalog schema change, no migration, no configuration key.
