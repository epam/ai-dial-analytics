## MODIFIED Requirements

### Requirement: Audited resources

The system SHALL track every insert, update, and delete of the catalog state backing tables, and of
the enrichment control plane's rule table, the aggregate pipeline registry, and the saved query
registry:

- the **table definition** row in its entirety — its type, status, write discipline, scan metadata,
  partitioning, TTL, ordering key, an enrichment's grain key, and its role-based `access` lists;
- every **column** of a table — its type, element type, nullability, description, display name, tag,
  ordinal, and its `sensitive` and `heavy` flags;
- every **rule** — its identity, target enrichment, source table, trigger configuration, grouping,
  readiness and membership conditions, filter, input and output bindings, scheduling parameters,
  priority, enabled flag, generation counter, and timestamps;
- every **aggregate pipeline** — its identity, kind, target, input table, filter, aggregate
  declaration, trigger, enabled flag, scan cursor and scheduling state, generation counter, and
  timestamps;
- every **saved query** — its identity, name, description, tag, scope, owner, source, its structured
  or SQL body, time intent, result view, chart configuration, generation counter, and timestamps.

For each tracked change the system SHALL preserve the complete state of the changed entity at that
point, so the resource can be read back as it stood at that revision. A delete SHALL preserve the
entity's final state rather than a row of nulls.

Analytics row writes (`POST /v1/tables/{name}/rows`) are data-plane writes to ClickHouse and SHALL
NOT be audited. The enrichment control plane's evaluators — both the evaluator registry and its
immutable versions — are outside this capability's coverage.

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

- **WHEN** an evaluator is registered, or an evaluator version is created
- **THEN** no activity is recorded for it — a rule, an aggregate pipeline, and a saved query each
  produce activities like any other audited resource; only the evaluator registry and its versions
  remain outside this capability's coverage

#### Scenario: Rule mutations are tracked

- **WHEN** a rule is created through `POST /v1/rules`, changed through `PUT /v1/rules/{id}`, or
  removed through `DELETE /v1/rules/{id}`
- **THEN** a `Create`, `Update`, or `Delete` activity respectively is recorded for resource type
  `Rule`, and its preserved state carries the rule's full configuration at that point

#### Scenario: Pipeline mutations are tracked

- **WHEN** a pipeline is created through `POST /v1/pipelines`, changed through
  `PATCH /v1/pipelines/{name}`, or removed through `DELETE /v1/pipelines/{name}`
- **THEN** a `Create`, `Update`, or `Delete` activity respectively is recorded for resource type
  `Pipeline`, and its preserved state carries the pipeline's full configuration at that point

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

A table that is the target enrichment of a rule cannot be deleted while that rule still exists, so
deleting a table's dependent rule is not a database-level side effect the system must recover after
the fact; the rule is deleted through its own repository, in the same transaction as the table's
deletion, before the table is removed. Deleting such a table SHALL therefore also record a `Delete`
for its dependent rule, sharing the same revision as the table's own `Delete`.

The same applies to a source table that is an aggregate pipeline's target: the pipeline is deleted
through its own repository, in the same transaction as the table's deletion, before the table is
removed, and the system SHALL record a `Delete` for the dependent pipeline sharing that same revision.

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

#### Scenario: Deleting a table's target enrichment records its rule

- **WHEN** a table that is the target enrichment of a rule is deleted
- **THEN** a `Delete` activity is recorded for the table, for each of its columns, and for the rule
  that named it as target enrichment, all sharing one revision

#### Scenario: Deleting a pipeline's target table records the pipeline

- **WHEN** a table that is an aggregate pipeline's target is deleted
- **THEN** a `Delete` activity is recorded for the table, for each of its columns, and for the
  pipeline that named it as target, all sharing one revision

### Requirement: Every changed entity is its own activity

