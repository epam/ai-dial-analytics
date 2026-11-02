## ADDED Requirements

### Requirement: Dashboard activities have no parent and no snapshot endpoint

A dashboard activity SHALL have resource type `Dashboard`, its resource id SHALL be the dashboard's
identifier, and it SHALL have no parent: a dashboard belongs to no other audited entity, and a widget is
part of its dashboard's state rather than an entity of its own, so a change to one widget SHALL be recorded
as an `Update` of the dashboard.

The preserved state SHALL NOT include `generation`. It is the dashboard's optimistic-locking token, not
authored state, and a past value is of no use to a client, since a replace must carry the current one.
It is also redundant: every mutation increments it by one and records a revision, so the trail's own
revision order already gives it.

A point-in-time read of a dashboard is not part of this capability's surface: the trail preserves the
state, but no snapshot endpoint for `Dashboard` is exposed.

#### Scenario: A dashboard activity has no parent

- **WHEN** a dashboard is created
- **THEN** its `Create` activity has the dashboard's identifier as resource id and no parent

#### Scenario: A refused replace records nothing

- **WHEN** a dashboard replace is refused with `409 dashboard_conflict`
- **THEN** no activity is recorded

#### Scenario: Reading a dashboard records nothing

- **WHEN** a caller lists dashboards or reads one by id
- **THEN** no activity is recorded

### Requirement: Dashboard activities are narrowed by dashboard visibility

`Dashboard` resource type activities SHALL be narrowed exactly as `SavedQuery` activities are, in the
activity feed and in single-activity retrieval: visible to a `SAVED_QUERY_PUBLISH` holder unconditionally,
to any caller holding `AUDIT_READ` when the dashboard's **current** visibility is `public`, and otherwise
only to the caller recorded as the dashboard's owner. A dashboard that has since been deleted SHALL have
its activities visible to a `SAVED_QUERY_PUBLISH` holder only, including to its former owner. This is
content narrowing within an otherwise-successful response, not a denial: a single `Dashboard` activity the
caller may not see SHALL be answered as an activity that does not exist.

The rule is the saved-query one because dashboards take their visibility and their privileges from the
saved-query model, and a `private` dashboard's name and author are as personal as a `personal` saved
query's.

#### Scenario: An owner sees their private dashboard's activity

- **WHEN** the owner of a `private` dashboard, holding `AUDIT_READ`, requests the activity feed filtered
  to that dashboard's resource id
- **THEN** the feed returns that dashboard's activity

#### Scenario: A non-owner does not see another caller's private dashboard activity

- **WHEN** a caller holding `AUDIT_READ` but not `SAVED_QUERY_PUBLISH`, who is not the owner of a
  `private` dashboard, requests the activity feed filtered to that dashboard's resource id
- **THEN** the feed is returned successfully with that dashboard's activity absent from it

#### Scenario: A public dashboard's activity is visible to any audit reader

- **WHEN** a caller holding `AUDIT_READ` requests the activity feed filtered to a `public` dashboard's
  resource id
- **THEN** the feed returns that dashboard's activity

#### Scenario: A deleted dashboard's activity is visible only to a publisher

- **WHEN** a `private` dashboard is deleted and its former owner, holding `AUDIT_READ` but not
  `SAVED_QUERY_PUBLISH`, requests the activity feed filtered to its resource id
- **THEN** the feed is returned successfully with that activity absent from it, and is present only when
  the caller holds `SAVED_QUERY_PUBLISH`

## MODIFIED Requirements

### Requirement: Audited resources

The system SHALL track every insert, update, and delete of the catalog state backing tables, and of
the pipeline registry, the saved query registry and the dashboard registry:

- the **table definition** row in its entirety — its type, status, write discipline, scan metadata,
  partitioning, TTL, ordering key, an enrichment's grain key, its role-based `access` lists, and its
  per-role `row_policies`;
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

### Requirement: Every changed entity is its own activity

The system SHALL record one activity per changed entity, naming the entity that changed rather than
a parent it belongs to. The resource types SHALL be `Table`, `TableColumn`, `Pipeline`, `SavedQuery`,
and `Dashboard`, and the activity types `Create`, `Update`, and `Delete`. All SHALL be represented on the
wire exactly as spelled here.

