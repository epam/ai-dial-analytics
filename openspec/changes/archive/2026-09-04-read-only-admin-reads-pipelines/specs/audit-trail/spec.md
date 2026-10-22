## MODIFIED Requirements

### Requirement: Resource-specific narrowing of the audit surface

The general rule SHALL be applied per resource type rather than assumed to default to the catalog's
own bar, for any resource whose own read authorization is not simply "any mapped application role":
the audit surface for a resource requires exactly what reading that resource's current state
requires.

A pipeline's own reads (`GET /v1/pipelines`, `GET /v1/pipelines/{name}`), of either kind, require any
mapped application role. Applying the rule above, the audit surface for a `Pipeline` therefore
requires the same: `Pipeline` resource type activities SHALL appear in the activity feed for any
caller holding a mapped application role, SHALL be directly readable by activity id by that caller,
and the pipeline snapshot endpoint SHALL be authorized the same way. No `Pipeline`-specific content
narrowing SHALL be applied to the feed. This requirement previously narrowed `Pipeline` to the full
administrative role; it does so no longer, because the live read it derives that bar from is no longer
full-admin-only. The derivation is what is normative here — a future change to the live read's
authority moves this surface with it, rather than leaving the two to drift.

A pipeline snapshot SHALL disclose no more than the live read does. It reconstructs a pipeline's own
stored fields from the audit trail, and the runtime-position fields are `@NotAudited` — excluded from
the trail precisely so a per-tick cursor write raises no revision — so a snapshot carries no scan
position at all. Widening it therefore adds no field beyond the declaration, and requires no
per-caller filtering; see the `pipeline-registry` capability's **A pipeline read carries no row
values** for why the live read needs none either.

A saved query's own reads are owner-scoped: a `common`-scope saved query is visible to any caller
holding a mapped application role, while a `personal`-scope saved query is visible only to its owner
(`SavedQueryRepository.findVisible`). The activity feed and the saved query snapshot endpoint SHALL
apply the same rule to `SavedQuery` resource type activities and snapshots: visible to the full
administrative role unconditionally, to any caller when the saved query's current scope is `common`,
and otherwise only to the caller recorded as the saved query's owner. This is content narrowing within
an otherwise-successful feed response, not a denial.

This visibility is resolved against the saved query's **current** stored state. A saved query that
has since been deleted has no current state to resolve ownership or scope from, so its historical
activity and snapshot SHALL be visible to the full administrative role only — including to the
caller who was its owner before deletion. This is a deliberate, stated narrowing: reconstructing
whom a deleted saved query was visible to at each point in its history is out of scope for this
capability.

#### Scenario: Read-only admin sees pipeline activities in the feed

- **WHEN** an authenticated caller holding the read-only catalog role requests the activity feed with
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

- **WHEN** a caller that maps to no application role requests the activity feed or a `Pipeline`
  activity by id in `oidc` mode
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

- **WHEN** a `personal`-scope saved query is deleted and its former owner, holding the read-only
  catalog role rather than the full administrative role, requests the activity feed filtered to that
  saved query's resource id
- **THEN** the feed is returned successfully with that saved query's activity absent from it, and is
  present only when the caller holds the full administrative role
