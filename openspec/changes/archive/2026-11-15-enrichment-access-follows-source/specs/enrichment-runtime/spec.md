## ADDED Requirements

### Requirement: An enrichment's clearance is not compared with the tables its pipeline reads

Who may read an enrichment and its sensitive columns SHALL be decided by its source's grant lists alone (see
`table-access-control`, "An enrichment's read-side access is its source's"). Registration, enable and armed patches of
an `enrich` pipeline, grant edits on the source and schema edits that mark a column `sensitive` SHALL NOT compare
those lists with the grant lists of any table the pipeline reads. The pipeline's author SHALL still be entitled to
every `sensitive` column it reads (see `enrichment-registry`, "Sensitive-column entitlement").

#### Scenario: A source may clear a role its enrichment's pipeline input does not

- **GIVEN** an enabled `enrich` pipeline that reads table `T` and writes an enrichment, carrying a `sensitive` column,
  of a different source `S`, and a `T` whose `sensitive` list does not name `analysts`
- **WHEN** an operator grants `sensitive` on `S` to `analysts`
- **THEN** the request succeeds
- **AND WHEN** a caller entitled to the sensitive columns the pipeline reads disables it and enables it again
- **THEN** the enable succeeds

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
declaration is untouched and the flag is untouched, so the clearance rule on the flag does not fire; the pipeline's elevated runner then copies the protected values into an
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

## REMOVED Requirements

### Requirement: A grant may not launder a restricted column into a laxer target

**Reason**: A derived table's audience is decided by its own grants (for an enrichment, its source's) and by whoever authors the pipeline, who must already be entitled to the sensitive columns it reads. That a role may read a derived table without being cleared on the table it derives from is intended: one table can hold raw data and another a corrected or reduced form of it, with different readers. Flagging both columns `sensitive` protects each with its own table's clearance.

**Migration**: None. `PUT /v1/tables/{name}/access` and both schema endpoints no longer answer `422 sensitive_grant_widens`; pipeline registration, enable and aggregate refresh no longer answer `422 pipeline_validation_failed` for a role a target grants more widely than its input; and grant edits, schema edits that mark a column sensitive, and `enrich` registration, enable and armed patch no longer answer `409 pipeline_declaration_unresolvable`. Clients that handled those refusals there can drop the handling.
