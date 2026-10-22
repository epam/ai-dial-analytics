## ADDED Requirements

### Requirement: Per-table mutations honor role-based access

The per-table mutating endpoints SHALL be authorized by `FULL_ADMIN` or by a matching per-table access
role as defined by the `table-access-control` capability: row writes (`POST /v1/tables/{name}/rows`)
require a `write` role, and schema PATCH (`PATCH /v1/tables/{name}/schema`) and description update
(`PUT /v1/tables/{name}`) require a `modify` role. Table create (`POST /v1/tables`) and delete
(`DELETE /v1/tables/{name}`) SHALL remain `FULL_ADMIN`-only and SHALL NOT be authorizable by any access
role. `system`-owned table protection SHALL take precedence: a `system` table SHALL reject every
modifying operation regardless of access roles.

#### Scenario: Access role authorizes a row write

- **GIVEN** a non-system table whose `write` access list contains the caller's provider role
- **WHEN** the caller inserts rows
- **THEN** the write succeeds

#### Scenario: Delete is never authorized by an access role

- **WHEN** a non-`FULL_ADMIN` caller holding a `modify` access role deletes the table
- **THEN** the service responds `403 forbidden`

#### Scenario: System table rejects modification despite access roles

- **GIVEN** a `system` table with a `write` access role for the caller's provider role
- **WHEN** the caller inserts rows
- **THEN** the service rejects the modification (system-owned protection)
