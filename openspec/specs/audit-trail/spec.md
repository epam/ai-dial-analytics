# audit-trail Specification

## Purpose
Change tracking for the ADAS catalog and declaration registries: every mutation of a table
definition, a table column, a pipeline of either kind, or a saved query is preserved as a full
historical snapshot attributed to the identity that made it, and is exposed as a filtered activity
feed plus point-in-time reads of any tracked resource as it stood at any past revision.

## Requirements

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
  or SQL body, time intent, result view, chart configuration, generation counter, and timestamps.

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

### Requirement: Dependent removals are recorded

When deleting a resource also removes audited state that belongs to it, the system SHALL record a
`Delete` activity, and preserve the final state, for **every** audited entity that ceases to exist —
not only for the resource the caller named.

Deleting a table SHALL therefore record a `Delete` for the table and for each of its columns, all
sharing the one revision of the deleting transaction. An enrichment's grain and a table's ordering key
are properties of the definition row, so they are preserved by that row's own `Delete` and need no
record of their own.

A table that is a pipeline's `target` exists only to be filled by it, so deleting the table deletes
that pipeline. That removal SHALL go through the registry's own repository, in the same transaction as
the table's deletion and before the table is removed, rather than being left to the schema's
`ON DELETE CASCADE` — a database cascade fires no persistence event and the removal would be absent
from the trail. Deleting such a table SHALL therefore also record a `Delete` for its dependent
pipeline, sharing the same revision as the table's own `Delete`.

This is one rule for both kinds, not two: a target is unique across the registry, an aggregate
pipeline's target being a source table and an enrich pipeline's an enrichment table.

This is a correctness requirement for the historical read surface as much as for the feed: an entity
whose removal is not recorded would continue to appear in snapshots taken at revisions after it
ceased to exist.

#### Scenario: Deleting a table records its columns

- **WHEN** a table with columns is deleted
- **THEN** a `Delete` activity is recorded for the table and for each of its columns, all sharing one
  revision

#### Scenario: Deleting an enrichment preserves its grain

- **WHEN** an enrichment table that has a grain is deleted
- **THEN** `Delete` activities are recorded for the table and for each of its columns, and the table's
  preserved final state carries the grain key it had

#### Scenario: A deleted table's columns do not appear in later snapshots

- **WHEN** a table is deleted and a snapshot is requested at a revision after the deletion
- **THEN** the resource is reported as not existing at that revision, and none of its columns are
  returned at any later revision

#### Scenario: Deleting a pipeline's target table records the pipeline

- **WHEN** a table that is a pipeline's target is deleted — a source table targeted by an aggregate
  pipeline, or an enrichment table targeted by an enrich pipeline
- **THEN** a `Delete` activity is recorded for the table, for each of its columns, and for the
  pipeline that named it as target, all sharing one revision

### Requirement: Every changed entity is its own activity

The system SHALL record one activity per changed entity, naming the entity that changed rather than
a parent it belongs to. The resource types SHALL be `Table`, `TableColumn`, `Pipeline`, and
`SavedQuery`, and the activity types `Create`, `Update`, and `Delete`. All SHALL be represented on the
wire exactly as spelled here.

A resource id SHALL identify the entity uniquely within its resource type. Because a column is
identified by its table together with its physical name, a `TableColumn` resource id SHALL be those
two joined by a colon, and SHALL be treated as one opaque string. A `Pipeline` resource id SHALL be
its name, and a `SavedQuery` resource id SHALL be its identifier.

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

### Requirement: Author identity captured from the security context

The system SHALL record, on every revision and every activity, the principal name and email of the
identity that initiated the change, read from the authenticated security context at the time of the
change. When the security context holds no authentication — which is always the case in `none`
security mode — the system SHALL record the author `unknown` and a null email. Work ADAS runs on its
own behalf already authenticates as its system principal and SHALL therefore be attributed to that
principal rather than to `unknown`.

A failure to read the identity SHALL degrade the recorded author to `unknown` and SHALL NOT fail the
operation being audited.

Author identity is recorded for attribution only; it SHALL never be used to authorize a request.

#### Scenario: Authenticated caller attributed

