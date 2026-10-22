## MODIFIED Requirements

### Requirement: Audited resources

The system SHALL track every insert, update, and delete of the catalog state backing tables, and of
the pipeline registry and the saved query registry:

- the **table definition** row in its entirety — its type, status, write discipline, scan metadata,
  partitioning, TTL, ordering key, an enrichment's grain key, its role-based `access` lists, and its
  per-role `row_policies`;
- every **column** of a table — its type, element type, nullability, description, display name, tag,
  ordinal, and its `sensitive` and `heavy` flags;
- every **pipeline**, of either kind — its name, kind, target, input table, filter, transform
  declaration, trigger configuration (including a group trigger's key, readiness and membership
  conditions), enabled flag, generation counter, and timestamps. For an `enrich` pipeline the
  **transform declaration** SHALL include the folded evaluator definition — the `transform` block's
  `type`, `model`, `preset`, `params`, `request_template`, **`inputs`** and `outputs`, or the
  `output_vars` and stored `response_schema` a folded legacy declaration carries instead — so a change
  to a prompt, a model, an input binding, an output's prose or a stored response schema is a tracked,
  attributed revision like any other declaration change. `inputs` is named explicitly because it was the
  top-level `vars` before the fold and was audited then; omitting it from this enumeration would read as
  a binding change having become untracked. Its **runtime
  position** is deliberately excluded: the scan cursor, scheduling and last-run state, the clamp
  record, and the Class-1 materialized position are not tracked. Those move on every scheduler tick
  or are written by bulk updates the persistence context never sees, so tracking them would either
  flood the feed with entries no one administered or mirror a value that disagrees with the live row;
- every **saved query** — its identity, name, description, tag, scope, owner, source, its structured
  or SQL body, time intent, result view, chart configuration, generation counter, and timestamps.

For each tracked change the system SHALL preserve the complete state of the changed entity at that
point, so the resource can be read back as it stood at that revision. A delete SHALL preserve the
entity's final state rather than a row of nulls.

Analytics row writes (`POST /v1/tables/{name}/rows`) are data-plane writes to ClickHouse and SHALL
NOT be audited. The **retained evaluator archive** — the `evaluator` and `evaluator_version` tables,
readable through `GET /v1/evaluators*` and closed to writes — SHALL remain outside this capability's
coverage. Auditing it would record nothing: no route can change it, so no revision can ever be
produced. Before the fold this exclusion covered a live registry and was a real gap in attribution;
it now covers an immutable archive, and the gap it left is closed by the transform being part of the
audited pipeline declaration.

Two **known and accepted limitations** SHALL be stated rather than discovered.

First, a `Pipeline` revision recorded **before** the fold carries no transform, because those members
were not part of the declaration then. A snapshot at such a revision SHALL return the declaration as
it was stored — without `transform` — rather than synthesising it from the evaluator the pipeline
named at the time. Reconstructing which evaluator version a past revision resolved to, and inlining
it, is out of scope: the pin was resolved at read time, not stored per revision. Nor does the snapshot
answer with the pin: a revision read re-serialises the stored declaration **in the current shape**
(see [pipeline-registry](../pipeline-registry/spec.md), "Pipeline registry object"), and the current
shape has no `evaluator_name`. The linkage survives in two places outside this endpoint, and both are
deliberate: `pipeline_aud` keeps its `evaluator_name` and `evaluator_version` mirror columns, which
carry no foreign key precisely so they outlive the registry, and the fold migration logs every
`(pipeline, evaluator, resolved version)` triple it folds. The retained archive is where the
definition those name is read.

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

- **WHEN** an administrator replaces a table's role-based access lists through
  `PUT /v1/tables/{name}/access`
- **THEN** an `Update` activity for that table is recorded, and the snapshot read by a caller holding
  `TABLE_GRANT` carries the new lists under `access` while the preceding revision's snapshot carries the
  old ones

#### Scenario: Sensitivity change is tracked

- **WHEN** a schema patch marks a column `sensitive`
- **THEN** an `Update` activity for that `TableColumn` is recorded and the snapshot carries the flag for a
  caller cleared to see that sensitive column; for any other caller the column is absent from both the
  before and after snapshots

#### Scenario: Row-policy change is tracked

- **WHEN** a caller holding `TABLE_GRANT` creates, replaces or removes a role's policy through
  `PUT` or `DELETE /v1/tables/{name}/row-policies/{role}`
- **THEN** an `Update` activity for that table is recorded, and the snapshot read by a caller holding
  `TABLE_GRANT` carries the resulting policy map under `row_policies` while the preceding revision's
  snapshot carries the previous one

#### Scenario: Reordering roles in an access list records nothing

- **WHEN** a caller `PUT`s `/v1/tables/{name}/access` with the same roles in each list as stored, in a
  different order
- **THEN** no revision and no activity are recorded, and both the `PUT` response and
  `GET /v1/tables/{name}/access` keep the stored order

#### Scenario: A save that changes one list keeps a reordered list's stored order

- **WHEN** a caller `PUT`s `/v1/tables/{name}/access` changing the roles of one list and sending another
  list's stored roles in a different order
- **THEN** one `Update` activity is recorded, and in its snapshot, the `PUT` response and
  `GET /v1/tables/{name}/access` the reordered list keeps its stored order, so the before/after differs only
  in the changed list

#### Scenario: Re-saving an identical row policy records nothing

- **WHEN** a caller `PUT`s for a role exactly the policy that role already has on the table
- **THEN** the policy is returned as on any `PUT`, and no revision and no activity are recorded

#### Scenario: Deletion preserves final state

- **WHEN** an audited resource is deleted
- **THEN** the preserved state for that change is the resource's state immediately before deletion

#### Scenario: Row writes are not tracked

- **WHEN** rows are written through `POST /v1/tables/{name}/rows`
- **THEN** no activity is recorded

#### Scenario: The evaluator archive is not tracked

- **WHEN** the retained `evaluator` and `evaluator_version` tables are read through `GET /v1/evaluators*`
- **THEN** no activity is recorded for them, and no route exists that could change them, so no
  revision is ever produced

#### Scenario: A prompt change is a tracked, attributed revision

- **WHEN** `PATCH /v1/pipelines/{name}` changes an `enrich` pipeline's `transform.request_template`,
  `transform.model` or an output's prose
- **THEN** an `Update` activity for resource type `Pipeline` is recorded, attributed to the calling
  identity, and its preserved state carries the new transform in full

#### Scenario: A pre-fold pipeline revision carries no transform

- **WHEN** a caller requests a `Pipeline` snapshot at a revision recorded before the fold
- **THEN** the snapshot returns the declaration as it was stored at that revision, without the
  `transform` and without an `evaluator_name`, rather than resolving the evaluator the pipeline named
  at the time

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

### Requirement: Historical table snapshots

The system SHALL expose `GET /v1/tables/{name}/revision/{revision}` returning the table as it stood
at the end of the given revision, in the same response shape as the current-state read
(`GET /v1/tables/{name}`) plus the two grant members below. The snapshot SHALL include the columns, ordering key, and grain that
belonged to the table at that revision, and SHALL exclude a column that had been dropped by then.

Every member of that response describes the table **as of the requested revision**, including the
caller's effective permissions, which are resolved from the access lists the table had then. A snapshot
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
