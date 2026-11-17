## MODIFIED Requirements

### Requirement: Audited resources

The system SHALL track every insert, update, and delete of the catalog state backing tables, and of
the pipeline registry, the saved query registry and the dashboard registry:

- the **table definition** row in its entirety — its type, status, write discipline, scan metadata,
  partitioning, TTL, ordering key, an enrichment's grain key, and its per-role access entries: each role's
  privileges, row policy and column policy. Its access `generation`, the optimistic-locking token of its access
  entries, SHALL NOT be part of the preserved state;
- every **column** of a table — its type, element type, nullability, description, display name, tag,
  ordinal, and its `sensitive` and `heavy` flags;
- every **pipeline**, of either kind — its name, kind, target, input table, filter, transform
  declaration, trigger configuration (including a group trigger's key, readiness and membership
  conditions), enabled flag, generation counter, and timestamps. For an `enrich` pipeline the
  **transform declaration** SHALL include the whole `transform` block — its `type`, `model`, `preset`,
  `params`, `request_template`, **`inputs`** and `outputs`, or the `output_vars` and stored
  `response_schema` a stored legacy-shape declaration carries instead — so a change to a prompt, a model,
  an input binding, an output's prose or a stored response schema is a tracked, attributed revision like
  any other declaration change. Its **runtime
  position** is deliberately excluded: the scan cursor, scheduling and last-run state, the clamp
  record, and the Class-1 materialized position are not tracked. Those move on every scheduler tick
  or are written by bulk updates the persistence context never sees, so tracking them would either
  flood the feed with entries no one administered or mirror a value that disagrees with the live row;
- every **saved query** — its identity, name, description, tag, scope, owner, source, its structured
  or SQL body, time intent, result view, chart configuration, generation counter, and timestamps;
