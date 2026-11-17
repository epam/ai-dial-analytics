## Context

See proposal.md for why. `SensitiveColumnAccess.visibleColumnsAtRevision(snapshot)` combines the snapshot's own
visibility with `visibleColumns(snapshot.getName())`, which loads the live table by name and returns every column
when there is none. For an enrichment, both sides go through `GoverningAccess.sourceOf`, which loads the live source,
and return every column when the source is gone. The trail keeps the full row on delete
(`store_data_at_delete: true`), and a snapshot is served only for a table whose creation is in the trail.

## Goals / Non-Goals

**Goals:**
- A column restricted when a table's life ended stays hidden in every revision of that life.
- A later table of the same name has no say over an earlier life's revisions.

**Non-Goals:**
- Read-side flags of an enrichment snapshot. They keep answering from the live source, as the spec states; column
  visibility is the fail-closed part. After a source is deleted and recreated, those flags therefore come from the new
  table while the columns follow the old life. That is a known limit, not a defect of this change.
- The endpoint's own gate. It still checks the caller against the live table name, so a recreated table's grants decide
  who reaches an old life's revisions. That is unchanged and outside this change.
- Mapping renamed columns. A column keeps being matched by the name it has in the revision.

## Decisions

**A life is the span from an `ADD` row at or before the revision to the next `DEL` row after it.** The trail already
answers both with indexed queries on the table's id and revision number. With no `DEL` after the revision, the life is
still running and the live table is the state, as today. With one, the state is the row of that `DEL` revision,
which carries the full definition because of `store_data_at_delete`. Rejected alternative: the newest audit row of the
name. After a recreation that is the new table's, which is exactly the bug.

**The live row is read before the trail is asked for a deletion.** The reads run at read committed, so asking the
trail first and the live table second lets a deletion committed between the two leave no state at all, or the next
life's row after a recreation. Reading the live row first closes both: if it is gone, its deletion has committed and is
visible to the next statement. A `DEL` row after the revision still wins over a live row, because a live row then
belongs to a later life. A missing state is no longer reachable short of an edit outside the application, and it
hides every column rather than skipping the check.

**An ended enrichment life is judged by the source as it stood just before that deletion.** Deleting an enrichment
removes its qualified names from the source's column policies, and a recreated enrichment of the same name can then be
granted columns on the live source. Judging an ended enrichment life by the live source would let those grants reveal
the earlier life's columns. The source's state at the revision before the enrichment's `DEL` row is the last state that
spoke about that life. While the enrichment's life is running, the source's own life containing the revision is used.

**The audit snapshot service resolves the state and hands it over.** It already holds the `AuditReader` and the
revision number, and resolves both the table's life and, for an enrichment, its source's life at the same revision.
It passes them to the mapper with the snapshot, and the visibility decision takes them as arguments. The security
layer never opens the trail itself, and a live read cannot reach that code.

**An enrichment with no resolvable source state hides every column.** Today an enrichment whose source is gone shows
every column, the opposite of failing closed. A source can be deleted only after its enrichments, so an enrichment
snapshot whose source is gone always belongs to a deleted enrichment. Its source's deletion is in the trail whenever
the enrichment's history is, so in practice the fallback applies only to state the trail cannot explain.

## Risks / Trade-offs

- [A caller who could read every column of a deleted table's old revision now sees fewer] → intended: the policies the
  table had when it was deleted are the last word on that data.
- [Two more audit queries per snapshot read] → both are lookups by id and revision; the snapshot already runs several
  of the same kind.
- [The source's life is judged at the enrichment's revision number] → the two are rows of one trail, so the same
  revision number orders them correctly.

## Migration Plan

No catalog schema change, no migration and no configuration key. Rollback restores the previous visibility rule.
