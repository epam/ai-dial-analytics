## RENAMED Requirements

- FROM: `### Requirement: Catalog import writes no grants`
- TO: `### Requirement: Catalog import writes access only on request`

## MODIFIED Requirements

### Requirement: Catalog import writes access only on request

A catalog import SHALL write no access entry on any table unless it is called with `include_access=true`: without it, a created table
starts with none and a `system` table's entries are never touched, as defined by
[catalog-export-import](../catalog-export-import/spec.md) ("Import order and access"). With it, the import upserts each role entry the
bundle carries onto the target table, system tables included, as that capability's "Access import" defines, through the same body
rules as `PUT /v1/tables/{name}/access?role=<role>`, so an import can store only what a `PUT` could. Access is each environment's own
policy unless the operator asks for it to travel, so granting access on an imported table otherwise stays an operator action through
`PUT /v1/tables/{name}/access?role=<role>`, on the table itself for a source and on its source for an enrichment, whose read-side
access is its source's.

#### Scenario: Imported table starts ungranted

- **WHEN** a table that has entries on the source environment is imported without `include_access`, whether or not it was exported
  with it
- **THEN** the target's table has no entry, and the target's `dial_usage_log` entries are unchanged

#### Scenario: Imported access obeys the PUT rules

- **WHEN** a bundle whose `access` gives `*` the `write` privilege on a user table `t` is imported with `include_access=true`
- **THEN** that access entry is `FAIL` with a problem naming `validation_error`, the import is `422` with `error`
  `catalog_import_invalid`, and `t`'s access is unchanged

### Requirement: Access configuration generation

Each table SHALL carry one access `generation`, a non-negative integer covering every role's entry on it. It SHALL be
`0` for a table created after this capability and for every table that existed before it. `GET .../access`,
`GET .../access?role=<role>` and every successful `PUT` response SHALL carry it.

The service SHALL also record, for each role, the generation at which that role's entry last changed: its
**last-change mark**. A change that removes the entry SHALL set the mark too, and the mark SHALL be kept after the entry
is gone, so a client that read the entry before its removal still conflicts. A role whose entry has not changed since
this capability has mark `0`. No endpoint SHALL return the marks.

A `PUT /v1/tables/{name}/access?role=<role>` SHALL carry the `generation` the client last read. A `generation` that is
absent, `null`, not a JSON integer (`"7"`, `7.5`, `7.0`), or outside the range of a signed 64-bit integer SHALL be refused
`422 validation_error`. Otherwise:

1. when the entry the request would store equals the role's stored entry (an absent entry being the empty one), the
   request SHALL answer `200` with that entry and the current generation, SHALL write and record nothing, and SHALL
   leave the generation and the marks as they were, whatever `generation` it carries, so a client may retry a `PUT`
   whose response it lost;
2. otherwise, when the `generation` is greater than the table's, which no client can have read, or less than the
   role's last-change mark, the request SHALL be refused `409 access_conflict` and nothing SHALL change;
3. otherwise the entry SHALL be written, the table's generation incremented by exactly one, and the role's mark set
   to the new generation.

A change to another role's entry after the client's read SHALL NOT make its `PUT` conflict. The `409` message SHALL
tell the caller to read the access again and reapply its change, and SHALL NOT name a generation, neither the current
one nor the one sent, so a client re-reads rather than retrying with a fresher number.

A `DELETE /v1/tables/{name}/access?role=<role>` MAY carry a `generation` query parameter. Without it the removal SHALL
NOT be conditioned on any generation. With it, a value that is not an integer within the signed 64-bit range SHALL be
refused `422 validation_error`; a role with no entry SHALL still answer `404 not_found`; and a value greater than the
table's generation, or less than the role's last-change mark, SHALL be refused `409 access_conflict` with nothing
changed. A successful `DELETE` SHALL increment the generation by exactly one and set the role's mark to the new
generation.

