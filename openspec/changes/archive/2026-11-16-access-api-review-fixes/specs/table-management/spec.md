## MODIFIED Requirements

### Requirement: Column policies follow a rename and refuse an emptying drop

A column policy (see [table-access-control](../table-access-control/spec.md), "Per-role column policies") names
columns, so a schema change SHALL keep every stored policy pointing at the columns it meant and SHALL never widen one.

**Rename.** A `PATCH /v1/tables/{name}/schema` that renames a column SHALL rewrite every entry on the table whose
`columns` lists it to the new name, in the same transaction and the same audit revision as the rename. Renaming a
column of an enrichment SHALL rewrite `<enrichment>.<old>` to `<enrichment>.<new>` in the entries of its source.

**Drop.** A column dropped by `PATCH /v1/tables/{name}/schema`, a column a re-defined schema of a `PENDING` or `FAILED`
table no longer declares (`POST /v1/tables/{name}/schema`), and every column of an enrichment removed by
`DELETE /v1/tables/{enrichment}` SHALL be removed from every `columns` list that names it (as `<enrichment>.<column>`
in the source's entries for an enrichment's column). When removing them would leave any list **empty**, the request
SHALL instead be refused with HTTP 409 and machine code `column_in_column_policy`, naming the columns and the roles
whose lists they would empty, before any DDL is issued or any catalog row changes. A list SHALL never become `null`
through a schema change, because that would show the role every column, and SHALL never become empty, because that
is not a valid policy. Editing or removing those entries first SHALL make the operation possible. A re-defined schema
that omits a column the caller cannot see drops it like any other omitted column, as for an unentitled sensitive
column; the rules above still protect every list that names it.

**Order within one `PATCH`.** A `PATCH` that combines operations SHALL rewrite the lists in the request's canonical
order, against the lists as they stood before the request: first every `drop` prunes the dropped column's name, then
every `rename` rewrites `from` to `to` in the pruned lists. A column `add`ed in the same request is never listed by an
existing entry, even when it reuses a dropped column's name, because the drop already pruned that name. The emptying
refusal SHALL be evaluated on the lists after the prune; a rename only rewrites names a list still holds, so it never
refills a list the prune emptied, and such a request is refused.

These guarantees SHALL hold against a concurrent `PUT /v1/tables/{name}/access?role=<role>`: the refusal and the rewrite
SHALL be decided on the entries as they stand when the schema change commits, and a `PUT` committed after a rename or
drop SHALL be validated against the columns as they stand then. No interleaving SHALL leave a list empty or naming a
column the table (or, for a qualified name, the enrichment) no longer has, and none SHALL make the refusal arrive
after the `ALTER`: a schema `PATCH` SHALL hold the definition's row lock (and, for an enrichment, its source's) from
before it reads any policy until its catalog changes commit, so the entries it decides on before issuing the `ALTER`
are the entries it commits against.

