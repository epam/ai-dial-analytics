## ADDED Requirements

### Requirement: A row policy may be written as a SQL condition

`PUT /v1/tables/{name}/row-policies/{role}` SHALL accept `{"where": "<condition>"}` as an alternative to
`{"filter": <filter node>}`. The condition is the text that would follow `WHERE` in a SQL statement over
the table, in the SQL subset the ad-hoc SQL surface accepts (see
[rest-sql-endpoint](../rest-sql-endpoint/spec.md)). Exactly one of `where` and `filter` SHALL be given: a
body carrying both, or neither, SHALL be refused `422 validation_error`.

The system SHALL translate the condition into a filter node and from there treat the policy exactly as one
written as `filter`: the same validation against the table's own columns, the same refusals, the same size
limit, the same storage and the same enforcement. The condition text SHALL NOT be stored, SHALL NOT be sent
to the data store, and SHALL NOT be returned: the response and every later read of the policy carry the
stored `filter`.

The condition SHALL be resolved against every column the table stores, sensitive columns included,
whatever the writing caller may itself see or read.

A caller value SHALL be written in the condition as `caller.user_hash` or `caller.project`, exactly, and
SHALL become the `param` of that name, subject to the placement rule of "Caller values in row policies". A
string literal in the condition SHALL remain a literal whatever it spells, including the text of a caller
value's name.

A condition that does not parse, does not validate against the table, or uses a construct the SQL surface
cannot express as a single filter SHALL be refused `422 validation_error` with the reason. A table that is
not a queryable entity on its own, such as an enrichment, SHALL refuse `where` with `422 validation_error`;
its policies are written as `filter`.

#### Scenario: A condition is stored as its filter

- **WHEN** a caller holding `TABLE_GRANT` `PUT`s `{"where": "project_id IN ('X-PRJ-1', 'X-PRJ-2')"}` for
  role `X`
- **THEN** the response is `200` with a `filter` equal to the one the same policy written as `filter`
  stores, and a caller carrying `X` sees only the rows of those two projects

#### Scenario: A caller value in a condition

- **WHEN** a policy is written as `{"where": "user_hash = caller.user_hash"}`
- **THEN** the stored filter compares `user_hash` with the `param` `caller.user_hash`, and each caller
  reads its own rows

#### Scenario: A literal spelling a caller value stays a literal

- **WHEN** a policy is written as `{"where": "user_hash = 'caller.user_hash'"}`
- **THEN** the stored filter compares `user_hash` with the string `caller.user_hash`, and no caller's value
  is substituted

#### Scenario: Both forms at once are refused

- **WHEN** a body carries both `where` and `filter`
- **THEN** the request is refused `422 validation_error` and no policy is stored

#### Scenario: A subquery in a condition is refused

- **WHEN** a policy is written as `{"where": "project_id IN (SELECT project_id FROM other_table)"}`
- **THEN** the request is refused `422 validation_error` and no policy is stored

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
request type — malformed JSON, or a member other than `filter` and `where` — SHALL be `400 bad_request`, as
on every endpoint behind the strict request mapper. A
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
