## ADDED Requirements

### Requirement: Per-table grant model

The system SHALL maintain a Postgres-backed set of per-table grants, each a tuple of
`(table, subject, permission)`. A grant's `table` SHALL reference an existing catalog table by
`name`. A grant's `subject` SHALL be one of two kinds: a `PROJECT` subject carrying a non-blank
`project` name, or a `CLAIM_RULE` subject carrying `{claim, operator, value}` where `operator` ∈
{`EQUALS`, `CONTAINS`, `STARTS_WITH`}. A grant's `permission` SHALL be exactly one of `WRITE` or
`MODIFY`; the two are independent (holding `MODIFY` does NOT imply `WRITE`). Grants MAY target any
catalog table, including seeded tables such as `dial_usage_log`; there is no system/custom
distinction. Deleting a table SHALL cascade-delete all of its grants.

#### Scenario: Grant persists a project subject with a permission

- **WHEN** an admin creates a grant for a table with a `PROJECT` subject `"acme"` and permission `WRITE`
- **THEN** the grant is persisted against that table and is returned when the table's grants are listed

#### Scenario: Grant persists a claim-rule subject

- **WHEN** an admin creates a grant with a `CLAIM_RULE` subject `{claim:"groups", operator:CONTAINS, value:"analytics-writers"}` and permission `MODIFY`
- **THEN** the grant is persisted with its claim, operator, and value, and is returned when the table's grants are listed

#### Scenario: Deleting a table removes its grants

- **WHEN** a table that has grants is deleted from the catalog
- **THEN** all grants targeting that table are removed

### Requirement: Grant-management API

The system SHALL expose an admin-only grant-management API scoped to a table:
`GET /v1/tables/{name}/grants` returns the table's grants; `POST /v1/tables/{name}/grants` creates a
grant from `{subject, permission}`; `DELETE /v1/tables/{name}/grants/{grantId}` revokes a grant. All
three endpoints SHALL require the `FULL_ADMIN` authority in `oidc` mode. Grant management against an
unknown table SHALL return 404. Revoking an unknown grant SHALL return 404. A grant that duplicates
an existing `(table, subject, permission)` tuple SHALL be rejected with 409.

#### Scenario: Admin creates a grant

- **WHEN** a `FULL_ADMIN` caller `POST`s a valid grant to `/v1/tables/{name}/grants` for an existing table
- **THEN** the response is 201 with the created grant including its assigned identifier

#### Scenario: Admin lists a table's grants

- **WHEN** a `FULL_ADMIN` caller `GET`s `/v1/tables/{name}/grants`
- **THEN** the response lists every grant targeting that table

#### Scenario: Admin revokes a grant

- **WHEN** a `FULL_ADMIN` caller `DELETE`s `/v1/tables/{name}/grants/{grantId}` for an existing grant
- **THEN** the grant is removed and the response is 204

#### Scenario: Non-admin cannot manage grants

- **WHEN** a caller lacking `FULL_ADMIN` invokes any grant-management endpoint
- **THEN** the request is rejected with 403 and the `ErrorView` body and error code `forbidden`

#### Scenario: Grant management on an unknown table is rejected

- **WHEN** a grant-management endpoint is invoked for a table name not in the catalog
- **THEN** the response is 404 with error code `not_found`

#### Scenario: Duplicate grant is rejected

- **WHEN** a `POST` would create a grant with the same `(table, subject, permission)` as an existing grant
- **THEN** the request is rejected with 409

### Requirement: Grant request validation

The system SHALL validate every grant create request before persisting, rejecting invalid requests
with HTTP 422. Validation SHALL require: a `PROJECT` subject carries a non-blank `project`; a
`CLAIM_RULE` subject carries a non-blank `claim`, a non-blank `value`, and an `operator` in the
accepted vocabulary (`EQUALS`, `CONTAINS`, `STARTS_WITH`); and `permission` is exactly one of `WRITE`
or `MODIFY`. A subject that mixes both project and claim-rule fields, or that supplies neither, SHALL
be rejected.

#### Scenario: Claim rule with an unknown operator is rejected

- **WHEN** a grant `POST` supplies a `CLAIM_RULE` subject whose `operator` is not one of the accepted values
- **THEN** the request is rejected with HTTP 422

#### Scenario: Project subject with a blank project is rejected

