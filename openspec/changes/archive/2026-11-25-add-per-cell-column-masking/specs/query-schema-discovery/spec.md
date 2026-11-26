## ADDED Requirements

### Requirement: A masked field is marked

Each field of `GET /v1/queries/entities/schema/{name}` SHALL carry a boolean `masked`: `true` when the field's column
is masked for the caller (see [table-access-control](../table-access-control/spec.md), "Column visibility by caller
role"), or the field is a computed field that is masked, and `false` otherwise — always `false` for a caller with no
governing role on the owning table and in `none` security mode. A masked field SHALL be listed with every other member
as for a visible field. `masked` SHALL tell the client that the field's value can be absent on some rows for lack of
permission, and that a query result may mark its cells (see [structured-query](../structured-query/spec.md),
"Masked inputs are reported per output cell"). It SHALL NOT disclose the policies or say on which rows the field is
absent.

#### Scenario: A masked field is listed and marked

- **GIVEN** a caller whose governing roles on `dial_usage_log` are `R`, with `read`, `rows` admitting
  `project_id = 'P1'` and `columns` `null`, and `U`, with `read`, `rows` admitting `project_id = 'P2'` and `columns`
  `["project_id", "deployment"]`
- **WHEN** it calls `GET /v1/queries/entities/schema/dial_usage_log`
- **THEN** `project_id` and `deployment` carry `masked: false`, and every other field of the source it may see carries
  `masked: true`

#### Scenario: A single-role caller sees no masked field

- **WHEN** a caller with one governing role on the table, and no rows-only entry whose `columns` list narrows it, calls
  the schema endpoint
- **THEN** every field it lists carries `masked: false`
