## MODIFIED Requirements

### Requirement: Row-policy management API

The system SHALL expose the row policies of a table at `/v1/tables/{name}/row-policies`, every method
requiring `TABLE_GRANT`, because a policy discloses which provider roles exist and what each may see:

- `GET /v1/tables/{name}/row-policies` SHALL return `{"policies": {"<role>": <policy>, ...}}`, an empty
  object when the table has none.
- `PUT /v1/tables/{name}/row-policies/{role}` SHALL create or replace that role's policy and return it.
  It is idempotent: repeating it changes nothing, and records no audit revision.
- `DELETE /v1/tables/{name}/row-policies/{role}` SHALL remove that role's policy and answer `204`.

A caller without `TABLE_GRANT` SHALL receive `403 forbidden`. An unknown table SHALL be `404 not_found`,
and so SHALL a `DELETE` naming a role that has no policy on the table. A body that does not bind to the
request type — malformed JSON, or a member other than `filter` — SHALL be `400 bad_request`, as on every endpoint behind the strict request mapper. A
body that binds but whose shape or meaning is invalid, and an invalid role, SHALL be `422 validation_error`
naming the offending member. Every change SHALL be recorded in the audit trail as
a change to the table definition, readable as `row_policies` on the table's revision snapshot by a caller
holding `TABLE_GRANT` (see [audit-trail](../audit-trail/spec.md), "Historical table snapshots").

#### Scenario: Create, read and remove a policy

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s a policy for role `X`, then `GET`s the table's row
  policies, then `DELETE`s `X`'s policy
- **THEN** the `PUT` returns `200` with the stored policy, the `GET` lists it under `X`, the `DELETE`
  answers `204`, and a further `GET` returns `{"policies": {}}`

#### Scenario: A caller without TABLE_GRANT cannot read policies

- **WHEN** a caller holding `TABLE_READ` but not `TABLE_GRANT` `GET`s a table's row policies
- **THEN** the response is `403 forbidden`

#### Scenario: Removing an absent policy is not found

- **WHEN** a caller holding `TABLE_GRANT` `DELETE`s the policy of a role that has none on the table
- **THEN** the response is `404 not_found`

#### Scenario: Repeating a PUT records no revision

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s the same policy for role `X` twice
- **THEN** both responses are `200` with the stored policy, and only the first records an `Update`
  activity for the table

#### Scenario: A bundle name is not a policy role

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s a policy for role `READ_ONLY_ANALYTIC`
- **THEN** the request is refused `422 validation_error`