The system SHALL record one activity per changed entity, naming the entity that changed rather than
a parent it belongs to. The resource types SHALL be `Table`, `TableColumn`, `Rule`, `Pipeline`, and
`SavedQuery`, and the activity types `Create`, `Update`, and `Delete`. All SHALL be represented on the
wire exactly as spelled here.

A resource id SHALL identify the entity uniquely within its resource type. Because a column is
identified by its table together with its physical name, a `TableColumn` resource id SHALL be those
two joined by a colon, and SHALL be treated as one opaque string. A `Rule` resource id SHALL be its
identifier, a `Pipeline` resource id SHALL be its name, and a `SavedQuery` resource id SHALL be its
identifier.

A change to a table's ordering key or to an enrichment's grain key SHALL be recorded as an `Update` on
the owning `Table`: both are properties of the definition row and have no identity apart from it.

An ordinary update SHALL record an activity only for the entities it actually changes: a schema patch
naming three columns records three column activities and nothing for the table's other columns.
Likewise, repointing a source table's `version_column` and thereby bumping every rule that reads it
SHALL record one `Update` activity per rule actually bumped, and nothing for rules that read a
different source.

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

#### Scenario: Bulk generation bump records every affected rule

- **WHEN** repointing a source table's `version_column` bumps the generation of three rules that read
  that source, and a fourth rule reading a different source is unaffected
- **THEN** three `Update` activities for resource type `Rule` are recorded, one per bumped rule, and
  none is recorded for the unaffected rule

### Requirement: Child activities link to their parent

An activity for an entity that belongs to another audited entity SHALL carry the activity identifier
of that owning entity's activity in the same revision, when one exists, so a client can present the
children collapsed under the act that caused them. When no such owning activity exists in that
revision, the child's parent identifier SHALL be absent — this is a normal outcome, not an error.

This applies to two parent/child shapes: a `TableColumn` activity's parent is the `Table` activity
for its own table; and, for a `Rule` activity produced by a bulk generation bump, its parent is the
`Table` activity of the source table whose `version_column` repoint caused the bump —
`version_column` is a property of the table definition row itself, so the driving change is always a
`Table` activity, never a `TableColumn` one. A `Rule` activity produced by a direct create, update, or
delete through the rule endpoints has no owning entity in the same revision and therefore no parent.
A `Pipeline` and a `SavedQuery` activity likewise have no parent — neither belongs to another audited
entity.

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

#### Scenario: A directly-mutated rule has no parent

- **WHEN** a rule is created, changed, or deleted through the rule endpoints
- **THEN** the resulting `Rule` activity carries no parent identifier

#### Scenario: Bulk-bumped rules link to their source table's activity

- **WHEN** repointing a source table's `version_column` bumps a rule's generation in the same
  revision as the `Table` activity for that source change
- **THEN** the resulting `Rule` activity carries the identifier of that source's `Table` activity in
  the same revision

## ADDED Requirements

### Requirement: Point-in-time reads for rule, pipeline, and saved query

The system SHALL expose `GET /v1/rules/{id}/revision/{revision}`,
`GET /v1/pipelines/{name}/revision/{revision}`, and
`GET /v1/saved-queries/{id}/revision/{revision}`, each returning that resource's own stored fields —
not a resolved view — as they stood at the end of the given revision.

Unlike the live reads of the same resources, these SHALL NOT resolve state belonging to another
entity as of that revision: a rule's snapshot SHALL NOT resolve its pinned evaluator or its read
source's version column, since reconstructing those would require historical state this capability
does not track for evaluators. A resource whose own history reconstructs correctly but names another
entity SHALL still return successfully, carrying that other entity's identifier verbatim rather than
its resolved current or historical state.

A request for a revision at which the resource did not exist — before its creation, at or after its
deletion, or naming a revision that was never issued — SHALL be answered with HTTP 404 and the error
code `revision_not_found`, matching `GET /v1/tables/{name}/revision/{revision}`'s existing behavior.
A resource whose creation is not itself recorded in the trail SHALL be answered the same way.