- **WHEN** an authenticated administrator changes a table in `oidc` mode
- **THEN** the resulting activity records that caller's principal name and, when the token carries
  one, their email

#### Scenario: Unauthenticated caller attributed as unknown

- **WHEN** a catalog change is made with no authentication present (for example in `none` security
  mode)
- **THEN** the activity records author `unknown` and no email

#### Scenario: Background work attributed to the system principal

- **WHEN** ADAS's own scheduled work changes an audited resource
- **THEN** the activity records the system principal rather than `unknown`

#### Scenario: Identity failure does not break the operation

- **WHEN** reading the caller's identity fails while an audited change is being made
- **THEN** the change succeeds and the activity records author `unknown`

### Requirement: Transaction-consistent revisions

The system SHALL group every audited change made within one transaction under a single revision
carrying one identifier and one timestamp, and SHALL persist audit records in that same transaction.
The activity feed SHALL be filterable by revision, so the several entity-level activities caused by
one administrative act can be retrieved as that act.

The unit is the transaction, not the request. One endpoint call MAY therefore produce more than one
revision where it deliberately commits in stages: defining a schema commits the catalog before issuing
the ClickHouse DDL, so that a failure can leave neither a materialized table without a definition nor a
definition claiming a table that was never created. The trail SHALL reflect those as the separate acts
they are rather than merging them, because a snapshot between them describes a state the system really
passed through.

#### Scenario: One transaction is one revision

- **WHEN** a single request changes several audited entities
- **THEN** all resulting audit records and activities share one revision identifier and one timestamp

#### Scenario: No audit record on rollback

- **WHEN** a transaction that changed audited entities is rolled back
- **THEN** no revision, snapshot, or activity is persisted for it

#### Scenario: One schema patch is retrievable as one act

- **WHEN** a schema patch changes several columns and the feed is then filtered by the revision of
  any one of the resulting activities
- **THEN** every activity produced by that patch is returned, and no activity from any other
  transaction

#### Scenario: Materialization is its own revision

- **WHEN** a table's schema is defined and the table is materialized through
  `POST /v1/tables/{name}/schema`
- **THEN** the catalog write and the confirmation of materialization are recorded as two revisions, and
  a snapshot at the first reports the table as not yet materialized

### Requirement: Child activities link to their parent

An activity for an entity that belongs to another audited entity SHALL carry the activity identifier
of that owning entity's activity in the same revision, when one exists, so a client can present the
children collapsed under the act that caused them. When no such owning activity exists in that
revision, the child's parent identifier SHALL be absent — this is a normal outcome, not an error.

This applies to two parent/child shapes: a `TableColumn` activity's parent is the `Table` activity
for its own table; and, for a `Pipeline` activity produced by a bulk generation bump, its parent is
the `Table` activity of the source table whose `version_column` repoint caused the bump —
`version_column` is a property of the table definition row itself, so the driving change is always a
`Table` activity, never a `TableColumn` one. The bumped pipeline's parent is resolved from its
**effective** read source: its declared input table, or, when it declares none, its target
enrichment's source table, which is the half of the bump whose parent cannot be read off the
resource id.

A `Pipeline` activity produced by a direct create, update, or delete through the pipeline endpoints
has no owning entity in the same revision and therefore no parent. A `Pipeline` activity produced by
the deletion of its target table likewise carries no parent: a pipeline's owner for this purpose is
the table it reads, not the table it writes. A `SavedQuery` activity has no parent at all — it
belongs to no other audited entity.

A `Table` activity SHALL have no parent.

#### Scenario: Columns link to their table's activity

- **WHEN** a schema patch changes both the table definition and several of its columns
- **THEN** each resulting `TableColumn` activity carries the identifier of that revision's `Table`
  activity

#### Scenario: Parent link is order-independent

- **WHEN** a column change is recorded before the table-definition change within the same revision
- **THEN** the column's activity still carries the `Table` activity's identifier

#### Scenario: A column change with no table change has no parent

- **WHEN** a revision changes a column but not its table definition row
- **THEN** the resulting `TableColumn` activity carries no parent identifier and is returned normally

#### Scenario: A directly-mutated pipeline has no parent

