## MODIFIED Requirements

### Requirement: Historical table snapshots

The system SHALL expose `GET /v1/tables/{name}/revision/{revision}` returning the table as it stood
at the end of the given revision, in the same response shape as the current-state read
(`GET /v1/tables/{name}`) plus the access member below. The snapshot SHALL include the columns, ordering key, and grain that
belonged to the table at that revision, and SHALL exclude a column that had been dropped by then. A system table's
snapshot is the one exception: it carries no columns, and its definition is the one last recorded at or before the
revision (below).

Every member of that response describes the table **as of the requested revision**, including the
caller's effective permissions, which are resolved from the access entries the table had then. The columns it shows
are the exception that fails closed: a column SHALL be hidden when either the column policies the table had then or
the column policies it has now hide it from the caller, matched by the name the column had at that revision. "Now"
means the life of the table that contains the revision: the live table while that life has not ended, otherwise its
state as recorded at the deletion that ended it, so a later table of the same name never judges it (see
`table-access-control`, "Hidden columns are absent from every read surface"). An
enrichment is the
one exception: its read-side access is its source's (see `table-access-control`), and the source's entries at that
revision are not part of the enrichment's history, so an enrichment snapshot's `read` and `sensitive` SHALL be resolved from its source's current state, which of
its columns it shows from its source's policies now (its source's life containing the revision, resolved as above), its `rows_restricted` from the source's current
state or a policy the enrichment had at that revision, its `write` and `modify` from the grants the
enrichment had then, and its `get` from either: the source's current state, or the `modify` grants the enrichment had
then. For an enrichment whose source no longer exists, the source's entries SHALL be taken as empty, so only a global
privilege — or, for `get`, the enrichment's `modify` grants at that revision — sets a read-side flag. A snapshot
therefore answers "what could this caller have done then", not "what may they do now", and SHALL NOT be
used to decide whether an action is currently permitted — the live read answers that. Column policies are judged
both ways because a column restricted today would otherwise reappear, metadata and all, in every revision recorded
before the restriction, those recorded before column policies existed included; a column renamed since the revision
is hidden when the current allowlist lists only its new name. The snapshot's `permissions.columns_restricted` is judged
over the revision's own columns; a system table's, which has none, is judged as below. A column's `sensitive` flag is
still resolved as of the revision.

The snapshot SHALL additionally carry the table's access entries as of the revision as `roles`, in the shape of the
`roles` member of `GET /v1/tables/{name}/access` — each role's `privileges`, `rows` and `columns` — to a caller holding
`TABLE_GRANT`, and in `none` security mode. That is exactly what reading them live requires, so a past grant is never
more visible than the present one. For every other caller the member SHALL be absent, not empty: an empty object would
assert that the table had no entries. When served, it is always present, an empty one being `{}`, including at a
revision recorded before row or column policies existed, where `rows` and `columns` read `null` (its columns are
still filtered by the column policies the table has now, as above). The snapshot SHALL
NOT carry the access `generation`. The live read, the table list and every mutation response SHALL NOT carry `roles`,
so in `none` mode the snapshot is a superset of the live shape.

Because the member is read from the recorded state, an access revision recorded before it was served becomes readable
too. A write that changes no entry — a re-save of an identical entry — SHALL record no revision; revisions recorded
before this rule that changed nothing remain in the trail unchanged.

A request for a revision at which the table did not exist — before its creation or at or after its
deletion — SHALL be answered with HTTP 404 and the error code `revision_not_found`, as SHALL a
revision identifier that does not exist at all, including one beyond the newest revision. A revision
number is not evidence that a state existed at it.

A snapshot SHALL be returned only for a table whose **creation** is itself recorded in the trail. A
table that already existed when tracking began SHALL be answered with HTTP 404 at every revision, for
the same reason: its columns acquire history only as they are individually changed, so any state
reconstructed for it would omit every column nobody has touched since. Answering "no tracked state"
is correct where answering with a partial one would assert that the missing columns were absent.

A **system** table, one whose definition the revision records as `system`, is exempt from that rule.
It is created by a migration, and its definition update, schema definition, schema patch and
deletion are refused (see `table-management`). So its trail can hold only changes to its definition row, its access
entries above all, and never a change to one of its columns. Its snapshot SHALL be returned at every revision at or after
the first recorded state of its definition, with every member described above except `columns`, which SHALL be
absent, not empty. An empty list would assert that the table had no columns, while a list built from the current
schema would assert a past state the trail never recorded. Its `ordering_key`, partitioning, TTL and grain SHALL be
those last recorded at or before the revision. Its `roles` SHALL be served under the same bar as any table's. Its
`permissions.columns_restricted` SHALL be `true` exactly when a column policy, the revision's or the current one,
confines the caller, because no column list exists to judge it over. A revision before the first recorded state of
its definition SHALL be answered with HTTP 404 and `revision_not_found`, like a revision before any table's creation.
A change a migration makes to a system table's definition is written outside the trail and records no revision. So no
snapshot shows it until the table's next recorded change, whose recorded state carries it, and a comparison across that
recorded change shows the migration's edit as part of it.

#### Scenario: Table snapshot at a past revision

- **WHEN** a table's description is changed and a caller requests the table's snapshot at the
  revision preceding that change
- **THEN** the previous description is returned in the same shape as the current-state table read

#### Scenario: Grant members are served to a TABLE_GRANT holder

- **WHEN** a caller holding `TABLE_GRANT` and `TABLE_GET` requests a table snapshot in `oidc` mode
- **THEN** the snapshot carries `roles` as it stood at that revision, present even when empty, and no `generation`

#### Scenario: Grant members are omitted without TABLE_GRANT