A schema operation that rewrites a stored entry's `columns` (see [table-management](../table-management/spec.md),
"Column policies follow a rename and refuse an emptying drop") SHALL increment, by exactly one, the generation of the
table **whose entries it rewrites**, and SHALL set the mark of every role whose list it changed to the new generation;
a role whose list it left as it was keeps its mark. Such operations are a rename or drop through
`PATCH /v1/tables/{name}/schema`, a re-define through `POST /v1/tables/{name}/schema` that drops a listed column, and
the delete of an enrichment. For an enrichment's column and for an enrichment's delete, those entries are its
**source's** (they name the column as `<enrichment>.<column>`), so the source's generation and marks move and the
enrichment's do not. A catalog import called with `include_access=true` (see
[catalog-export-import](../catalog-export-import/spec.md), "Access import") SHALL move them exactly as a `PUT` of each
entry it imports would, while carrying and checking no client `generation`: by one, setting that role's mark, for each
entry it changes, and not at all for an entry equal to the stored one. Nothing else SHALL change a generation or a mark: a `PUT` that changes no entry, a description
update, a schema operation that rewrites no entry, a row write and a pipeline change leave them as they were, so none
of them makes a concurrent access edit conflict. A refused request SHALL change nothing.

The check and the write SHALL be atomic: of two writes to the same role that read the same generation and would each
change its entry, exactly one SHALL succeed and the other SHALL answer `409 access_conflict`, while two writes to
different roles from the same read SHALL both succeed. Of two concurrent `DELETE`s of one entry without a
`generation`, one answers `204` and the other `404 not_found`.

The generation and the marks SHALL NOT be part of the audited state, of a revision snapshot or of a catalog export.

#### Scenario: A PUT with the current generation succeeds

- **GIVEN** a table at access `generation` 7
- **WHEN** a caller holding `TABLE_GRANT` `PUT`s a changed entry with `generation` 7
- **THEN** the response is `200` with `generation` 8

#### Scenario: A stale PUT is refused and changes nothing

- **GIVEN** table `usage` at access `generation` 42, on which role `viewers`' entry last changed at generation 42
- **WHEN** a caller `PUT`s a different entry for `viewers` with `generation` 41
- **THEN** the response is `409 access_conflict`, its message names no generation (it contains neither `42` nor
  `41`), and a subsequent read returns every entry unchanged at `generation` 42

#### Scenario: A PUT without a usable generation is refused

- **WHEN** a caller `PUT`s an entry whose `generation` is absent, `null`, `"7"`, `7.5`, `7.0` or `9223372036854775808`
- **THEN** the response is `422 validation_error` and nothing is stored

#### Scenario: The generation is checked before the rest of the body

- **WHEN** a caller `PUT`s, for a role whose entry changed after the `generation` it carries, `"columns": []`
- **THEN** the response is `409 access_conflict`, not `422`

#### Scenario: A missing generation is reported before staleness and the body

- **WHEN** a caller `PUT`s an entry with no `generation` and `"columns": []`
- **THEN** the response is `422 validation_error` naming `generation`

#### Scenario: Two writes from the same read

- **GIVEN** two clients that both read a table's access at `generation` 5
- **WHEN** one `PUT`s an entry for role `X` and the other an entry for role `Y`, both with `generation` 5
- **THEN** both receive `200`, and the table is at `generation` 7

#### Scenario: Two writes to one role from the same read

- **GIVEN** two clients that both read a table's access at `generation` 5
- **WHEN** both `PUT` different entries for role `X` with `generation` 5
- **THEN** exactly one receives `200` and the other `409 access_conflict`

#### Scenario: A retried PUT is answered without a write

- **GIVEN** a `PUT` for role `X` carrying `generation` 5 succeeded, moving the table to 6, and role `Y` changed after
  it, moving the table to 7
- **WHEN** the client sends the identical `PUT` again, carrying `generation` 5
- **THEN** the response is `200` with `X`'s entry and `generation` 7, the generation does not move, and no revision is
  recorded
- **AND WHEN** it `PUT`s a different entry for `X` carrying `generation` 5
- **THEN** the response is `409 access_conflict`

#### Scenario: A generation above the table's is refused

- **GIVEN** a table at access `generation` 3
- **WHEN** a caller `PUT`s a changed entry, or `DELETE`s an entry, carrying `generation` 4
- **THEN** the response is `409 access_conflict` and nothing changes

