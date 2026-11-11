## MODIFIED Requirements

### Requirement: Every changed entity is its own activity

The system SHALL record one activity per changed entity, naming the entity that changed rather than
a parent it belongs to. The resource types SHALL be `Table`, `TableColumn`, `Pipeline`, `SavedQuery`,
`Dashboard` and `Setting`, and the activity types `Create`, `Update`, and `Delete`. All SHALL be
represented on the wire exactly as spelled here.

A resource id SHALL identify the entity uniquely within its resource type. Because a column is
identified by its table together with its physical name, a `TableColumn` resource id SHALL be those
two joined by a colon, and SHALL be treated as one opaque string. A `Pipeline` resource id SHALL be
its name, a `SavedQuery` or `Dashboard` resource id SHALL be its identifier, and a `Setting` resource id SHALL be the
setting name (for example `sessions-source`).

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

## ADDED Requirements

### Requirement: Instance setting activities

The system SHALL track every write of an instance setting (see `instance-settings`) as an audited resource
of type `Setting`, whose resource id is the setting name. The first successful write of a setting SHALL
record a `Create`, and each later successful write an `Update`, attributed to the writing identity like any
other activity. A `Setting` activity SHALL have no parent: a setting belongs to no other audited entity.

The preserved state SHALL be the setting's stored body together with its `updated_at` and `updated_by`. A
refused write (`403`, `400` or `422`) and a read SHALL record nothing. A write whose body equals the stored
value SHALL still record an `Update`, because it refreshes `updated_at` and `updated_by`, which are part of
the preserved state.

`Setting` activities SHALL appear in the feed, and be readable by activity id, for any caller holding
`AUDIT_READ`, with no `Setting`-specific content narrowing: the setting's own read is the single global
privilege `SETTINGS_READ`, and the trail discloses no more than the stored setting itself does. A
point-in-time read of a setting is not part of this capability's surface: the trail preserves the state, but
no snapshot endpoint for `Setting` is exposed.

#### Scenario: The first write is a create

- **WHEN** `PUT /v1/settings/sessions-source` succeeds on an instance where the setting was never stored
- **THEN** a `Create` activity is recorded with resource type `Setting`, resource id `sessions-source`, no
  parent, and the writing identity as its author

#### Scenario: A replace is an update

- **WHEN** a later `PUT /v1/settings/sessions-source` succeeds
- **THEN** an `Update` activity is recorded for resource type `Setting` and resource id `sessions-source`

#### Scenario: A refused write records nothing

- **WHEN** `PUT /v1/settings/sessions-source` is refused with `422 validation_error`
- **THEN** no activity is recorded

#### Scenario: Filtering the feed by the setting resource type

- **WHEN** a caller holding `AUDIT_READ` requests the feed filtering `resourceType` `eq` `Setting`
- **THEN** only setting activities are returned