- **WHEN** a pipeline is created, changed, or deleted through the pipeline endpoints
- **THEN** the resulting `Pipeline` activity carries no parent identifier

#### Scenario: Bulk-bumped pipelines link to their source table's activity

- **WHEN** repointing a source table's `version_column` bumps an enrich pipeline's generation in the
  same revision as the `Table` activity for that source change, the pipeline declaring no input table
  of its own so that its read source derives through its target enrichment
- **THEN** the resulting `Pipeline` activity carries the identifier of that source's `Table` activity
  in the same revision

### Requirement: Activity deduplication within a revision

When the same entity is affected more than once within one revision, the system SHALL retain exactly
one activity for it. `Create` and `Delete` SHALL take precedence over `Update`.

#### Scenario: Repeated update collapses

- **WHEN** the same entity is updated twice within one transaction
- **THEN** exactly one `Update` activity exists for it in that revision

#### Scenario: Create then update collapses to create

- **WHEN** an entity is created and further modified within the same transaction
- **THEN** only the `Create` activity is retained for that entity

#### Scenario: Distinct entities in one revision stay distinct

- **WHEN** one transaction changes a table definition and two of its columns
- **THEN** three activities are recorded — one `Table` and two `TableColumn` — sharing one revision

### Requirement: Activity feed listing

The system SHALL expose `POST /v1/activities` taking a request of
`{pageNumber, pageSize, sorts, filters}` and returning `{total, totalPages, data}`, where `total` is
the count of all matches and `totalPages` the number of pages at the requested page size.

- `pageNumber` SHALL be zero-based and default to `0`; `pageSize` SHALL default to `20` and be
  bounded, with an out-of-range value rejected as HTTP 400.
- `sorts` SHALL be an ordered list of `{column, direction}` where `direction` is `ASC` or `DESC`, the
  first entry primary and the rest tie-breakers. When `sorts` is empty the system SHALL order by
  timestamp descending, so that paging over an unsorted request is stable.
- `filters` SHALL be a flat list of `{column, operator, value}` combined conjunctively, with `value`
  always carried as a string. The operators SHALL be `eq`, `ne`, `co` (contains), `nc` (not
  contains), `lt`, `gt`, `le`, `ge`, and `in` (comma-separated set membership). A range SHALL be
  expressed as two filters on the same column.
- An operator SHALL be rejected on a column where it asks no meaningful question: `co`/`nc` apply only
  to text, and `lt`/`gt`/`le`/`ge` only to text and numbers. Ordering an enum or an identifier would
  compare how its values are spelled rather than anything about the resources, so it is refused rather
  than answered misleadingly.
- `ne` and `nc` SHALL match rows that have no value in that column. "Not equal to x" includes "has no
  value", which is what a reader means by it; the alternative silently hides those rows, and for a
  column like the parent activity identifier that would conceal every top-level activity.

Each returned activity SHALL carry its identifier, activity type, resource type, resource id,
revision, timestamp, initiating author, initiating author email, and its parent activity identifier
when it has one.

Filtering and sorting SHALL be restricted to an allow-listed set of columns. A column outside that
set SHALL be rejected with HTTP 400 and the error code `invalid_filter_column`. A value that cannot
be interpreted for its column — a non-numeric value for a numeric column, an absent value — SHALL be
rejected with HTTP 400 and the error code `invalid_filter_value`. Neither response SHALL echo an
underlying exception message.

A `co` or `nc` value SHALL be matched literally: wildcard characters in the value SHALL NOT act as
wildcards.

#### Scenario: Filter by resource

- **WHEN** the feed is requested filtering `resourceType` `eq` `Table` and `resourceId` `eq` a table
  name
- **THEN** only activities for that table definition are returned, and none for its columns

#### Scenario: Filter by activity type

- **WHEN** the feed is requested filtering `activityType` `eq` `Delete`
- **THEN** only delete activities are returned

#### Scenario: Filter by author email

- **WHEN** the feed is requested filtering `initiatedEmail` `eq` an email, or `co` a part of one
- **THEN** only activities whose initiating author carried that email are returned

#### Scenario: Time range as two filters