- **WHEN** a grant `POST` supplies a `PROJECT` subject with a blank `project`
- **THEN** the request is rejected with HTTP 422

#### Scenario: Subject supplying neither project nor claim rule is rejected

- **WHEN** a grant `POST` supplies a subject with neither a `project` nor a complete claim rule
- **THEN** the request is rejected with HTTP 422

### Requirement: Caller identity for grant evaluation

To evaluate grants at request time, the system SHALL retain the authenticated caller's identity on
the security context beyond the principal name and mapped roles. For a project-key caller the identity
SHALL expose the caller as a project subject carrying its `project`; for a claims-bearing caller
(a Bearer JWT, an opaque token, or a JWT-rooted API key) the identity SHALL expose the caller's raw
identity claims for claim-rule evaluation. A project-key caller carries no user claims and therefore
SHALL match only `PROJECT` grants; a claims-bearing caller carries no project and therefore SHALL
match only `CLAIM_RULE` grants.

#### Scenario: Project-key caller exposes its project

- **WHEN** a request is authenticated with a project key for project `"acme"`
- **THEN** grant evaluation sees a project subject `"acme"` and can match `PROJECT` grants for `"acme"`

#### Scenario: Claims-bearing caller exposes its claims

- **WHEN** a request is authenticated with a Bearer JWT (or JWT-rooted key) carrying identity claims
- **THEN** grant evaluation sees those claims and can match `CLAIM_RULE` grants against them

#### Scenario: Project key does not match claim rules

- **WHEN** a project-key caller invokes a per-table mutating endpoint that is governed only by a `CLAIM_RULE` grant
- **THEN** no grant matches and access is denied unless the caller is `FULL_ADMIN`

### Requirement: Per-table authorization evaluation

The system SHALL authorize the per-table mutating endpoints by evaluating, in order: a caller holding
`FULL_ADMIN` SHALL be allowed regardless of grants; otherwise the system SHALL load the target table's
grants and allow the request when at least one grant matches the caller's subject AND covers the
required permission, and SHALL otherwise deny with 403. A `PROJECT` grant matches when the caller is a
project subject whose `project` equals the grant's `project`. A `CLAIM_RULE` grant matches when the
named `claim` is present on the caller's identity and satisfies the grant's operator against its value:
`EQUALS` (an exact match, or membership when the claim is list-valued), `CONTAINS` (substring, or
membership when list-valued), and `STARTS_WITH` (prefix). The required permission SHALL be `WRITE` for
row writes and `MODIFY` for schema PATCH and description update.

#### Scenario: Matching WRITE grant permits a row write

- **WHEN** a non-admin caller whose subject matches a `WRITE` grant on the table `POST`s rows
- **THEN** the write is authorized and processed

#### Scenario: WRITE grant does not permit schema modification

- **WHEN** a caller holds only a `WRITE` grant on a table and attempts `PATCH /v1/tables/{name}/schema`
- **THEN** the request is denied with 403 (WRITE does not cover MODIFY)

#### Scenario: Matching MODIFY grant permits schema modification

- **WHEN** a non-admin caller whose subject matches a `MODIFY` grant on the table `PATCH`es its schema
- **THEN** the modification is authorized and processed

#### Scenario: No matching grant is denied

- **WHEN** a non-admin caller with no matching grant invokes a per-table mutating endpoint
- **THEN** the request is denied with 403 and the `ErrorView` body and error code `forbidden`

#### Scenario: Claim-rule operator is evaluated against caller claims

- **WHEN** a `CLAIM_RULE` grant `{claim:"groups", operator:CONTAINS, value:"writers"}` is set on a table and the caller's `groups` claim contains `"writers"`
- **THEN** the grant matches and the corresponding action is authorized

#### Scenario: FULL_ADMIN bypasses grants

- **WHEN** a `FULL_ADMIN` caller invokes a per-table mutating endpoint on a table with no grants
- **THEN** the request is authorized

### Requirement: Grant evaluation is inert in none mode

In `none` security mode the system SHALL NOT enforce grant evaluation, exactly as method-level
`FULL_ADMIN` authorization is not enforced in `none` mode. All per-table mutating endpoints SHALL
remain open when the security mode is `none`.

#### Scenario: none mode leaves per-table mutations open

- **WHEN** the security mode is `none` and any caller invokes a per-table mutating endpoint
- **THEN** the request is processed without grant evaluation