- **WHEN** a caller holding `TABLE_GET` but not `TABLE_GRANT` — such as the `READ_ONLY_ADMIN` bundle —
  requests the snapshot at an access change in `oidc` mode
- **THEN** the snapshot carries no `roles`, and its other members are those it would carry without this requirement

#### Scenario: Grant members are served in none mode

- **WHEN** any caller requests a table snapshot in `none` security mode
- **THEN** the snapshot carries `roles`

#### Scenario: The live read carries no grant members

- **WHEN** any caller, in either security mode, reads `GET /v1/tables/{name}` or lists tables
- **THEN** no `roles` member is present in the response

#### Scenario: A revision before column policies existed

- **WHEN** a caller holding `TABLE_GRANT` requests the snapshot at a revision recorded before column policies existed,
  at which role `X` held `read` and a row policy
- **THEN** `roles.X` carries `["read"]`, that `rows`, and `columns` `null`

#### Scenario: A revision before column policies existed is filtered by the current ones

- **GIVEN** a revision recorded before column policies existed, and a current entry for the caller's only role with
  `columns` `["k"]`
- **WHEN** the caller requests the snapshot at that revision
- **THEN** it shows column `k` only and `permissions.columns_restricted = true`
- **AND WHEN** the current entry's `columns` is removed
- **THEN** the same snapshot shows every column

#### Scenario: A revision older than a column policy hides the column it now restricts

- **GIVEN** a revision at which the caller's only role had no column policy on the table, and a later change that gave
  it `columns` `["k"]`
- **WHEN** the caller requests the snapshot at that revision
- **THEN** it shows column `k` only and `permissions.columns_restricted = true`, while a caller holding `TABLE_GRANT`
  reads `columns` `null` for that role in the snapshot's `roles`

#### Scenario: A column renamed since the revision is matched by its old name

- **GIVEN** a revision at which column `project` existed, and a current entry for the caller's only role whose
  `columns` list `k` and `proj`, the name `project` was renamed to
- **WHEN** the caller requests the snapshot at that revision
- **THEN** column `project` is not shown

#### Scenario: A snapshot hides columns by the policies of its revision

- **GIVEN** a revision at which the caller's only role had `columns` `["a"]` on the table, and a later change that
  removed that restriction
- **WHEN** the caller requests the snapshot at that revision
- **THEN** it shows column `a` only and `permissions.columns_restricted = true`

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

- **WHEN** a caller requests a snapshot of a non-system table that existed before change tracking began, at a
  revision in which only its definition row was changed
- **THEN** the system returns HTTP 404 with error code `revision_not_found`, rather than a state whose
  column list is empty because those columns have no recorded history

#### Scenario: A system table's access change is readable at its revision

- **GIVEN** the system table `dial_usage_log`, on which a caller holding `TABLE_GRANT` `PUT`s an entry for role `Y`
  with `read`, recording revision `R0`, and then an entry for role `X` with `read` and a row policy, recording
  revision `R`
- **WHEN** that caller requests `GET /v1/tables/dial_usage_log/revision/R0` and
  `GET /v1/tables/dial_usage_log/revision/R`
- **THEN** both return HTTP 200 with `system` `true`, the snapshot at `R` carries `roles.X` with `["read"]` and that
  policy, and the one at `R0` carries `roles.Y` and no entry for `X`

#### Scenario: A system table's snapshot carries no columns

- **WHEN** any caller allowed to read the table requests a system table's snapshot at a revision at or after the first
  recorded state of its definition
- **THEN** the response carries no `columns` member, and carries `ordering_key` as last recorded at or before that
  revision

#### Scenario: A system table's snapshot without TABLE_GRANT

- **WHEN** a caller holding `TABLE_GET` but not `TABLE_GRANT` requests a system table's snapshot at an access change
- **THEN** it returns HTTP 200 with neither `roles` nor `columns`

#### Scenario: Columns restricted on a system table's snapshot

- **GIVEN** a system table revision at which the caller's only role had `read` and `columns` `["event_id"]`
- **WHEN** the caller requests the snapshot at that revision
- **THEN** `permissions.columns_restricted` is `true`
- **AND WHEN** a caller confined by no column policy requests the same snapshot
- **THEN** `permissions.columns_restricted` is `false`

#### Scenario: Before a system table's first recorded state

- **WHEN** a caller requests a system table's snapshot at a revision that exists but precedes every recorded state of
  its definition
- **THEN** the system returns HTTP 404 with error code `revision_not_found`

#### Scenario: A migration's change to a system table appears at its next recorded change

- **GIVEN** a system table whose definition is recorded at revision `R` with `ordering_key` `["k"]`, after which a
  migration sets its `ordering_key` to `["m"]` and an access change then records revision `R2`
- **WHEN** a caller requests the snapshots at `R` and `R2`
- **THEN** the one at `R` still carries `ordering_key` `["k"]`, and the one at `R2` carries `["m"]`

#### Scenario: An enrichment snapshot reports its source's current read-side flags

- **GIVEN** an enrichment snapshot recorded when the enrichment's own `read` grant named the caller's role, and a source
  that does not grant that role now
- **WHEN** the caller, holding `TABLE_GET` globally, requests the snapshot
- **THEN** the snapshot's `permissions.read` is `false`, and its `permissions.write` reflects the enrichment's `write`
  grants at that revision

#### Scenario: A deleted table's revision is still filtered

- **GIVEN** a table whose revision `R` was recorded before column policies existed, which later gave the caller's only
  role `columns` `["k"]` and was then deleted
- **WHEN** the caller, holding `TABLE_GET` globally, requests the snapshot at `R`
- **THEN** it shows column `k` only, not every column

