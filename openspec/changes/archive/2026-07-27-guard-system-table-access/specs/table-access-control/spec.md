# table-access-control Delta

## MODIFIED Requirements

### Requirement: Per-table access model

The system SHALL store, for each catalog table, a role-based access list co-located on the table
definition: a set of `write` role names and a set of `modify` role names. A `write` role authorizes
row inserts; a `modify` role authorizes schema changes (PATCH) and description updates (PUT). The two
sets are independent — a `write` role does NOT imply `modify`. Role names are raw provider role names
(the same names used as identity-provider `roles-mapping` keys), matched against the caller's provider
roles. Access lists SHALL NOT apply to `system` tables: a `system` table is materialized and fed out of
band, every modifying operation on it is refused regardless of any list, and its stored access lists
SHALL be empty. Deleting a table SHALL remove its access lists.

#### Scenario: Access lists persist per table

- **WHEN** an admin sets a table's `write` roles to `["analytics-writer"]`
- **THEN** reading the table's access returns `write = ["analytics-writer"]` and `modify = []`

#### Scenario: Deleting a table removes its access

- **WHEN** a table that has access roles is deleted from the catalog
- **THEN** the access lists are removed with it

#### Scenario: System tables carry no access roles

- **WHEN** any caller reads the access of a `system` table (e.g. the seeded `dial_usage_log`)
- **THEN** the response is `200` with `write = []` and `modify = []`

### Requirement: Access-management API

The system SHALL expose an API to read and replace a table's access lists at
`/v1/tables/{name}/access`. `GET` SHALL return `{write, modify}` and SHALL be available to any
caller holding an application role (`FULL_ADMIN` or `READ_ONLY_ADMIN`); a caller with no
application role SHALL receive `403 forbidden` on the read. `PUT` SHALL fully replace both sets
from the request body, trimming and de-duplicating role names, and SHALL require `FULL_ADMIN`;
a non-`FULL_ADMIN` caller SHALL receive `403 forbidden` on the replace. A `PUT` against a
`system` table SHALL be refused with `409` and machine code `table_is_system` (the same
contract as every other modifying operation on a system table), even for `FULL_ADMIN`. A
request against a non-existent table SHALL return `404 not_found`; a request with a blank role
name SHALL return `422 validation_error`.

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

#### Scenario: Replacing a system table's access is refused

- **WHEN** a `FULL_ADMIN` caller `PUT`s access lists for a `system` table
- **THEN** the response is `409` with machine code `table_is_system` and the lists are unchanged