- every **dashboard** — its identity, name, description, visibility, owner, time intent, comparison, its
  widgets in full (each widget's query reference or inline body, rendering members and layout), and
  timestamps. Its `generation`, the optimistic-locking token, SHALL NOT be part of the preserved state,
  and neither SHALL the per-caller `permissions` a dashboard response carries, which are computed, not
  stored.

For each tracked change the system SHALL preserve the complete state of the changed entity at that
point, so the resource can be read back as it stood at that revision. A delete SHALL preserve the
entity's final state rather than a row of nulls.

Analytics row writes (`POST /v1/tables/{name}/rows`) are data-plane writes to ClickHouse and SHALL
NOT be audited.

Two **known and accepted limitations** SHALL be stated rather than discovered.

First, a `Pipeline` revision recorded **before** the transform became part of the pipeline's
declaration (a pre-fold revision) carries no transform. A snapshot at such a revision SHALL return the
stored declaration re-serialised **in the current shape** — without `transform` — (see
[pipeline-registry](../pipeline-registry/spec.md), "Pipeline registry object"), rather than
synthesising a transform for it. Which evaluator version such a revision resolved to is not
recoverable from the service.

Second, **the fold migration itself records no revision**. It rewrites `spec` in raw SQL, outside the
persistence context, and deliberately bumps neither `generation` nor `updated_at` — bumping would
re-sync every runner lane and discard every Class-1 materialized position for a change no consumer can
observe. The trail therefore shows a pipeline whose transform appears between two revisions with no
revision in between. This is the correct trade: the alternative is a revision attributed to no
identity, describing an administrative act nobody performed.

#### Scenario: A scheduled refresh creates no revision

- **WHEN** an enabled aggregate pipeline's scheduled refresh advances its cursor, records its last run and
  sets its next run
- **THEN** no revision and no activity are recorded for it, because every field written is runtime position

This scenario is the exclusion above stated as an observable, because the exclusion is defeasible by a
single line elsewhere: it holds only while the runtime writers leave the pipeline's **audited** columns
alone. `updated_at` is audited, so a state write that stamped it produced one revision per write — two per
fire — whose audited columns were byte-identical to the previous revision's. `updated_at` therefore means
*the declaration last changed*, which is also what the enrichment runner's incremental sync needs it to
mean; run state is carried by `last_run_at` and `next_run_at`, which are not audited.

#### Scenario: Table creation is tracked

- **WHEN** a table is created through `POST /v1/tables`
- **THEN** a `Create` activity for resource type `Table` with that table's name is recorded

#### Scenario: Schema definition is tracked

- **WHEN** a table's schema is defined and materialized through `POST /v1/tables/{name}/schema`
- **THEN** an `Update` activity for the `Table` and a `Create` activity for each declared
  `TableColumn` are recorded, and the resulting snapshot carries the persisted columns, ordering key,
  and status

#### Scenario: Access-list change is tracked

- **WHEN** a caller holding `TABLE_GRANT` creates, replaces or removes a role's entry through
  `PUT` or `DELETE /v1/tables/{name}/access?role=<role>`
- **THEN** one `Update` activity for that table is recorded, and the snapshot read by a caller holding
  `TABLE_GRANT` carries the resulting entries under `roles` while the preceding revision's snapshot carries the
  previous ones

#### Scenario: Row-policy change is tracked

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s for role `X` an entry whose `rows` differs from the stored one, or
  `null` where one was stored
- **THEN** one `Update` activity for that table is recorded, and the snapshot read by a caller holding
  `TABLE_GRANT` carries the resulting policy under `roles.X.rows` while the preceding revision's snapshot carries the
  previous one

#### Scenario: A PUT for one role leaves other entries unchanged in the revision

- **WHEN** a caller `PUT`s a changed entry for role `X` on a table that also has entries for other roles
- **THEN** one `Update` activity is recorded, and the new revision's `roles` differs from the previous revision's
  only in `X`'s entry

#### Scenario: Sensitivity change is tracked

- **WHEN** a schema patch marks a column `sensitive`
- **THEN** an `Update` activity for that `TableColumn` is recorded and the snapshot carries the flag for a
  caller cleared to see that sensitive column; for any other caller the column is absent from both the
  before and after snapshots

#### Scenario: Re-ordering an entry's privileges or columns records nothing

- **WHEN** a caller `PUT`s for a role the privileges and columns its entry already has, in a different order
- **THEN** no revision and no activity are recorded, and both the `PUT` response and `GET /v1/tables/{name}/access`
  carry the entry in its stored order

#### Scenario: Re-saving an identical row policy records nothing

- **WHEN** a caller `PUT`s for a role exactly the entry, row policy included, that role already has on the table
- **THEN** the entry is returned as on any `PUT`, the access `generation` does not move, and no revision and no
  activity are recorded

#### Scenario: A concurrent edit records the committed access entries

- **WHEN** a description update of a table runs while a `PUT .../access?role=<role>` on it commits
- **THEN** whichever commits second records a revision whose `roles` carry the entries the `PUT` stored, and the
  description of the description update; neither revision restores the other's previous state

#### Scenario: A rename that rewrites a column policy is one revision

- **WHEN** a schema patch renames a column that a role's `columns` lists
- **THEN** the table's `Update` activity recorded for the patch carries the rewritten entry in its snapshot, and no
  separate activity is recorded for it

#### Scenario: An enrichment column rename records an update of its source

- **GIVEN** a source on which role `X`'s `columns` lists `e.label`, a column of its enrichment `e`
- **WHEN** a schema patch of `e` renames `label` to `title`
- **THEN** the patch's revision records, besides `e`'s own activities, an `Update` activity for the source, whose
  snapshot read by a caller holding `TABLE_GRANT` carries `roles.X.columns` naming `e.title`

#### Scenario: Deletion preserves final state

- **WHEN** an audited resource is deleted
- **THEN** the preserved state for that change is the resource's state immediately before deletion

#### Scenario: Row writes are not tracked

- **WHEN** rows are written through `POST /v1/tables/{name}/rows`
- **THEN** no activity is recorded

#### Scenario: A prompt change is a tracked, attributed revision

- **WHEN** `PATCH /v1/pipelines/{name}` changes an `enrich` pipeline's `transform.request_template`,
  `transform.model` or an output's prose
- **THEN** an `Update` activity for resource type `Pipeline` is recorded, attributed to the calling
  identity, and its preserved state carries the new transform in full

#### Scenario: A pre-fold pipeline revision carries no transform

- **WHEN** a caller requests a `Pipeline` snapshot at a revision recorded before the fold
- **THEN** the snapshot returns the declaration as it was stored at that revision, without a
  `transform`, rather than a synthesised one

#### Scenario: The fold migration records no revision

- **WHEN** the fold migration rewrites every `enrich` pipeline's `spec` with its resolved transform
- **THEN** no `Pipeline` revision and no activity are recorded, and no pipeline's `generation` or
  `updated_at` changes

#### Scenario: Pipeline mutations are tracked

- **WHEN** a pipeline of either kind is created through `POST /v1/pipelines`, changed through
  `PATCH /v1/pipelines/{name}`, or removed through `DELETE /v1/pipelines/{name}`
- **THEN** a `Create`, `Update`, or `Delete` activity respectively is recorded for resource type
  `Pipeline`, and its preserved state carries the pipeline's full configuration at that point

#### Scenario: A scheduler tick records no activity

- **WHEN** an aggregate pipeline's scheduled refresh advances its cursor and last-run state, or a
  Class-1 materialize advances its materialized position
- **THEN** no activity is recorded — nothing an administrator did changed

#### Scenario: Saved query mutations are tracked

- **WHEN** a saved query is created through `POST /v1/saved-queries`, changed through
  `PUT /v1/saved-queries/{id}`, or removed through `DELETE /v1/saved-queries/{id}`
- **THEN** a `Create`, `Update`, or `Delete` activity respectively is recorded for resource type
  `SavedQuery`, and its preserved state carries the saved query's full configuration at that point

#### Scenario: Dashboard mutations are tracked

- **WHEN** a dashboard is created through `POST /v1/dashboards`, replaced through
  `PUT /v1/dashboards/{id}`, or removed through `DELETE /v1/dashboards/{id}`
- **THEN** a `Create`, `Update`, or `Delete` activity respectively is recorded for resource type
  `Dashboard`, and its preserved state carries the dashboard's full configuration at that point

### Requirement: Historical table snapshots

The system SHALL expose `GET /v1/tables/{name}/revision/{revision}` returning the table as it stood
at the end of the given revision, in the same response shape as the current-state read
(`GET /v1/tables/{name}`) plus the access member below. The snapshot SHALL include the columns, ordering key, and grain that
belonged to the table at that revision, and SHALL exclude a column that had been dropped by then.

Every member of that response describes the table **as of the requested revision**, including the
caller's effective permissions, which are resolved from the access entries the table had then. The columns it shows
are the exception that fails closed: a column SHALL be hidden when either the column policies the table had then or
the table's current column policies hide it from the caller, matched by the name the column had at that revision. An
enrichment is the
one exception: its read-side access is its source's (see `table-access-control`), and the source's entries at that
revision are not part of the enrichment's history, so an enrichment snapshot's `read` and `sensitive`, and which of its
columns it shows, SHALL be resolved from its source's current state, its `rows_restricted` from the source's current
state or a policy the enrichment had at that revision, its `write` and `modify` from the grants the
enrichment had then, and its `get` from either: the source's current state, or the `modify` grants the enrichment had
then. For an enrichment whose source no longer exists, the source's entries SHALL be taken as empty, so only a global
privilege — or, for `get`, the enrichment's `modify` grants at that revision — sets a read-side flag. A snapshot
therefore answers "what could this caller have done then", not "what may they do now", and SHALL NOT be
used to decide whether an action is currently permitted — the live read answers that. Column policies are judged
both ways because a column restricted today would otherwise reappear, metadata and all, in every revision recorded
before the restriction, those recorded before column policies existed included; a column renamed since the revision
is hidden when the current allowlist lists only its new name. The snapshot's `permissions.columns_restricted` is judged
over the revision's own columns. A column's `sensitive` flag is still resolved as of the revision.

The snapshot SHALL additionally carry the table's access entries as of the revision as `roles`, in the shape of the
`roles` member of `GET /v1/tables/{name}/access` — each role's `privileges`, `rows` and `columns` — to a caller holding
`TABLE_GRANT`, and in `none` security mode. That is exactly what reading them live requires, so a past grant is never
more visible than the present one. For every other caller the member SHALL be absent, not empty: an empty object would
assert that the table had no entries. When served, it is always present, an empty one being `{}`, including at a
revision recorded before row or column policies existed, where `rows` and `columns` read `null` (its columns are
still filtered by the table's current column policies, as above). The snapshot SHALL
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

- **WHEN** a caller requests a snapshot of a table that existed before change tracking began, at a
  revision in which only its definition row was changed
- **THEN** the system returns HTTP 404 with error code `revision_not_found`, rather than a state whose
  column list is empty because those columns have no recorded history

#### Scenario: An enrichment snapshot reports its source's current read-side flags

- **GIVEN** an enrichment snapshot recorded when the enrichment's own `read` grant named the caller's role, and a source
  that does not grant that role now
- **WHEN** the caller, holding `TABLE_GET` globally, requests the snapshot
- **THEN** the snapshot's `permissions.read` is `false`, and its `permissions.write` reflects the enrichment's `write`
  grants at that revision