- **WHEN** the feed is requested with a `ge` and a `le` filter on the timestamp column
- **THEN** only activities within that window are returned

#### Scenario: Set membership

- **WHEN** the feed is requested filtering the parent activity identifier with `in` and a
  comma-separated list
- **THEN** activities whose parent is any of those identifiers are returned

#### Scenario: Default ordering is newest first

- **WHEN** the feed is requested with no `sorts`
- **THEN** results are ordered by timestamp descending

#### Scenario: Total and page count returned

- **WHEN** the feed is requested with a `pageSize` smaller than the number of matches
- **THEN** one page of results is returned together with the total match count and the total number
  of pages

#### Scenario: Unknown filter column rejected

- **WHEN** the feed is requested with a filter on a column outside the allow-list
- **THEN** the request is rejected with HTTP 400, the error code `invalid_filter_column`, and an
  `ErrorView` body

#### Scenario: Unparseable filter value rejected

- **WHEN** the feed is requested with a non-numeric value for a numeric column
- **THEN** the request is rejected with HTTP 400, the error code `invalid_filter_value`, and an
  `ErrorView` body

#### Scenario: Out-of-range numeric filter value rejected

- **WHEN** the feed is filtered on the revision with a number too large for a revision identifier
- **THEN** the request is rejected with HTTP 400 and the error code `invalid_filter_value`, rather
  than failing as a server or database error

#### Scenario: An operator that does not apply to its column is rejected

- **WHEN** the feed is requested ordering an activity type or an identifier with `lt`, `gt`, `le` or
  `ge`
- **THEN** the request is rejected with HTTP 400 and the error code `invalid_filter_value`

#### Scenario: Not-equal includes rows with no value

- **WHEN** the feed is filtered with `ne` on the parent activity identifier
- **THEN** activities that have no parent are returned, alongside those whose parent differs

#### Scenario: Wildcards in a contains value are literal

- **WHEN** a `co` filter value contains a wildcard character
- **THEN** it matches that character literally rather than acting as a wildcard

### Requirement: Single activity retrieval

The system SHALL expose `GET /v1/activities/{activity_id}` returning that one activity. An unknown
identifier SHALL be answered with HTTP 404 and the error code `activity_not_found`.

#### Scenario: Known activity returned

- **WHEN** a caller requests an activity by an identifier taken from the feed
- **THEN** that activity is returned

#### Scenario: Unknown activity

- **WHEN** a caller requests an activity by an unknown identifier
- **THEN** the system returns HTTP 404 with error code `activity_not_found`

### Requirement: Activities carry the import that produced them

Every activity of a revision written while a catalog import (`POST /v1/catalog/import`) applies a bundle SHALL carry an
`importId` equal to the `import_id` that import returns in its `ImportResult`. Every other activity SHALL carry no `importId`.
Presence of the field is what marks an activity as part of an import; its value is what correlates the activities of one
import.

The tag SHALL follow the revision, not the resource type: it covers every activity of such a revision — the `Table` create, the
schema's `Table` update and `TableColumn` creates, each `Pipeline` create, every compensation delete, and any dependent removal a
compensation delete records. It SHALL NOT replace or alter the parent links of "Child activities link to their parent": an
imported `TableColumn` activity still points at its own `Table` activity, and no activity is created to stand for the import
itself.

A revision committed by any other request, or by background work, SHALL NOT carry an `importId`, even while an import is
running at the same time. An import preview writes no revision and therefore tags nothing. Activities recorded before this
field existed SHALL carry no `importId`.

The feed SHALL return `importId` on each activity that has one, from both the listing and single-activity retrieval, and SHALL
accept `importId` as an allow-listed filter and sort column under the same rules as the parent activity identifier: `eq`, `ne`
and `in` apply, the ordering operators and `co`/`nc` are rejected with `invalid_filter_value`, a value that is not an
identifier is rejected with `invalid_filter_value`, and `ne` matches activities that carry no `importId`.

#### Scenario: A completed import is listed by its id

- **WHEN** an import of a bundle with one source table and one pipeline returns `outcome` `completed` and `import_id` `X`, and
  the feed is then requested filtering `importId` `eq` `X`
