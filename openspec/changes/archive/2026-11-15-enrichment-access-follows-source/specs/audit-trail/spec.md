## ADDED Requirements

### Requirement: The enrichment access clean-up records no revision

In addition to the limitations stated under "Audited resources", the catalog migration that removes the `get`, `read`
and `sensitive` lists and the row policies stored on enrichments (see `table-access-control`, "An enrichment's
read-side access is its source's") records **no revision**, and this SHALL be stated as a known limitation. It rewrites the rows in raw SQL, outside
the persistence context, so an enrichment's latest revision snapshot keeps showing the removed lists and policies until
the next change that rewrites its table definition (its description, grants or row policies, not a column-only
schema change) records a new one. The history of earlier revisions SHALL be left as it was,
because it records what was stored then. A snapshot carrying such lists does not describe access that was in force
after the upgrade: from then on an enrichment's read-side access is its source's whatever its stored lists say. The
next such change of an enrichment, whatever it is for, records the emptied lists, so its diff against the previous
revision shows them removed under that change's author.

#### Scenario: The snapshot lags the clean-up

- **GIVEN** an enrichment whose stored `read` list named a role before the upgrade
- **WHEN** a caller holding `TABLE_GRANT`, and `TABLE_GET` on the enrichment, reads the enrichment's latest revision snapshot after the upgrade, before any
  later change of the table
- **THEN** the snapshot still carries that `read` list, while `GET /v1/tables/{name}/access` returns it empty
- **AND WHEN** the enrichment's description is then updated
- **THEN** the new revision's snapshot carries empty `get`, `read` and `sensitive` lists and no row policies

## MODIFIED Requirements

### Requirement: Historical table snapshots

The system SHALL expose `GET /v1/tables/{name}/revision/{revision}` returning the table as it stood
at the end of the given revision, in the same response shape as the current-state read
(`GET /v1/tables/{name}`) plus the two grant members below. The snapshot SHALL include the columns, ordering key, and grain that
belonged to the table at that revision, and SHALL exclude a column that had been dropped by then.

Every member of that response describes the table **as of the requested revision**, including the
caller's effective permissions, which are resolved from the access lists the table had then. An enrichment is the
one exception: its read-side access is its source's (see `table-access-control`), and the source's lists at that
revision are not part of the enrichment's history, so an enrichment snapshot's `read` and `sensitive` SHALL be
resolved from its source's current state, its `rows_restricted` from the source's current state or a policy the
enrichment had at that revision, its `write` and `modify` from the lists the
enrichment had then, and its `get` from either: the source's current state, or the `modify` list the enrichment had
then. For an enrichment whose source no longer exists, the source's lists SHALL be taken as empty, so only a global
privilege — or, for `get`, the enrichment's `modify` list at that revision — sets a read-side flag. A snapshot
therefore answers "what could this caller have done then", not "what may they do now", and SHALL NOT be
used to decide whether an action is currently permitted — the live read answers that.

The snapshot SHALL additionally carry the table's grants as of the revision — `access`, in the shape of
`GET /v1/tables/{name}/access`, and `row_policies`, in the shape of the `policies` member of
`GET /v1/tables/{name}/row-policies` — to a caller holding `TABLE_GRANT`, and in `none` security mode.
That is exactly what reading them live requires, so a past grant is never more visible than the present
one. For every other caller both members SHALL be absent, not empty: an empty list or map would assert
that the table had no grants. When served, both are always present, an empty `row_policies` being `{}`
and each `access` list `[]`, including at a revision recorded before row policies existed. The live
read, the table list and every mutation response SHALL NOT carry either member, so in `none` mode the
snapshot is a superset of the live shape.

Because both members are read from the recorded state, an access or row-policy revision recorded before
they were served becomes readable too. A write that changes no role set and no policy — an access save
that only reorders roles, an identical row-policy re-save — SHALL record no revision; such revisions
recorded before this rule remain in the trail unchanged.

A request for a revision at which the table did not exist — before its creation or at or after its
deletion — SHALL be answered with HTTP 404 and the error code `revision_not_found`, as SHALL a
revision identifier that does not exist at all, including one beyond the newest revision. A revision
number is not evidence that a state existed at it.

A snapshot SHALL be returned only for a table whose **creation** is itself recorded in the trail. A
table that already existed when tracking began SHALL be answered with HTTP 404 at every revision, for
the same reason: its columns acquire history only as they are individually changed, so any state
reconstructed for it would omit every column nobody has touched since. Answering "no tracked state"
is correct where answering with a partial one would assert that the missing columns were absent.

#### Scenario: Table snapshot at a past revision

- **WHEN** a table's description is changed and a caller requests the table's snapshot at the
  revision preceding that change
- **THEN** the previous description is returned in the same shape as the current-state table read

#### Scenario: Grant members are served to a TABLE_GRANT holder

- **WHEN** a caller holding `TABLE_GRANT` and `TABLE_GET` requests a table snapshot in `oidc` mode
- **THEN** the snapshot carries `access` and `row_policies` as they stood at that revision, each present
  even when empty

#### Scenario: Grant members are omitted without TABLE_GRANT

- **WHEN** a caller holding `TABLE_GET` but not `TABLE_GRANT` — such as the `READ_ONLY_ADMIN` bundle —
  requests the snapshot at an access or row-policy change in `oidc` mode
- **THEN** the snapshot carries neither `access` nor `row_policies`, and its other members are those it
  would carry without this requirement

#### Scenario: Grant members are served in none mode

- **WHEN** any caller requests a table snapshot in `none` security mode
- **THEN** the snapshot carries `access` and `row_policies`

#### Scenario: The live read carries no grant members

- **WHEN** any caller, in either security mode, reads `GET /v1/tables/{name}` or lists tables
- **THEN** neither `access` nor `row_policies` is present in the response

#### Scenario: Snapshot reflects the columns of that revision

- **WHEN** a column is dropped from a table and a caller requests the snapshot at the revision
  preceding the drop
- **THEN** the dropped column is present in the returned schema

#### Scenario: Snapshot excludes a column dropped earlier

- **WHEN** a caller requests a table snapshot at a revision after a column was dropped
- **THEN** the dropped column is absent from the returned schema

#### Scenario: Snapshot before the table existed

- **WHEN** a caller requests a table's snapshot at a revision earlier than its creation
- **THEN** the system returns HTTP 404 with error code `revision_not_found`

#### Scenario: Snapshot at a revision that never happened

- **WHEN** a caller requests a snapshot at a revision number beyond the newest revision
- **THEN** the system returns HTTP 404 with error code `revision_not_found`, rather than the table's
  current state

#### Scenario: Snapshot of a table older than the trail

- **WHEN** a caller requests a snapshot of a table that existed before change tracking began, at a
  revision in which only its definition row was changed
- **THEN** the system returns HTTP 404 with error code `revision_not_found`, rather than a state whose
  column list is empty because those columns have no recorded history

#### Scenario: An enrichment snapshot reports its source's current read-side flags

- **GIVEN** an enrichment snapshot recorded when the enrichment's own `read` list named the caller's role, and a source
  that does not grant that role now
- **WHEN** the caller, holding `TABLE_GET` globally, requests the snapshot
- **THEN** the snapshot's `permissions.read` is `false`, and its `permissions.write` reflects the enrichment's `write`
  list at that revision
