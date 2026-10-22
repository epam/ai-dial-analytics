## MODIFIED Requirements

### Requirement: A schema edit may not repoint what a stored declaration reads

A column any stored declaration reads SHALL NOT be dropped, renamed, or **renamed onto** — on an
enrichment, where a declaration addresses it as `"<enrichment>.<column>"`, and equally on a **source**,
where it is addressed by its bare name. Refused `409 conflict`, naming the declarations.

**The source half is a security control, not only an availability one.** A rename preserves the
`sensitive` flag and changes only the name, so on a source a holder of `TABLE_MODIFY` and
`TABLE_SENSITIVE` — which together confer no `TABLE_READ` — could free the name an expression reads and
move a protected column into it. Without the clearance a sensitive column does not exist for a
`TABLE_MODIFY` holder, so the rename is `422` as an unknown column; with it, the column is reachable. The
declaration is untouched and the flag is untouched, so neither the clearance rule on the flag nor the
subset rule above fires; the pipeline's elevated runner then copies the protected values into an
unflagged enrichment column that plain `TABLE_READ` reads. Before privileges existed this was
unreachable, because `PATCH .../schema` required the full administrative role, which conferred global
sensitive read; letting schema authority over sensitive columns be held without `TABLE_READ` is what
opened it.

**Both halves of a rename SHALL be asked**, because freeing a read name and occupying one are the two
ways to repoint a reference, and only the second carries the values.

A source column counts as read when **any** SQL fragment of the declaration names it as a bare
identifier — the filter, the `ready_when` signal, the member-selection `prefer_sql`, and each `sql`
output expression — as well as through the declaration's structured members. On an `aggregate`
pipeline declared by `sql`, the statement text counts as one such fragment, read by the same token scan
with the leading `INSERT INTO <target> (<columns>)` prefix excluded: those names are target columns, not
reads. When the prefix is not recognised, the whole text is scanned. The statement's bare reads pin the
columns of its input table only once that table has been derived, so a draft whose `sql` cannot be planned
pins no source column; arming re-validates it against the schema as it stands then. A scan for qualified
`table.column` tokens is not enough: a source column has no qualified spelling, so an expression reading
`length(nick)` would be invisible to it, and that is exactly the shape a rename-repoint uses. Every
identifier SHALL count, keywords and string contents included; a false positive refuses a rename of a
column nobody reads, and a false negative is impossible.

#### Scenario: Renaming a source column a pipeline reads is refused

- **GIVEN** an enabled `sql` pipeline whose expression reads the source column `nick`
- **WHEN** a caller holding `TABLE_MODIFY` on that source renames `nick`
- **THEN** the request is refused `409 conflict`, naming the pipeline, and no column changes

#### Scenario: Renaming a sensitive column onto a read name is refused

- **GIVEN** the same pipeline, and a `sensitive` column `user_email` on the same source
- **WHEN** a caller holding `TABLE_MODIFY` and `TABLE_SENSITIVE` on that source renames `user_email`
  to `nick`
- **THEN** the request is refused `409 conflict` — the rename would repoint the expression at a
  protected column without touching the declaration, the flag, or any grant list

#### Scenario: Renaming a source column an SQL-declared aggregate reads bare is refused

- **WHEN** an `aggregate` pipeline is declared by `sql` whose block reads `deployment` as a bare
  identifier only, and a caller renames the input's `deployment` column
- **THEN** the request is refused `409 conflict`, naming the pipeline

#### Scenario: A target column-list name does not block a source rename

- **WHEN** an `aggregate` pipeline's `sql` names `user_id` only in its `INSERT INTO <target> (user_id, …)`
  column list and nowhere in the `SELECT`, and a caller renames the input's `user_id` column
- **THEN** the rename is not refused on that pipeline's account
