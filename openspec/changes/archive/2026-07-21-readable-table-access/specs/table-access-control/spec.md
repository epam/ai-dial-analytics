# table-access-control — Delta

## MODIFIED Requirements

### Requirement: Access-management API

The system SHALL expose an API to read and replace a table's access lists at
`/v1/tables/{name}/access`. `GET` SHALL return `{write, modify}` and SHALL be available to any
caller holding an application role (`FULL_ADMIN` or `READ_ONLY_ADMIN`); a caller with no
application role SHALL receive `403 forbidden` on the read. `PUT` SHALL fully replace both sets
from the request body, trimming and de-duplicating role names, and SHALL require `FULL_ADMIN`;
a non-`FULL_ADMIN` caller SHALL receive `403 forbidden` on the replace. A request against a
non-existent table SHALL return `404 not_found`; a request with a blank role name SHALL return
`422 validation_error`.

#### Scenario: Admin reads and replaces access

- **WHEN** an admin `PUT`s `{"write":["analytics-writer","analytics-writer"],"modify":[]}` for a table
- **THEN** the response is `200` with `write = ["analytics-writer"]` (de-duplicated) and `modify = []`

#### Scenario: Read-only admin can read but not replace access

- **WHEN** a `READ_ONLY_ADMIN` caller `GET`s a table's access
- **THEN** the response is `200` with the `{write, modify}` lists
- **AND WHEN** the same caller `PUT`s the table's access
- **THEN** the response is `403 forbidden`

#### Scenario: Roleless caller cannot read access

- **WHEN** a caller with no application role (e.g. authorized only by a per-table provider role)
  `GET`s a table's access
- **THEN** the response is `403 forbidden`

#### Scenario: Access on an unknown table is not found

- **WHEN** an admin requests access for a table that does not exist
- **THEN** the response is `404 not_found`