- **THEN** the result holds the `Table` create, the `Table` update and every `TableColumn` create of the schema, and the
  `Pipeline` create, each carrying `importId` `X`, and nothing else

#### Scenario: Compensation deletes carry the import id

- **WHEN** an import creates its first table and then fails on its second, and the first table is rolled back
- **THEN** the `Delete` activity of the rolled-back table carries the same `importId` as its `Create` activity

#### Scenario: Parent links are unchanged under an import

- **WHEN** an import defines a table's schema
- **THEN** each resulting `TableColumn` activity carries both the import's `importId` and, as its parent, the `Table` activity of
  the same revision

#### Scenario: A hand-made change carries no import id

- **WHEN** a table is created through `POST /v1/tables` while an import is running on another request
- **THEN** that table's activities carry no `importId`

#### Scenario: Activities outside any import are found with ne

- **WHEN** the feed is requested filtering `importId` `ne` an import's id
- **THEN** activities that carry no `importId` are returned, alongside those of other imports

#### Scenario: Ordering an import id is rejected

- **WHEN** the feed is requested filtering `importId` with `lt`
- **THEN** the request is rejected with HTTP 400 and the error code `invalid_filter_value`

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

### Requirement: Point-in-time reads for pipeline and saved query

The system SHALL expose `GET /v1/pipelines/{name}/revision/{revision}` and
`GET /v1/saved-queries/{id}/revision/{revision}`, each returning that resource's own stored fields —
not a resolved view — as they stood at the end of the given revision.

Unlike the live reads of the same resources, these SHALL NOT resolve state belonging to another
entity as of that revision: an enrich pipeline's snapshot SHALL NOT resolve its derived grain key or
its read source's version column, since reconstructing those would require historical catalog state
this capability does not track. A resource whose own history reconstructs correctly but names another
entity SHALL still return successfully, carrying that other entity's identifier verbatim rather than
its resolved current or historical state.

**The transform is not in that category**: it is a member of the pipeline's own declaration, stored in
`spec`, which `pipeline_aud` mirrors, so a snapshot SHALL carry it. A **pre-fold** revision, recorded
before the transform was part of the declaration, SHALL be returned without a `transform` rather than
refused or synthesised (see "Audited resources").

A request for a revision at which the resource did not exist — before its creation, at or after its
deletion, or naming a revision that was never issued — SHALL be answered with HTTP 404 and the error
code `revision_not_found`, matching `GET /v1/tables/{name}/revision/{revision}`'s existing behavior.
A resource whose creation is not itself recorded in the trail SHALL be answered the same way.

#### Scenario: Pipeline snapshot returns the pipeline as it stood at that revision

- **WHEN** a pipeline's declaration is changed and a caller requests its snapshot at the revision
  preceding the change
- **THEN** the previous declaration is returned

#### Scenario: Enrich pipeline snapshot returns its own stored fields only

- **WHEN** an enrich pipeline is created, then updated, and a caller requests its snapshot at the
  revision preceding the update
- **THEN** its fields as they stood before the update are returned, including the `transform` it
  declared at that revision, and the derived grain key and resolved read source present on the live
  read are absent from the snapshot

#### Scenario: A pre-fold revision answers with no transform

- **WHEN** a caller requests the snapshot of a revision recorded before the transform was part of the
  pipeline's declaration
- **THEN** the response is returned successfully and carries no `transform`, rather than being refused
  or answered with a reconstructed transform

#### Scenario: Saved query snapshot returns the saved query as it stood at that revision

- **WHEN** a saved query's body is changed and a caller requests its snapshot at the revision
  preceding the change
- **THEN** the previous body is returned

#### Scenario: Snapshot of a resource whose creation predates the trail is not found

- **WHEN** a caller requests a pipeline or saved query snapshot at a revision, and that resource's own
  creation is not recorded in the trail
- **THEN** the system returns HTTP 404 with error code `revision_not_found`

### Requirement: Audit surface authorization and immutability

In `oidc` security mode the activity endpoints SHALL require `AUDIT_READ`. An unauthenticated caller
SHALL be answered `401`, and an authenticated caller without that privilege `403`, both as the standard
error contract.

