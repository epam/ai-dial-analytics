## Purpose

Change tracking for the ADAS table catalog: every mutation of a table definition or a table column is
preserved as a full historical snapshot attributed to the identity that made it, and is exposed as a
filtered activity feed plus point-in-time reads of a table as it stood at any past revision.

## ADDED Requirements

### Requirement: Audited resources

The system SHALL track every insert, update, and delete of the catalog state backing tables:

- the **table definition** row in its entirety — its type, status, write discipline, scan metadata,
  partitioning, TTL, ordering key, an enrichment's grain key, and its role-based `access` lists;
- every **column** of a table — its type, element type, nullability, description, display name, tag,
  ordinal, and its `sensitive` and `heavy` flags.

For each tracked change the system SHALL preserve the complete state of the changed entity at that
point, so the resource can be read back as it stood at that revision. A delete SHALL preserve the
entity's final state rather than a row of nulls.

Analytics row writes (`POST /v1/tables/{name}/rows`) are data-plane writes to ClickHouse and SHALL
NOT be audited. The enrichment control plane — evaluators and rules — aggregate pipelines, and saved
queries are outside this capability's coverage.

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
- **THEN** an `Update` activity for that table is recorded and the snapshot carries the new lists

#### Scenario: Sensitivity change is tracked

- **WHEN** a schema patch marks a column `sensitive`
- **THEN** an `Update` activity for that `TableColumn` is recorded and the snapshot carries the flag

#### Scenario: Deletion preserves final state

- **WHEN** an audited resource is deleted
- **THEN** the preserved state for that change is the resource's state immediately before deletion

#### Scenario: Row writes are not tracked

- **WHEN** rows are written through `POST /v1/tables/{name}/rows`
- **THEN** no activity is recorded

#### Scenario: Rules and pipelines are not tracked

- **WHEN** an enrichment rule or an aggregate pipeline is created, changed, or deleted
- **THEN** no activity is recorded for it

### Requirement: Dependent removals are recorded

When deleting a resource also removes audited state that belongs to it, the system SHALL record a
`Delete` activity, and preserve the final state, for **every** audited entity that ceases to exist —
not only for the resource the caller named.

Deleting a table SHALL therefore record a `Delete` for the table and for each of its columns, all
sharing the one revision of the deleting transaction. An enrichment's grain and a table's ordering key
are properties of the definition row, so they are preserved by that row's own `Delete` and need no
record of their own.

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

### Requirement: Every changed entity is its own activity

The system SHALL record one activity per changed entity, naming the entity that changed rather than
a parent it belongs to. The resource types SHALL be `Table` and `TableColumn`, and the activity types
`Create`, `Update`, and `Delete`. Both SHALL be represented on the wire exactly as spelled here.

A resource id SHALL identify the entity uniquely within its resource type. Because a column is
identified by its table together with its physical name, a `TableColumn` resource id SHALL be those
two joined by a colon, and SHALL be treated as one opaque string.

A change to a table's ordering key or to an enrichment's grain key SHALL be recorded as an `Update` on
the owning `Table`: both are properties of the definition row and have no identity apart from it.

An ordinary update SHALL record an activity only for the entities it actually changes: a schema patch
naming three columns records three column activities and nothing for the table's other columns.

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

An activity for a `TableColumn` SHALL carry the activity identifier of the `Table` activity for its own
table in the same revision, when one exists, so a client can present the children collapsed under the
act that caused them. When no such `Table` activity exists in that revision, the child's parent
identifier SHALL be absent — this is a normal outcome, not an error.

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

### Requirement: Historical table snapshots

The system SHALL expose `GET /v1/tables/{name}/revision/{revision}` returning the table as it stood
at the end of the given revision, in the same response shape as the current-state read
(`GET /v1/tables/{name}`). The snapshot SHALL include the columns, ordering key, and grain that
belonged to the table at that revision, and SHALL exclude a column that had been dropped by then.

Every member of that response describes the table **as of the requested revision**, including the
caller's effective permissions, which are resolved from the access lists the table had then. A snapshot
therefore answers "what could this caller have done then", not "what may they do now", and SHALL NOT be
used to decide whether an action is currently permitted — the live read answers that.

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

### Requirement: Audit surface authorization and immutability

In `oidc` security mode the activity endpoints SHALL require exactly what reading the catalog requires:
a caller holding a mapped application role. An unauthenticated caller SHALL be answered `401`, and an
authenticated caller without such a role `403`, both as the standard error contract.

They SHALL NOT require the `FULL_ADMIN` role specifically. The feed describes resources whose current
state is readable at the lower bar, so gating the description more tightly than the thing it describes
would be inconsistent — a reader who may see a table's columns and access lists may see that they
changed.

The snapshot endpoint SHALL require exactly what the current-state read of the same table requires,
so that a past state is never more accessible than the present one.

The system SHALL expose no endpoint that creates, edits, or deletes an audit record, revision, or
snapshot.

#### Scenario: Unauthenticated refused

- **WHEN** an unauthenticated caller requests the activity feed in `oidc` mode
- **THEN** the request is refused with HTTP 401 and an `ErrorView` body

#### Scenario: Read-only admin permitted

- **WHEN** an authenticated caller holding the read-only catalog role, rather than the full
  administrative role, requests the activity feed in `oidc` mode
- **THEN** the feed is returned

#### Scenario: Authenticated caller with no application role refused

- **WHEN** an authenticated caller who maps to no application role requests the activity feed in
  `oidc` mode
- **THEN** the request is refused with HTTP 403 and an `ErrorView` body, exactly as reading a table
  would be

#### Scenario: Snapshot matches the live read's authorization

- **WHEN** a caller who may read a table's current state requests that table's snapshot at a past
  revision
- **THEN** the snapshot is returned

#### Scenario: No mutating audit endpoint exists

- **WHEN** a caller attempts to write to an activity record
- **THEN** no such route exists and the request is answered with the standard error contract