A resource id SHALL identify the entity uniquely within its resource type. Because a column is
identified by its table together with its physical name, a `TableColumn` resource id SHALL be those
two joined by a colon, and SHALL be treated as one opaque string. A `Pipeline` resource id SHALL be
its name, and a `SavedQuery` or `Dashboard` resource id SHALL be its identifier.

The enrichment control plane's declarations were tracked under a resource type of their own, keyed by
a UUID, before the two registries merged into one. Activities recorded then SHALL be readable in the
feed under resource type `Pipeline` and, where the declaration is still present, under its
name — the identity the merged registry gave it. An activity for a declaration that had already been
deleted before the merge cannot be re-identified and SHALL keep its former identifier as an opaque
resource id, like any other activity naming a resource that no longer exists. No activity SHALL be
discarded to achieve this.

The point-in-time read is the part the merge does not carry over: a merged declaration's preserved
state was recorded against its former identity, so its reconstructable history SHALL begin at the
merge, and a snapshot requested at an earlier revision SHALL be answered as a resource whose creation
is not recorded in the trail.

A change to a table's ordering key or to an enrichment's grain key SHALL be recorded as an `Update` on
the owning `Table`: both are properties of the definition row and have no identity apart from it.

An ordinary update SHALL record an activity only for the entities it actually changes: a schema patch
naming three columns records three column activities and nothing for the table's other columns.
Likewise, repointing a source table's `version_column` and thereby bumping every enrich pipeline that
reads it SHALL record one `Update` activity per pipeline actually bumped, and nothing for pipelines
that read a different source.

One limit is stated rather than promised away. Defining the schema of a table that is not yet
materialized replaces its column rows wholesale, so re-posting such a schema SHALL record an `Update`
for every column it re-states, including those whose values are unchanged. The trail stays truthful —
those rows were rewritten — but it is coarser than a minimal diff on that one path.

#### Scenario: Column change names the column

- **WHEN** a schema patch adds one column to a table
- **THEN** the feed shows a `Create` activity whose resource type is `TableColumn` and whose
  resource id identifies both the table and that column

#### Scenario: Multi-column patch records every column

- **WHEN** one schema patch adds, drops, and renames different columns of a table
- **THEN** the feed shows a separate activity for each affected column, each naming that column

#### Scenario: A rename ends one column resource and starts another

- **WHEN** a schema patch renames a column
- **THEN** a `Delete` activity is recorded for the resource id carrying its former physical name and a
  `Create` activity for the resource id carrying its new one, in the same revision

#### Scenario: A patch records only the columns it names

- **WHEN** a schema patch changes one column of a table that has several
- **THEN** an activity is recorded for that column and for none of the others

#### Scenario: Re-posting a schema records every column it re-states

- **WHEN** the schema of a not-yet-materialized table is re-posted with one column changed and the rest
  declared exactly as they already are
- **THEN** an `Update` is recorded for every re-stated column, and the request succeeds

#### Scenario: Ordering-key change is an activity on the table

- **WHEN** a table's ordering key changes
- **THEN** an `Update` activity for resource type `Table` is recorded, and no separate resource type
  is used for the ordering key

#### Scenario: Grain is recorded on the table

- **WHEN** an enrichment's schema is defined and it acquires its grain key
- **THEN** an `Update` activity for resource type `Table` is recorded whose preserved state carries
  the grain key, and no separate resource type is used for the grain

#### Scenario: Bulk generation bump records every affected pipeline

- **WHEN** repointing a source table's `version_column` bumps the generation of three enrich pipelines
  that read that source, and a fourth pipeline reading a different source is unaffected
- **THEN** three `Update` activities for resource type `Pipeline` are recorded, one per bumped
  pipeline, and none is recorded for the unaffected one

#### Scenario: A widget change is an activity on the dashboard

- **WHEN** a dashboard is replaced with one widget's layout changed
- **THEN** one `Update` activity for resource type `Dashboard` is recorded, and no separate resource type
  is used for the widget