`AUDIT_READ` replaces "a caller holding a mapped application role". The derivation that reasoning rested
on — gate the description exactly as the thing it describes — no longer yields a single bar, because
the resources the feed names are now read at four different rungs and per table. A feed entry names a
resource and an author and carries no column value, so it is its own disclosure and gets its own
privilege. The `READ_ONLY_ADMIN` bundle holds `AUDIT_READ`, so an unmodified `roles-mapping` keeps
exactly the reach it has today; the two analyst bundles deliberately do not.

The **table** snapshot endpoint SHALL require `TABLE_GET` on the named table, held globally or granted
on it — which is exactly what the current-state read of that table requires, so a past state is never
more accessible than the present one. Naming the privilege rather than deriving it is the point: the
derivation is withdrawn as a general rule (see **Resource-specific narrowing of the audit surface**)
because a resource's live read is no longer a single authority, and this endpoint is the one place it
still lands on one.

The system SHALL expose no endpoint that creates, edits, or deletes an audit record, revision, or
snapshot.

#### Scenario: Unauthenticated refused

- **WHEN** an unauthenticated caller requests the activity feed in `oidc` mode
- **THEN** the request is refused with HTTP 401 and an `ErrorView` body

#### Scenario: Read-only admin permitted

- **WHEN** an authenticated caller holding the `READ_ONLY_ADMIN` bundle, rather than every privilege,
  requests the activity feed in `oidc` mode
- **THEN** the feed is returned, because that bundle holds `AUDIT_READ`

#### Scenario: Authenticated caller with no application role refused

- **WHEN** an authenticated caller holding no `AUDIT_READ` requests the activity feed in `oidc` mode
- **THEN** the request is refused with HTTP 403 and an `ErrorView` body. A table privilege does not
  confer it: a caller holding `TABLE_READ` on every table is still refused the feed

#### Scenario: Snapshot matches the live read's authorization

- **WHEN** a caller who may read a table's current state requests that table's snapshot at a past
  revision
- **THEN** the snapshot is returned

#### Scenario: No mutating audit endpoint exists

- **WHEN** a caller attempts to write to an activity record
- **THEN** no such route exists and the request is answered with the standard error contract

### Requirement: Resource-specific narrowing of the audit surface

The feed itself SHALL be gated once, at `AUDIT_READ`. Content narrowing within an
otherwise-successful response SHALL then be applied per resource type, for any resource whose own read
authorization is narrower than `AUDIT_READ`.

**The derivation this requirement declared normative is withdrawn, deliberately.** It said the audit
surface for a resource requires exactly what reading that resource's current state requires, so that a
future change to the live read would move this surface with it. That rule cannot survive per-table
authorization: a `Table` activity's live read is now one of four rungs *on a named table*, and a
`Pipeline`'s is `REGISTRY_READ` — so "exactly what the live read requires" is no longer one authority
the feed could be gated at, and a feed that pages in SQL cannot apply a per-row authorization predicate
unless the predicate is part of the query. `AUDIT_READ` is therefore the feed's own bar, and the
trade-off is stated rather than derived: a caller holding `AUDIT_READ` sees that a table it cannot read
changed, and sees the resource name and author with it. Adding the per-table visibility predicate to the
feed's SQL is named as out of scope for the change that introduced privileges, with the reason recorded
there.

`Pipeline` resource type activities SHALL appear in the activity feed for any caller holding
`AUDIT_READ`, SHALL be directly readable by activity id by that caller, and the pipeline snapshot
endpoint SHALL require `REGISTRY_READ` — matching its live read, which is the one place the old
derivation still lands on a single authority. No `Pipeline`-specific content narrowing SHALL be applied
to the feed.

A pipeline snapshot SHALL disclose no more than the live read does. It reconstructs a pipeline's own
stored fields from the audit trail, and the runtime-position fields are `@NotAudited` — excluded from
the trail precisely so a per-tick cursor write raises no revision — so a snapshot carries no scan
position at all. Widening it therefore adds no field beyond the declaration, and requires no
per-caller filtering; see the `pipeline-registry` capability's **A pipeline read carries no row
values** for why the live read needs none either.