#### Scenario: A DELETE moves the generation

- **GIVEN** a table at access `generation` 3 with an entry for role `X`
- **WHEN** a caller `DELETE`s `X`'s entry
- **THEN** a subsequent `GET .../access` returns `generation` 4, a `PUT` for `X` carrying `generation` 3 answers
  `409 access_conflict`, and a `PUT` for role `Y`, unchanged since, carrying `generation` 3 answers `200`

#### Scenario: A DELETE with a generation is conditional on its own role

- **GIVEN** a table at access `generation` 6, on which role `X`'s entry last changed at generation 5 and role `Y`'s at
  generation 6
- **WHEN** a caller sends `DELETE .../access?role=X&generation=5`
- **THEN** the response is `204`
- **AND WHEN** it sends `DELETE .../access?role=Y&generation=5`
- **THEN** the response is `409 access_conflict` and `Y`'s entry is kept
- **AND WHEN** it sends `DELETE .../access?role=Y&generation=5.0`, or `DELETE .../access?role=nobody&generation=7`
- **THEN** the responses are `422 validation_error` and `404 not_found` respectively

#### Scenario: A DELETE without a generation is unconditional

- **GIVEN** role `Y`'s entry changed after a client read the access
- **WHEN** the client `DELETE`s `Y`'s entry without a `generation`
- **THEN** the response is `204`

#### Scenario: A schema change does not move the generation

- **GIVEN** a table at access `generation` 2 whose entries name no column the change touches
- **WHEN** its description is updated and a column is added
- **THEN** `GET .../access` still returns `generation` 2, and a `PUT` carrying `generation` 2 succeeds

#### Scenario: A rename marks only the roles whose lists it rewrote

- **GIVEN** a table at access `generation` 2 on which role `X`'s entry lists `a` and role `Y`'s does not
- **WHEN** column `a` is renamed
- **THEN** the table is at `generation` 3, a `PUT` changing `X`'s entry carrying `generation` 2 answers
  `409 access_conflict`, and a `PUT` changing `Y`'s entry carrying `generation` 2 answers `200`

#### Scenario: An enrichment column rename moves the source's generation

- **GIVEN** `dial_usage_log` at access `generation` 4, on which role `X`'s entry names `deployment_ref.display_name`,
  and its enrichment `deployment_ref` at access `generation` 1
- **WHEN** `deployment_ref`'s column `display_name` is renamed to `label`
- **THEN** `dial_usage_log` is at `generation` 5 and `deployment_ref` still at `generation` 1

#### Scenario: A catalog import moves the generation as PUTs would

- **GIVEN** a table at access `generation` 3 with entries for roles `X` and `Y`
- **WHEN** a catalog import with `include_access=true` replaces `X`'s entry and carries an entry for `Y` equal to the stored one
- **THEN** the table is at `generation` 4, a `PUT` of a different entry for `X` carrying `generation` 3 answers `409 access_conflict`,
  and a `PUT` of a different entry for `Y` carrying `generation` 3 answers `200`

### Requirement: Caller-effective privilege reporting

The system SHALL report, on every table read surface (`GET /v1/tables` list items and `GET /v1/tables/{name}`), the
calling identity's effective per-table permissions as a `permissions` object with boolean `get`, `read`, `sensitive`,
`write`, `modify`, `grant`, `rows_restricted` and `columns_restricted` fields. There SHALL be no `list` flag: a caller that sees
a table at all may list it, so the flag could only ever read `true`. The reported flags SHALL agree with what per-table
evaluation, row-policy evaluation, column-visibility evaluation and system-table protection would decide for that
caller, **after** implication is applied:

- a `system` table SHALL report `write=false, modify=false` for every caller, including one holding every privilege
  (all modifying operations are refused on system tables), while its read-side flags SHALL be evaluated from the grant
  lists like any other table's;
- an enrichment SHALL report its source's `read` and `sensitive`, a `rows_restricted` that is `true` when the caller
  is confined on the source or by a policy an earlier build left on the enrichment, a `columns_restricted` decided by
  its source's column policies, and a `get` that is its source's or implied by its own `modify`; its `write` and
  `modify` SHALL be evaluated from its own grant lists;
