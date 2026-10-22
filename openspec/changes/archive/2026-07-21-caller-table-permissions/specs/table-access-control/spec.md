# table-access-control — Delta

## ADDED Requirements

### Requirement: Caller-effective permissions reporting

The system SHALL report, on every table read surface (`GET /v1/tables` list items and
`GET /v1/tables/{name}`), the calling identity's effective per-table permissions as a
`permissions` object with boolean `write` and `modify` fields. The reported flags SHALL agree with
what per-table authorization enforcement and system-table protection would decide for that caller:

- a `system` table SHALL report `write=false, modify=false` for every caller, including
  `FULL_ADMIN` (all modifying operations are refused on system tables);
- otherwise a `FULL_ADMIN` caller SHALL see `write=true, modify=true`;
- otherwise each flag SHALL be `true` if and only if one of the caller's raw provider roles
  appears in the table's access list for that permission (the two flags are independent);
- in `none` security mode both flags SHALL be `true` for non-`system` tables (no authorization is
  enforced).

The reporting SHALL share its role-matching implementation with the enforcement evaluation so the
two cannot diverge. Reporting SHALL NOT change enforcement behavior, and the admin-only
access-management API SHALL be unchanged.

#### Scenario: Matching write role is reported

- **GIVEN** a table whose `write` list contains provider role `analytics-writer` and whose
  `modify` list is empty
- **WHEN** a caller carrying provider role `analytics-writer` (and not `FULL_ADMIN`) reads the
  table via `GET /v1/tables/{name}` or finds it in `GET /v1/tables`
- **THEN** the table carries `permissions.write = true` and `permissions.modify = false`

#### Scenario: FULL_ADMIN sees full permissions on a non-system table

- **WHEN** a `FULL_ADMIN` caller reads a non-`system` table with empty access lists
- **THEN** the table carries `permissions.write = true` and `permissions.modify = true`

#### Scenario: No matching role is reported as no permissions

- **WHEN** a non-`FULL_ADMIN` caller whose provider roles appear in none of a table's access lists
  reads that table
- **THEN** the table carries `permissions.write = false` and `permissions.modify = false`

#### Scenario: System table reports no permissions even for an admin

- **WHEN** a `FULL_ADMIN` caller reads a `system` table (e.g. the seeded `dial_usage_log`)
- **THEN** the table carries `permissions.write = false` and `permissions.modify = false`

#### Scenario: none mode reports full permissions

- **WHEN** the service runs in `none` security mode and any caller reads a non-`system` table
- **THEN** the table carries `permissions.write = true` and `permissions.modify = true`
