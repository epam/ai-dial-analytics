## 1. Resolve the life's state

- [x] 1.1 In `AuditSnapshotService`, resolve for a table name and a revision the state of the life containing it: the
  live definition when no `DEL` row follows the revision, otherwise the definition recorded at the first `DEL` after
  it (`store_data_at_delete` keeps the row), or none when nothing is recorded
- [x] 1.2 Resolve the table's own life state for every snapshot, and for an enrichment snapshot its source's life state
  at the same revision; carry both to the mapper with the `TableView` (or an equivalent the mapper already receives)

## 2. Judge visibility by it

- [x] 2.1 Change `SensitiveColumnAccess.visibleColumnsAtRevision` to take the resolved states instead of looking the
  table and the source up by name: a table's current side is its life state; an enrichment's columns are judged by
  its source's life state, and hidden entirely when that state is missing
- [x] 2.2 Keep live reads, read-side flags and `roles` on their current paths; update the `TableMapper` comment and the
  snapshot endpoint's OpenAPI description

## 3. Tests

- [x] 3.1 Integration tests for the delta scenarios: a deleted table's pre-policy revision shows only the allowed
  columns; a recreated table does not judge an earlier life's revisions, and its own revisions are judged by its own
  policies; an enrichment snapshot after its source's deletion lists no column
- [x] 3.2 Existing revision tests still pass unchanged (live table, own-policy side, renamed column, enrichment with
  live source)

## 4. Docs

- [x] 4.1 `docs/row-level-security.md` and `docs/release-0.24-upgrade.md` §11: where they describe revisions hiding a
  column the current policies hide, say a deleted table's revisions are judged by the policies it last had

## 5. Verification

- [x] 5.1 `openspec validate revision-visibility-by-table-life --strict`
- [ ] 5.2 `./gradlew clean build` (by CI on the pull request)
