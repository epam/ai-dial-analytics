## MODIFIED Requirements

### Requirement: Best-effort compensation

`/import` SHALL apply each object as its own committed step and, on the first failure, SHALL delete what this import created, in
reverse order (pipelines, then enrichments, then sources). Compensation is best effort, not a guarantee.

Before deleting a table, import SHALL check that any pipeline targeting it was created by this import; otherwise it SHALL leave the
table and mark it `rollback_failed`. A crash mid-import, or a ClickHouse drop that succeeds before the catalog delete fails, MAY leave
orphans; re-running the bundle with `SKIP_IF_EXISTS` SHALL recover a missing object, and SHALL refuse a left-behind PENDING or FAILED
table until it is deleted. Each created object SHALL be written in its own audit revisions (table create, schema) under the
importing user, and the import SHALL log one structured `catalog_import` event carrying an `import_id`, the bundle hash and the object
list. Every audit revision the import writes — creates and compensation deletes alike — SHALL be tagged with that same `import_id`,
which the activity feed returns as `importId` (see the `audit-trail` capability), so what an import did, including what a crash
mid-import left behind, is recoverable from the feed and not only from the log.

#### Scenario: DDL failure on the second table

- **WHEN** ClickHouse DDL fails while creating the second table of a bundle
- **THEN** the first table is gone from both the catalog and ClickHouse, the `ImportResult` marks it `rolled_back`, and re-running
  with `SKIP_IF_EXISTS` after the cause is removed creates both

#### Scenario: Import revisions carry the import id

- **WHEN** an import completes and returns `import_id` `X`
- **THEN** every activity recorded for the objects it created carries `importId` `X`