#### Scenario: Rule snapshot returns its own stored fields only

- **WHEN** a rule is created, then updated, and a caller requests its snapshot at the revision
  preceding the update
- **THEN** the rule's fields as they stood before the update are returned, and the resolved evaluator
  and derived source/grain members present on the live read are absent from the snapshot

#### Scenario: Pipeline snapshot returns the pipeline as it stood at that revision

- **WHEN** a pipeline's declaration is changed and a caller requests its snapshot at the revision
  preceding the change
- **THEN** the previous declaration is returned

#### Scenario: Saved query snapshot returns the saved query as it stood at that revision

- **WHEN** a saved query's body is changed and a caller requests its snapshot at the revision
  preceding the change
- **THEN** the previous body is returned

#### Scenario: Snapshot of a resource whose creation predates the trail is not found

- **WHEN** a caller requests a rule, pipeline, or saved query snapshot at a revision, and that
  resource's own creation is not recorded in the trail
- **THEN** the system returns HTTP 404 with error code `revision_not_found`

### Requirement: Resource-specific narrowing of the audit surface

The general rule — the audit surface for a resource requires exactly what reading that resource's
current state requires — SHALL be applied per resource type rather than assumed to default to the
catalog's own bar, for any resource whose own read authorization is not simply "any mapped
application role."

An aggregate pipeline's own reads (`GET /v1/pipelines`, `GET /v1/pipelines/{name}`) require the full
administrative role. The activity feed and the pipeline snapshot endpoint SHALL therefore require the
full administrative role for `Pipeline` resource type activities and pipeline snapshots — narrower
than the read-only-admin-permitted bar the rest of the trail uses. A feed request that does not name a
resource type SHALL still return successfully to a read-only-admin caller, with `Pipeline` activities
absent from the result rather than the request being refused; this is a content narrowing, not an
authorization denial, consistent with the feed's existing filter/allow-list model.

A saved query's own reads are owner-scoped: a `common`-scope saved query is visible to any caller
holding a mapped application role, while a `personal`-scope saved query is visible only to its owner
(`SavedQueryRepository.findVisible`). The activity feed and the saved query snapshot endpoint SHALL
apply the same rule to `SavedQuery` resource type activities and snapshots: visible to the full
administrative role unconditionally, to any caller when the saved query's current scope is `common`,
and otherwise only to the caller recorded as the saved query's owner. As with pipelines, this is
content narrowing within an otherwise-successful feed response, not a denial.

This visibility is resolved against the saved query's **current** stored state. A saved query that
has since been deleted has no current state to resolve ownership or scope from, so its historical
activity and snapshot SHALL be visible to the full administrative role only — including to the
caller who was its owner before deletion. This is a deliberate, stated narrowing: reconstructing
whom a deleted saved query was visible to at each point in its history is out of scope for this
capability.

#### Scenario: Read-only admin does not see pipeline activities in the feed

- **WHEN** an authenticated caller holding the read-only catalog role requests the activity feed with
  no resource-type filter, and a pipeline was recently created
- **THEN** the feed is returned successfully, and no `Pipeline` activity appears in it

#### Scenario: Full admin sees pipeline activities

- **WHEN** an authenticated caller holding the full administrative role requests the activity feed
  filtered to resource type `Pipeline`
- **THEN** the feed is returned, including that caller's own pipeline activities

#### Scenario: Pipeline snapshot requires full admin

- **WHEN** an authenticated caller holding the read-only catalog role, rather than the full
  administrative role, requests a pipeline snapshot at a past revision
- **THEN** the request is refused with HTTP 403 and an `ErrorView` body

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

- **WHEN** a `personal`-scope saved query is deleted and its former owner, holding the read-only
  catalog role rather than the full administrative role, requests the activity feed filtered to that
  saved query's resource id
- **THEN** the feed is returned successfully with that saved query's activity absent from it, and is
  present only when the caller holds the full administrative role
