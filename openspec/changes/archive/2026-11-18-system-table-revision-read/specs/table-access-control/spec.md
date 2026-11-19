## MODIFIED Requirements

### Requirement: Hidden columns are absent from every read surface

A column hidden from the caller SHALL be treated on every user surface exactly as a sensitive column the caller holds
no clearance for: it SHALL be absent from schema discovery, from the catalog read (`GET /v1/tables/{name}` and every
response carrying the table representation), from revision snapshots, and from the schema the SQL, structured-query,
translation, CSV-export and Flight SQL surfaces resolve against, Flight's catalog metadata and `INFORMATION_SCHEMA`
included. A star projection SHALL omit it. Referencing it anywhere in a query — projection, filter, grouping, ordering,
join or function argument — SHALL fail as an unknown field, with the status that surface gives an unknown field, and
a saved query or dashboard widget referencing it SHALL fail the same way when that caller runs it. A schema change
naming it SHALL be refused as naming an unknown column (`422`), while an `add` or `rename.to` colliding with it is
still refused `422` as a name that already exists, as for a hidden sensitive column (see
[table-management](../table-management/spec.md), "Modify table schema via PATCH"). A `ttl.column` SHALL resolve
against the columns visible to the caller only, so naming a hidden column fails as naming an undeclared one (`422`)
and TTL validation never reveals its type or nullability; and while the stored TTL is on a column hidden from the
caller by a column policy, any `ttl` member (set, replace or remove) SHALL be refused `403 column_not_entitled`
naming that column, and the TTL left unchanged — where the column is also sensitive and the caller lacks the
clearance, the response is `403 column_not_entitled` too. The list item's `column_count` SHALL not
count it (see [table-management](../table-management/spec.md), "Read and list table definitions"), and an import
entry's `prev` SHALL omit it (see [catalog-export-import](../catalog-export-import/spec.md), "Import entries"). No
response SHALL mark a column as withheld or carry a placeholder for it; as for an unentitled sensitive column, a
definition's structural fields are returned as stored and a conflict refusal such as `409 column_in_column_policy`
names the columns it concerns.

The same parity covers two surfaces that resolve against the writer's own view. Sessions-source validation (see
[instance-settings](../instance-settings/spec.md), "Sessions-source validation") resolves the bound fields against the
writing caller's flat schema, so a field hidden from that caller by a column policy is reported as absent, exactly as
an unentitled sensitive one is. And a saved query or inline dashboard body that binds a literal to a column a column
policy hides from other readers is stored and returned to them verbatim: that is the disclosure already accepted for a
sensitive column (see [saved-queries](../saved-queries/spec.md), "A sensitive value may be persisted in a saved query
body"), not a new one.

A revision snapshot SHALL hide a column that either the column policies the table had at that revision or the
column policies the table has **now** hide from the caller, so neither a restriction recorded then nor one added since
is bypassed by reading an older revision. A revision recorded before column policies existed is judged by the
policies it has now alone. The table's policies now SHALL be those of the **life** of the table that contains the
revision, a life running from a creation of the table to its next deletion: the live table's while that life has not
ended, and otherwise those of its state as recorded at the deletion that ended it. A table deleted since the revision
therefore keeps judging its revisions by the policies it last had, and a table created again under the same name
begins a new life whose policies SHALL NOT judge an earlier life's revisions. A column SHALL be matched by the name it carries in the revision: a current allowlist that lists only a
renamed column's new name hides it in a revision taken before the rename. An enrichment snapshot SHALL be judged by its
source's policies now. While the enrichment's own life has not ended, those are the policies of the source's life
that contains the revision, resolved the same way: the live source while that life has not ended, otherwise its state
as recorded at the deletion that ended it. Once the enrichment's life has ended, they are the source's policies as
they stood just before that deletion, so a later enrichment of the same name, granted columns on the source, does not
judge an earlier life's revisions. When no recorded state of the table or of its source can be found, every column of
the snapshot SHALL be hidden. A snapshot's `permissions.columns_restricted` SHALL be
judged over the revision's own columns, except on a system table's snapshot, which carries none and reports it `true`
exactly when a column policy, the revision's or the current one, confines the caller (see
[audit-trail](../audit-trail/spec.md), "Historical table snapshots").

#### Scenario: Every query surface omits a hidden column

