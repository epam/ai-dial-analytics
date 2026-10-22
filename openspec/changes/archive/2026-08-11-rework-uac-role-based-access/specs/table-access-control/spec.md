## MODIFIED Requirements

### Requirement: Per-table authorization evaluation

For the per-table mutating endpoints, a caller SHALL be authorized when it holds `FULL_ADMIN`, or when
one of its raw provider roles appears in the table's access list for the required permission
(`write` for row inserts, `modify` for schema/description changes). Otherwise the request SHALL be
denied with `403 forbidden`. A `FULL_ADMIN` caller SHALL be authorized regardless of the lists. A
table with empty access lists SHALL deny every non-`FULL_ADMIN` caller. A caller that maps to no
application role but carries a listed provider role SHALL still be able to reach that table's
write/modify endpoint; reads, list, query, table create/delete, and access management still require an
application role, so a write-only caller SHALL be `403 forbidden` on catalog reads/lists and queries.

#### Scenario: Matching write role is allowed to write only

- **GIVEN** a table whose `write` list contains role `analytics-writer`
- **WHEN** a caller carrying provider role `analytics-writer` inserts rows
- **THEN** the insert succeeds; and a description update (PUT) by the same caller is `403 forbidden`

#### Scenario: FULL_ADMIN bypasses the lists

- **WHEN** a `FULL_ADMIN` caller writes or modifies a table with empty access lists
- **THEN** the operation is allowed

#### Scenario: Write-only caller can write but not read

- **GIVEN** a caller with no application role whose provider role is in a table's `write` list
- **WHEN** it inserts rows into that table
- **THEN** the insert succeeds
- **AND WHEN** it lists or reads the catalog
- **THEN** the response is `403 forbidden`

### Requirement: Grant evaluation is inert in none mode

In `none` security mode the per-table authorization checks SHALL NOT be enforced (method security is
disabled), exactly as `@FullAdminOnly` is inert; all per-table mutations remain open.

#### Scenario: none mode does not evaluate access

- **WHEN** the service runs in `none` mode
- **THEN** per-table mutating endpoints are reachable without any access-role check

## ADDED Requirements

### Requirement: Per-table access model

The system SHALL store, for each catalog table, a role-based access list co-located on the table
definition: a set of `write` role names and a set of `modify` role names. A `write` role authorizes
row inserts; a `modify` role authorizes schema changes (PATCH) and description updates (PUT). The two
sets are independent — a `write` role does NOT imply `modify`. Role names are raw provider role names
(the same names used as identity-provider `roles-mapping` keys), matched against the caller's provider
roles. The lists MAY apply to any catalog table; a `system` table remains protected from every
modifying operation regardless of its access lists. Deleting a table SHALL remove its access lists.

#### Scenario: Access lists persist per table

- **WHEN** an admin sets a table's `write` roles to `["analytics-writer"]`
- **THEN** reading the table's access returns `write = ["analytics-writer"]` and `modify = []`

#### Scenario: Deleting a table removes its access

- **WHEN** a table that has access roles is deleted from the catalog
- **THEN** the access lists are removed with it

### Requirement: Access-management API

The system SHALL expose an admin-only API to read and replace a table's access lists at
`/v1/tables/{name}/access`. `GET` SHALL return `{write, modify}`. `PUT` SHALL fully replace both sets
from the request body, trimming and de-duplicating role names. All access-management endpoints SHALL
require `FULL_ADMIN`. A request against a non-existent table SHALL return `404 not_found`; a request
with a blank role name SHALL return `422 validation_error`; a non-admin caller SHALL receive
`403 forbidden`.

#### Scenario: Admin reads and replaces access

- **WHEN** an admin `PUT`s `{"write":["analytics-writer","analytics-writer"],"modify":[]}` for a table
- **THEN** the response is `200` with `write = ["analytics-writer"]` (de-duplicated) and `modify = []`

#### Scenario: Non-admin cannot manage access

- **WHEN** a non-`FULL_ADMIN` caller `GET`s or `PUT`s a table's access
- **THEN** the response is `403 forbidden`

#### Scenario: Access on an unknown table is not found

- **WHEN** an admin requests access for a table that does not exist
- **THEN** the response is `404 not_found`

## REMOVED Requirements

### Requirement: Per-table grant model

**Reason**: Replaced by the role-based "Per-table access model" (a JSONB access list co-located on the
table definition); the separate `table_grant` table and `PROJECT`/`CLAIM_RULE` subjects are removed.

### Requirement: Grant-management API

**Reason**: Replaced by the "Access-management API" at `/v1/tables/{name}/access` (GET/PUT).

### Requirement: Grant request validation

**Reason**: The subject model is removed; the access API validates non-blank role names (422).

### Requirement: Caller identity for grant evaluation

**Reason**: Evaluation now reads the caller's raw provider roles from the security context instead of a
project/claims caller-identity view.
