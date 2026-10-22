## ADDED Requirements

### Requirement: A column a row policy references is protected against drop and rename

A `PATCH /v1/tables/{name}/schema` that drops or renames a column referenced by the filter of any row
policy stored on that table (see [table-access-control](../table-access-control/spec.md)) SHALL be
refused, before any DDL is rendered, with HTTP 409 and error code `column_in_row_policy`, naming the
column and the roles whose policies reference it. The refusal SHALL hold whoever the caller is, and in
`none` mode too, because a policy over a missing column would fail every read of the table once it is
enforced. Removing or rewriting those policies first SHALL make the operation possible.

The same refusal SHALL apply to `POST /v1/tables/{name}/schema` on a table that is not yet materialized
(`PENDING` or `FAILED`), which replaces the whole column set: a table whose CREATE failed keeps its
columns and can already carry policies, so a re-defined schema that no longer exposes a column a policy
references SHALL be refused `409 column_in_row_policy`, naming the columns and roles, before any column
is replaced or any DDL is issued.

A column referenced by no policy SHALL remain droppable and renamable under the existing rules.

#### Scenario: Dropping a policy column is refused

- **GIVEN** a policy for role `X` filtering `dial_usage_log` on `project_id`
- **WHEN** a caller holding `TABLE_MODIFY` patches the schema to drop or rename `project_id`
- **THEN** the request is refused `409 column_in_row_policy` naming `project_id` and `X`, and no
  `ALTER` is issued

#### Scenario: Removing the policy releases the column

- **WHEN** the policy for `X` is deleted and the same patch is repeated against a column no other
  policy references
- **THEN** the patch is applied under the existing schema rules

#### Scenario: Re-defining a failed table's schema without a policy column is refused

- **GIVEN** a `FAILED` table with column `c` and a policy for role `X` filtering on `c`
- **WHEN** a caller posts the table's schema again without `c`
- **THEN** the request is refused `409 column_in_row_policy` naming `c` and `X`, the stored columns are
  unchanged, and no `CREATE` is issued

## MODIFIED Requirements

### Requirement: Table responses carry caller-effective permissions

Each table returned by `GET /v1/tables` (every list item) and `GET /v1/tables/{name}` SHALL carry a
`permissions` object with boolean `get`, `read`, `sensitive`, `write`, `modify` and `rows_restricted`
fields describing what the calling identity may do with that table, computed per the
caller-effective permissions rules of
[table-access-control](../table-access-control/spec.md). All six fields SHALL always be present
booleans (never null/absent). The object grew from two flags to five with the per-table grants, and to
six with row policies; the earlier names keep their spelling and meaning, so a client reading only those
is unaffected. `rows_restricted` reports whether row policies confine the caller's reads of the table.
`sensitive` reports the clearance (see `table-access-control`) and replaces the former `read_sensitive`
flag, which SHALL NOT be emitted.
Mutation responses that return the table representation (create, description update, schema PATCH)
SHALL carry the same object.

#### Scenario: List items carry permissions

- **WHEN** `GET /v1/tables` is called
- **THEN** every list item carries a `permissions` object with all six boolean fields

#### Scenario: Single get carries permissions

- **WHEN** `GET /v1/tables/{name}` is called for an existing definition
- **THEN** the response carries a `permissions` object with all six boolean fields

#### Scenario: Create response reports the creator's permissions

- **WHEN** an authorized caller creates a table via `POST /v1/tables`
- **THEN** the 201 response reports the creator's own effective permissions on the new table — `get`,
  `read`, `sensitive`, `write` and `modify` all `true` for a caller holding every table-scoped
  privilege, and `false` for any the caller does not hold, since `TABLE_CREATE` alone confers none of
  them — and `rows_restricted = false`, because a new table carries no row policy