- **GIVEN** a caller from whom column `c` of table `t` is hidden
- **WHEN** it requests `t`'s schema through discovery, runs `SELECT *` over SQL, a structured query with no explicit
  fields, a CSV export, and a Flight SQL query and `INFORMATION_SCHEMA.COLUMNS` read
- **THEN** none of them carries `c`

#### Scenario: Referencing a hidden column fails as unknown

- **WHEN** the same caller translates or executes a query that filters, groups or orders on `c`
- **THEN** the request fails exactly as for a column `t` does not have

#### Scenario: A saved query over a hidden column fails for that caller

- **GIVEN** a saved query, and a dashboard widget running it, that selects `c`, authored by a caller who sees `c`
- **WHEN** the caller from whom `c` is hidden runs the saved query and the widget
- **THEN** each run fails exactly as for a column `t` does not have, while the author's runs succeed

#### Scenario: The catalog read omits a hidden column

- **WHEN** the same caller reads `GET /v1/tables/t`
- **THEN** `c` is absent from its columns and `permissions.columns_restricted` is `true`

#### Scenario: An older revision hides a column the current policies hide

- **GIVEN** a revision of `t` at which the caller's only role had no column policy, and a later entry for that role
  with `columns` `["k"]`
- **WHEN** the caller requests `t`'s snapshot at that revision
- **THEN** only `k` is listed and `permissions.columns_restricted` is `true`

#### Scenario: A schema change cannot name a hidden column

- **GIVEN** a caller holding `modify` on `t` from whom `c` is hidden
- **WHEN** it patches `c`'s description
- **THEN** the request is refused as naming an unknown column

#### Scenario: A TTL cannot reach a policy-hidden column

- **GIVEN** a table with a `timestamp` column `c` hidden by a column policy from a caller holding `modify` on it
- **WHEN** the caller `PATCH`es `{"ttl":{"column":"c","interval":1,"unit":"day"}}`
- **THEN** the request is refused `422` as naming an undeclared column, and no `ALTER` is issued
- **AND WHEN** the table's stored TTL is on `c` and the same caller sends `{"ttl":{}}`
- **THEN** the response is `403 column_not_entitled` and the TTL is unchanged

#### Scenario: A deleted table's revisions are judged by the policies it last had

- **GIVEN** a revision `R` of table `t` recorded before column policies existed, a later entry for role `X` with
  `columns` `["k"]`, and then the deletion of `t`
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `t`'s snapshot at `R`
- **THEN** it shows column `k` only and `permissions.columns_restricted = true`

#### Scenario: A table created again does not judge an earlier life's revisions

- **GIVEN** a revision `R` of table `t` at which role `X` had no column policy, a later entry for `X` with `columns`
  `["k"]`, the deletion of `t`, and a new table `t` created with no entry
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `t`'s snapshot at `R`
- **THEN** it shows column `k` only
- **AND WHEN** the new `t` gains an entry for `X` with `columns` `["m"]` and the caller requests a snapshot of the new
  `t` recorded after that entry
- **THEN** that snapshot is judged by `["m"]`, and the snapshot at `R` still shows `k` only

#### Scenario: An enrichment snapshot is judged by its deleted source's last policies

- **GIVEN** an enrichment `e` of source `s`, a revision `R` of `e`, an entry on `s` for role `X` with `read` and
  `columns` naming `s`'s columns and none of `e`'s, and then the deletion of `e` and of `s`
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `e`'s snapshot at `R`
- **THEN** it lists no column
- **AND WHEN** `X`'s entry on `s` instead carried `read` with no `columns` before both deletions
- **THEN** the snapshot lists every column of `e` at `R`

#### Scenario: An enrichment created again does not judge an earlier life's revisions

- **GIVEN** a revision `R` of enrichment `e` of a live source `s`, at which role `X`'s entry on `s` carries `read` and
  `columns` `["k"]`; then `e` is deleted, created again with a column `label`, and `X`'s `columns` become
  `["k", "e.label"]`
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `e`'s snapshot at `R`
- **THEN** it does not show `label`

#### Scenario: Each life of a table recreated twice is judged by its own deletion

- **GIVEN** table `t` with a revision `R1` in its first life, which ends with role `X`'s `columns` `["k"]`; a second
  life with a revision `R2`, which ends with `X`'s `columns` `["m"]`; and a third, live life with no entry
- **WHEN** a caller carrying only `X` and holding `TABLE_GET` globally requests `t`'s snapshots at `R1` and at `R2`
- **THEN** the snapshot at `R1` is judged by `["k"]` and the one at `R2` by `["m"]`
