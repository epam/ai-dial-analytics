## MODIFIED Requirements

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