- otherwise each of `get`, `read`, `sensitive`, `write` and `modify` SHALL be `true` if the caller holds the
  corresponding privilege globally, or if one of its raw provider roles appears in a grant list that grants or implies
  it;
- `rows_restricted` SHALL be `true` exactly when row-policy evaluation confines the caller on the table (see
  "Row-policy evaluation by caller role", and the enrichment bullet above), on `system` and non-`system` tables alike;
- `columns_restricted` SHALL be `true` exactly when column-visibility evaluation hides at least one of the table's own
  columns from the caller (see "Column visibility by caller role", row policies of the governing roles included), on
  `system` and non-`system` tables alike; a
  column hidden only by the clearance does not set it, and it is `false` for a caller with no governing role on the
  table, as `rows_restricted` is for a caller that may not read it;
- `grant` SHALL be `true` exactly when the caller holds `TABLE_GRANT`, and so may call the table's access API (see
  "Role-keyed access API"). `TABLE_GRANT` is held only globally, never through an entry, so `grant` SHALL be the
  same on every table the caller sees, `system` tables and enrichments included: both accept entries. An entry
  carrying every privilege SHALL NOT set it. It SHALL agree with the rule that withholds a revision snapshot's
  entries from a caller without `TABLE_GRANT`;
- in `none` security mode `grant` SHALL be `true` on every table, every other flag but `rows_restricted` and
  `columns_restricted` SHALL be `true` for non-`system`
  tables, the read-side flags SHALL be `true` for `system` tables, and `rows_restricted` and `columns_restricted`
  SHALL be `false` (no authorization is enforced).

`sensitive` SHALL report whether the caller holds the clearance on the table, not whether it can read sensitive
values. It counts among the read-side flags. A consumer SHALL derive "reads sensitive values" as `read && sensitive`,
"sees sensitive columns" as `get && sensitive`, and "may change them or the flag" as `modify && sensitive`.
`grant` SHALL NOT change what the caller may do: the access API keeps its own `TABLE_GRANT` check.
`rows_restricted` and `columns_restricted` SHALL NOT disclose the policies themselves; those are readable only where `TABLE_GRANT`
is required: through the access API, a revision snapshot (see [audit-trail](../audit-trail/spec.md)), and a catalog export or import
called with `include_access` (see [catalog-export-import](../catalog-export-import/spec.md), "Access export" and "Access import").

The reporting SHALL share its role-matching, implication, row-policy and column-visibility evaluation with the
evaluation used for enforcement so the two cannot diverge. Reporting SHALL NOT change enforcement behaviour.

#### Scenario: Matching write grant is reported

- **GIVEN** a table on which provider role `analytics-writer`'s entry carries `write` only
- **WHEN** a caller carrying provider role `analytics-writer` and no global privilege reads the table via
  `GET /v1/tables/{name}`
- **THEN** the table carries `permissions.write = true` and `permissions.modify = false`

#### Scenario: Implication is reflected in the reported flags

- **GIVEN** a table on which provider role `analytics-viewers`' entry carries `read` only
- **WHEN** a caller carrying that role reads the table
- **THEN** the table carries `permissions.read = true` and `permissions.get = true`, and `permissions.sensitive =
  false`

#### Scenario: The clearance is reported on its own flag

- **GIVEN** a table on which provider role `finance-owners`' entry carries `modify` and `sensitive`
- **WHEN** a caller carrying that role and no global privilege reads the table
- **THEN** the table carries `permissions.get = true`, `permissions.modify = true`, `permissions.sensitive = true` and
  `permissions.read = false`

#### Scenario: A full-privilege caller sees full permissions on a non-system table

- **WHEN** a caller holding the `FULL_ADMIN` bundle, none of whose roles has an entry on the table, reads a
  non-`system` table with no entries
- **THEN** every flag in `permissions` except `rows_restricted` and `columns_restricted` is `true`, and those two are
  `false`

#### Scenario: No matching grant is reported as no permissions

- **WHEN** a caller with no global privilege, none of whose provider roles has an entry on a table, reads that table
  through a surface it can reach