These rules SHALL hold whoever the caller is, and in `none` mode too, because a policy over a missing column would
be invalid the moment it is enforced. A rename or drop that rewrites any entry SHALL increment, by one, the access
`generation` of the table whose entries it rewrites — the source's for a column of an enrichment or an enrichment's
delete, never the enrichment's — and SHALL record that generation as the last change of every role whose list it
rewrote, and of no other role (see table-access-control, "Access configuration generation"); one that rewrites none
SHALL leave every generation as it was. The refusal of a column a row policy references (see "A column a row policy
references is protected against drop and rename") SHALL be checked first, so a column referenced by both is refused
`409 column_in_row_policy`. Apart from that, `409 column_in_column_policy` SHALL be checked in no specified order
against the request's other refusals — a schema `PATCH`'s other `422` and `409` checks, a re-define's validation, and
an enrichment delete's `409` for a pipeline that reads it — so a request failing more than one of them may
receive any of their codes; every one of them is decided before any DDL is issued or any catalog row changes.

**Audit.** Every rewrite of a stored list — by a rename, a drop, a re-define or an enrichment delete — SHALL be
recorded in the revision of the operation that caused it, as an `Update` activity of the table **whose entries
changed**: the table itself for its own column, the source for an enrichment's column or an enrichment's delete. That
activity's snapshot carries the rewritten entries. A table whose entries the operation does not change records no
activity for them.

A migration that removes or renames a catalog column outside the API (in raw SQL) SHALL prune or rewrite the
`column_policies` entries that name it in the same migration, so a name left behind never re-attaches to a column
added later under it.

#### Scenario: A rename rewrites the lists that name the column

- **GIVEN** a table at access `generation` 4 on which role `X`'s entry carries `columns` `["a", "b"]`
- **WHEN** a caller holding `TABLE_MODIFY` renames `b` to `c`
- **THEN** `X`'s entry carries `["a", "c"]`, a caller carrying only `X` still sees the renamed column, and the access
  `generation` is 5

#### Scenario: Renaming an enrichment column rewrites the source's lists

- **GIVEN** a source table `usage` created through the API, with an enrichment `deployment_ref`, and an entry on
  `usage` whose `columns` names `deployment_ref.display_name`
- **WHEN** `deployment_ref`'s column `display_name` is renamed to `label`
- **THEN** the entry names `deployment_ref.label`, `usage`'s access `generation` has moved by one, and
  `deployment_ref`'s has not
- **AND** the rename's revision records an `Update` activity for `usage` whose snapshot carries the rewritten
  entry, besides the activities it records for `deployment_ref`

#### Scenario: A drop prunes a list that keeps other columns

- **GIVEN** role `X`'s entry carries `columns` `["a", "b"]`
- **WHEN** a caller holding `TABLE_MODIFY` drops `b`
- **THEN** the drop is applied and `X`'s entry carries `["a"]`

#### Scenario: Dropping a column and adding a new one under its name in one PATCH

- **GIVEN** role `X`'s entry carries `columns` `["a", "c"]`
- **WHEN** a caller holding `TABLE_MODIFY` sends one `PATCH` that drops `c` and adds a new column `c`
- **THEN** the request is applied and `X`'s entry carries `["a"]`, so a caller carrying only `X` does not see the new
  `c`

#### Scenario: Dropping a column and renaming another to its name in one PATCH

- **GIVEN** role `X`'s entry carries `columns` `["a", "b", "c"]`
- **WHEN** a caller holding `TABLE_MODIFY` sends one `PATCH` that drops `c` and renames `b` to `c`
- **THEN** the request is applied and `X`'s entry carries `["a", "c"]`, where `c` is the renamed `b`

#### Scenario: A drop that would empty a list is refused

- **GIVEN** role `X`'s entry carries `columns` `["b"]`
- **WHEN** a caller drops `b`
- **THEN** the request is refused `409 column_in_column_policy` naming `b` and `X`, no `ALTER` is issued, and `X`'s
  entry is unchanged

#### Scenario: Deleting an enrichment that a list depends on is refused

- **GIVEN** an entry on a source whose `columns` names only columns of its enrichment `e`
- **WHEN** a caller deletes `e`
- **THEN** the request is refused `409 column_in_column_policy` and `e` is not dropped
- **AND WHEN** the list also names a column of the source
- **THEN** `e` is deleted and the list keeps only the source's column

#### Scenario: Re-defining a failed table without a listed column

- **GIVEN** a `FAILED` table with columns `a` and `b` and an entry whose `columns` is `["b"]`
- **WHEN** a caller posts the table's schema again without `b`
- **THEN** the request is refused `409 column_in_column_policy`, the stored columns are unchanged, and no `CREATE` is
  issued

#### Scenario: A column referenced by a row policy and a column policy

- **GIVEN** column `project_id`, referenced by role `X`'s row policy and listed alone in role `Y`'s `columns`
- **WHEN** a caller drops `project_id`
- **THEN** the request is refused `409 column_in_row_policy`

#### Scenario: A schema change that touches no list leaves the generation

- **GIVEN** a table at access `generation` 2 whose entries list only `a`
- **WHEN** column `z` is dropped
- **THEN** the access `generation` is still 2

#### Scenario: A drop racing an access PUT never empties a list

- **GIVEN** role `Y` has no entry, and a table has columns `a` and `b`
- **WHEN** a `PUT` storing `columns` `["b"]` for `Y` and a `PATCH` dropping `b` run concurrently
- **THEN** either the `PUT` commits first and the drop is refused `409 column_in_column_policy`, or the drop commits
  first and the `PUT` is refused `422 validation_error` naming `b`; `Y` never holds `[]` or a list naming `b` once `b`
  is gone

## ADDED Requirements

### Requirement: Writes to one table definition wait a bounded time for each other

Every request that writes a table's definition — an access `PUT` or `DELETE` (see
[table-access-control](../table-access-control/spec.md), "Role-keyed access API"), a metadata update
(`PUT /v1/tables/{name}`), a schema definition (`POST /v1/tables/{name}/schema`), a schema `PATCH` and a table
`DELETE` — SHALL take turns with the other writes of the same definition, and of its source's where it rewrites the
source's column policies, so none of them writes back state another committed meanwhile. A write that finds another
holding the definition SHALL wait for it at most the configured lock timeout (`tables.lock-timeout`, ten seconds by
default). When the wait expires the request SHALL be refused with HTTP 409 and machine code `table_busy`, whose
message says another change to the table is in progress; it SHALL change nothing, so it MAY be retried as sent, and
the write holding the definition SHALL complete unaffected. A write that obtains the definition within the timeout
SHALL be judged against the state the earlier write left. Writes to other tables SHALL NOT wait for it.

The bound SHALL apply to the wait for the definition alone, not to any other statement of the request. A configured
lock timeout below one millisecond, zero and negative values included, SHALL be refused at startup with a message
naming `tables.lock-timeout`, because PostgreSQL reads a zero lock timeout as no limit at all.

A schema definition whose `CREATE TABLE` has run when its final status change is refused `table_busy` SHALL undo that
`CREATE`, as it does when the `CREATE` itself fails: the table is left not materialized, holding the schema it was
sent as a failed definition does, and the same request sent again succeeds.

#### Scenario: A writer waiting past the lock timeout is refused

- **GIVEN** a change to table `t` that holds its definition for longer than the lock timeout
- **WHEN** another request updates `t`'s description
- **THEN** the request answers `409 table_busy`, `t`'s description is unchanged, and the first change commits as it
  would have

#### Scenario: A write that waits less than the timeout proceeds

- **GIVEN** a change to table `t` that holds its definition for less than the lock timeout
- **WHEN** an access `PUT` on `t` waits for it
- **THEN** the `PUT` proceeds once the change commits, and is judged against the entries that change left

#### Scenario: A lock timeout under a millisecond is refused at startup

- **WHEN** the service starts with `TABLES_LOCK_TIMEOUT` set to `0`, `500us` or `-1s`
- **THEN** startup fails with a message naming `tables.lock-timeout`

#### Scenario: A schema definition refused busy after its CREATE is retried as sent

- **GIVEN** a schema definition of `t` whose `CREATE TABLE` succeeded, and another change holding `t`'s definition past
  the lock timeout when the definition is to be marked active
- **WHEN** the definition answers `409 table_busy` and is sent again once the other change has committed
- **THEN** the first attempt dropped the table it created, and the second answers `200` with `t` active
