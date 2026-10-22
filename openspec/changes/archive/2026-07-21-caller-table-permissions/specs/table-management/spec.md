# table-management — Delta

## ADDED Requirements

### Requirement: Table responses carry caller-effective permissions

Each table returned by `GET /v1/tables` (every list item) and `GET /v1/tables/{name}` SHALL carry a
`permissions` object with boolean `write` and `modify` fields describing what the calling identity
may do with that table, computed per the caller-effective permissions rules of
[table-access-control](../table-access-control/spec.md). Both fields SHALL always be present
booleans (never null/absent). Mutation responses that return the table representation
(create, description update, schema PATCH) SHALL carry the same object.

#### Scenario: List items carry permissions

- **WHEN** `GET /v1/tables` is called
- **THEN** every list item carries a `permissions` object with boolean `write` and `modify` fields

#### Scenario: Single get carries permissions

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response carries a `permissions` object with boolean `write` and `modify` fields

#### Scenario: Create response reports the creator's permissions

- **WHEN** an authorized caller creates a table via `POST /v1/tables`
- **THEN** the 201 response carries `permissions.write = true` and `permissions.modify = true`
  (the creator is `FULL_ADMIN` and the new table is not `system`)