- **THEN** every flag in `permissions` is `false`, `columns_restricted` included even where another role's entry on
  the table carries `columns`

#### Scenario: A system table reports no write permissions but honours read grants

- **WHEN** a caller holding the `FULL_ADMIN` bundle reads a `system` table (e.g. the seeded `dial_usage_log`)
- **THEN** the table carries `permissions.write = false` and `permissions.modify = false`, and `permissions.read =
  true`

#### Scenario: A confined caller is reported as row-restricted

- **GIVEN** an entry for role `X` on a table whose `rows` admits one project
- **WHEN** a caller carrying `X` and holding `TABLE_READ` reads the table's definition
- **THEN** the table carries `permissions.read = true` and `permissions.rows_restricted = true`
- **AND WHEN** the caller also carries another role that may read the table and has no row policy on it
- **THEN** the table carries `permissions.rows_restricted = false`

#### Scenario: A column-restricted caller is reported

- **GIVEN** an entry for role `X` with `read` and `columns` naming some but not all of the table's columns
- **WHEN** a caller carrying only `X` reads the table's definition
- **THEN** the table carries `permissions.columns_restricted = true`
- **AND WHEN** the list names every column of the table
- **THEN** the table carries `permissions.columns_restricted = false`

#### Scenario: An enrichment reports its source's read-side flags

- **GIVEN** a source on which role `X`'s entry carries `read` and a row policy, and an enrichment of it on which `X`'s
  entry carries `write`
- **WHEN** a caller carrying `X` and no global privilege reads the enrichment's definition
- **THEN** the enrichment carries `permissions.read = true`, `permissions.rows_restricted = true`, `permissions.write
  = true` and `permissions.modify = false`

#### Scenario: An enrichment reports its source's column restriction

- **GIVEN** a source on which role `X`'s entry carries `read` and `columns` naming the source's columns and none of
  enrichment `e`'s
- **WHEN** a caller carrying only `X` reads `GET /v1/tables/e`
- **THEN** `e` carries `permissions.columns_restricted = true` and lists no column
- **AND WHEN** `X`'s `columns` names every column of `e` as `e.<column>`
- **THEN** `e` carries `permissions.columns_restricted = false`

#### Scenario: A policy left on an enrichment is reported as row-restricted

- **GIVEN** a source on which role `X`'s entry carries `read` and no row policy, and an enrichment of it that still
  stores a row policy for `X`, written by an earlier build
- **WHEN** a caller carrying only `X` and no global privilege reads the enrichment's definition
- **THEN** the enrichment carries `permissions.read = true` and `permissions.rows_restricted = true`

#### Scenario: An enrichment whose source is missing grants no read

- **GIVEN** a revision snapshot of an enrichment whose source table no longer exists
- **WHEN** a caller holding `TABLE_GET` globally and no other global privilege reads it
- **THEN** the read answers `200`, not an error, and the snapshot carries `permissions.get = true`, `permissions.read
  = false` and `permissions.sensitive = false`

#### Scenario: none mode reports full permissions

- **WHEN** the service runs in `none` security mode and any caller reads a non-`system` table
- **THEN** every flag in `permissions` except `rows_restricted` and `columns_restricted` is `true`, and those two are
  `false`

#### Scenario: The grant flag follows TABLE_GRANT

- **WHEN** a caller holding `TABLE_GET` and `TABLE_GRANT` globally, and no other privilege, reads a table
- **THEN** the table carries `permissions.grant = true` and `permissions.read = false`
- **AND WHEN** a caller holding only `TABLE_GET` globally reads it
- **THEN** the table carries `permissions.grant = false`

#### Scenario: An entry does not confer the grant flag

- **GIVEN** a table on which role `X`'s entry carries `get`, `read`, `sensitive`, `write` and `modify`
- **WHEN** a caller carrying `X` and no global privilege reads the table
- **THEN** the table carries `permissions.modify = true` and `permissions.grant = false`

#### Scenario: The grant flag is reported on system tables and enrichments

- **WHEN** a caller holding the `FULL_ADMIN` bundle reads a `system` table and an enrichment
- **THEN** both carry `permissions.grant = true`, the `system` table with `permissions.write = false`
