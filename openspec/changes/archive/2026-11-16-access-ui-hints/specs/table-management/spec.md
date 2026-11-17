## MODIFIED Requirements

### Requirement: Table responses carry caller-effective permissions

Each table returned by `GET /v1/tables` (every list item) and `GET /v1/tables/{name}` SHALL carry a
`permissions` object with boolean `get`, `read`, `sensitive`, `write`, `modify`, `grant`, `rows_restricted` and
`columns_restricted` fields describing what the calling identity may do with that table, computed per the
caller-effective permissions rules of
[table-access-control](../table-access-control/spec.md). All eight fields SHALL always be present
booleans (never null/absent). The object grew from two flags to five with the per-table grants, to
six with row policies, to seven with column policies, and to eight with `grant`; the earlier names keep their spelling and meaning, so a
client reading only those is unaffected. `rows_restricted` reports whether row policies confine the caller's reads of
the table, and `columns_restricted` whether column policies hide any of its columns from the caller.
`grant` reports whether the caller may manage the table's access configuration.
`sensitive` reports the clearance (see `table-access-control`) and replaces the former `read_sensitive`
flag, which SHALL NOT be emitted.
Mutation responses that return the table representation (create, description update, schema PATCH)
SHALL carry the same object.

#### Scenario: List items carry permissions

- **WHEN** `GET /v1/tables` is called
- **THEN** every list item carries a `permissions` object with all eight boolean fields

#### Scenario: Single get carries permissions

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response carries a `permissions` object with all eight boolean fields

#### Scenario: Create response reports the creator's permissions

- **WHEN** an authorized caller creates a table via `POST /v1/tables`
- **THEN** the 201 response reports the creator's own effective permissions on the new table — `get`,
  `read`, `sensitive`, `write` and `modify` all `true` for a caller holding every table-scoped
  privilege, and `false` for any the caller does not hold, since `TABLE_CREATE` alone confers none of
  them; `grant` is `true` exactly when the creator holds `TABLE_GRANT` — and `rows_restricted = false` and
  `columns_restricted = false`, because a new table carries no entry