A saved query's own reads are owner-scoped: a `common`-scope saved query is visible to any caller
holding `SAVED_QUERY_READ`, while a `personal`-scope saved query is visible only to its owner
(`SavedQueryRepository.findVisible`), with `common` readable by any caller holding `SAVED_QUERY_READ`.
The activity feed and the saved query snapshot endpoint SHALL
apply the same rule to `SavedQuery` resource type activities and snapshots: visible to a
`SAVED_QUERY_PUBLISH` holder unconditionally, to any caller when the saved query's current scope is
`common`, and otherwise only to the caller recorded as the saved query's owner. `SAVED_QUERY_PUBLISH`
is the curator authority over shared definitions, and it reproduces today's unconditional bar exactly:
the `FULL_ADMIN` bundle holds it and `READ_ONLY_ADMIN` does not. This is content narrowing within
an otherwise-successful feed response, not a denial.

This visibility is resolved against the saved query's **current** stored state. A saved query that
has since been deleted has no current state to resolve ownership or scope from, so its historical
activity and snapshot SHALL be visible to a `SAVED_QUERY_PUBLISH` holder only — including to the
caller who was its owner before deletion. This is a deliberate, stated narrowing: reconstructing
whom a deleted saved query was visible to at each point in its history is out of scope for this
capability.

#### Scenario: Read-only admin sees pipeline activities in the feed

- **WHEN** an authenticated caller holding the `READ_ONLY_ADMIN` bundle requests the activity feed with
  no resource-type filter, and a pipeline was recently created
- **THEN** the feed is returned successfully and that `Pipeline` activity appears in it

#### Scenario: Read-only admin may filter the feed to pipeline activities

- **WHEN** an authenticated caller holding the read-only catalog role requests the activity feed
  filtered to resource type `Pipeline`
- **THEN** the feed is returned with the matching pipeline activities, rather than empty or refused

#### Scenario: Full admin sees pipeline activities

- **WHEN** an authenticated caller holding the full administrative role requests the activity feed
  filtered to resource type `Pipeline`
- **THEN** the feed is returned, including that caller's own pipeline activities

#### Scenario: A single pipeline activity is directly readable by a read-only admin

- **WHEN** an authenticated caller holding the read-only catalog role requests a `Pipeline` activity
  by its activity id
- **THEN** the activity is returned with `resourceType` `Pipeline`, rather than the
  `activity_not_found` no-disclosure response

#### Scenario: Pipeline snapshot needs only an application role

- **WHEN** an authenticated caller holding the read-only catalog role, rather than the full
  administrative role, requests a pipeline snapshot at a past revision
- **THEN** the snapshot is returned

#### Scenario: A caller with no application role still cannot read a pipeline activity

- **WHEN** a caller holding no `AUDIT_READ` requests the activity feed or a `Pipeline` activity by id
  in `oidc` mode
- **THEN** the request is refused with HTTP 403 and an `ErrorView` body, unchanged by this widening

#### Scenario: A saved query owner sees their own personal saved query's activity

- **WHEN** the owner of a `personal`-scope saved query requests the activity feed filtered to that
  saved query's resource id
- **THEN** the feed returns that saved query's activity

#### Scenario: A non-owner does not see another caller's personal saved query activity

- **WHEN** an authenticated caller holding the read-only catalog role, who is not the owner of a
  `personal`-scope saved query, requests the activity feed filtered to that saved query's resource id
- **THEN** the feed is returned successfully with that saved query's activity absent from it

#### Scenario: Any role-holding caller sees a common saved query's activity

- **WHEN** an authenticated caller holding the read-only catalog role requests the activity feed
  filtered to a `common`-scope saved query's resource id
- **THEN** the feed returns that saved query's activity

#### Scenario: A deleted saved query's activity is visible only to full admin

- **WHEN** a `personal`-scope saved query is deleted and its former owner, holding `AUDIT_READ` but not
  `SAVED_QUERY_PUBLISH`, requests the activity feed filtered to that saved query's resource id
- **THEN** the feed is returned successfully with that saved query's activity absent from it, and is
  present only when the caller holds `SAVED_QUERY_PUBLISH`
