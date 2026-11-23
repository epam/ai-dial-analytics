## MODIFIED Requirements

### Requirement: Sensitive-column re-check on scan

Independently of the target-enrichment access grant, the scan SHALL re-apply the sensitive-column
declaration (see [enrichment-registry](../enrichment-registry/spec.md)) against the **scanning caller's**
identity: if the scan's projection includes a `sensitive`-flagged column of the pipeline's **read source**
and the caller may not see them (no `TABLE_SENSITIVE` on the table that owns the column, and
`security.mode` is not `none`), the
scan SHALL be rejected with HTTP 403 `sensitive_column_not_entitled`. This holds even though the stored
declaration was registered by an entitled author, so a runner without that entitlement cannot read
sensitive data,
and it holds whether the read source is the target enrichment's `source_table` or a declared one.

The projection judged for the clearance SHALL include every column the scan's page returns (see
[table-access-control](../table-access-control/spec.md), "Pipeline and export paths refuse a hidden column"): besides
the bound columns, the read source's `identity_column` and `version_column`, the target's grain key, a group
pipeline's group key and, for a row-trigger pipeline whose target is aligned with the source it reads, the source's
partition column, whose value every row returns as `__partition_v`. A source may declare its `partition_by` column
`sensitive`, so a caller without the clearance on such a source is refused `sensitive_column_not_entitled` when it scans
for a target aligned with it, even when no binding projects that column.

The refusal SHALL carry that code **however the sensitive column is reached** — projected directly via a
bound column, or referenced by the pipeline's `filter` or `trigger.ready_when.signal`. A non-entitled caller
cannot even parse the stored predicate, because the SQL front end validates against a schema from which
sensitive columns are hidden for that caller; such a parse failure SHALL be re-attributed to
`sensitive_column_not_entitled` rather than reported as a malformed declaration (422), which would both mislead
and misstate whose problem it is.

A column a column policy hides from the scanning caller (see
[table-access-control](../table-access-control/spec.md), "Column visibility by caller role" and "Pipeline and export
paths refuse a hidden column") is hidden from its schema view the same way, and SHALL be refused with HTTP 403
`column_not_entitled` at the same point, before any column is checked for the clearance. A parse failure SHALL
therefore be re-attributed to `column_not_entitled` when the read source or one of its active enrichments has a column
hidden from the caller by a column policy, otherwise to `sensitive_column_not_entitled` when one has a sensitive column
the caller may not see. Only a caller from whom no column of those tables is hidden — by the clearance or by a column
policy — SHALL have its parse failure reported unchanged, so a genuinely broken declaration still reports accurately
to the caller who can fix it.

#### Scenario: Non-entitled runner scanning a sensitive-referencing pipeline is rejected

- **WHEN** a caller who may not see sensitive columns calls `POST /v1/pipelines/{name}/scan` for a pipeline
  whose projection includes a `sensitive`-flagged column of the pipeline's read source
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: The refusal holds for a sensitive column referenced only by the pipeline's filter

- **WHEN** the same caller scans a pipeline whose `filter` (or `trigger.ready_when.signal`) references a
  `sensitive` column of the read source that no binding projects
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, not with
  a validation error

#### Scenario: A sensitive partition column of an aligned target's source is judged

- **GIVEN** a source whose `partition_by` column `event_day` is `sensitive`, and a row-grain pipeline reading it whose
  target is aligned with it and whose bindings, `filter` and grain key name no sensitive column
- **WHEN** a caller holding `TABLE_READ` on the source but not its clearance calls `POST /v1/pipelines/{name}/scan`
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and no row is read
- **AND WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the source calls the same scan
- **THEN** the scan proceeds and every row carries `__partition_v`

#### Scenario: FULL_ADMIN runner scanning a sensitive-referencing pipeline is allowed

- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the read source (or any caller in
  `none` mode) calls the same scan
- **THEN** the sensitive column is projected and the scan proceeds normally

#### Scenario: A column-restricted runner's parse failure is re-attributed

- **GIVEN** a runner holding `TABLE_READ` and the clearance on the read source, from which a column policy hides
  column `c`
- **WHEN** it scans a pipeline whose `filter` references `c`, and then a pipeline whose `filter` references a column
  the source no longer has
- **THEN** both scans are rejected with HTTP 403 and error code `column_not_entitled`, not with a validation error
- **AND WHEN** a runner from which no column of the source or its enrichments is hidden scans the second pipeline
- **THEN** the parse failure is reported unchanged

### Requirement: Sensitive-column re-check on group assembly

The group assembly endpoint SHALL re-apply the sensitive-column rule against the **calling** identity,
exactly as the scan does, and SHALL bind the check to the columns the **declaration declares** — its bound
input bindings, its membership filter, its `trigger.member_select` and its group key — and to the read source's
`version_column` and `identity_column`, which every member row returns, rather than to the columns a given read
phase happens to project. If any of those is a `sensitive`-flagged column of the read source's
entity — a source column or an enrichment column reached through the grain join, the two governed
alike — and the caller may not see them (no `TABLE_SENSITIVE` on the owning table, and `security.mode` is not
`none`), the request SHALL be rejected with HTTP 403 `sensitive_column_not_entitled`, **before** either phase reads
anything. Sensitive columns
SHALL NOT be silently stripped from the projection — a group input missing the very column the pipeline was
written to read would yield a quietly wrong label, so the misconfiguration fails loudly instead. The scan
and the group assembly SHALL share one implementation of this check so their behaviour cannot diverge.

Naming the owning table SHALL NOT be a way around the check: a declaration that reads
`"dial_usage_log_payload.request_body"` is refused for an unentitled caller on the same terms as one
reading a `sensitive` column of the source itself, and the refusal SHALL carry
`sensitive_column_not_entitled` rather than an unknown-field error, so the operator sees why the
declaration was refused rather than being told the column does not exist.

A column a column policy hides from the calling identity (see
[table-access-control](../table-access-control/spec.md), "Column visibility by caller role" and "Pipeline and export
paths refuse a hidden column") SHALL be refused among those same columns with HTTP 403 `column_not_entitled`, naming
it, before any column is checked for the clearance and before either phase reads anything — the read source's
`version_column` and `identity_column` included, each judged by the exposed name of the catalog column it maps to. A
parse failure of the membership filter or of `trigger.member_select` SHALL be re-attributed as on the scan: to
`column_not_entitled` when the read source or one of its active enrichments has a column hidden from the caller by a
column policy, otherwise to `sensitive_column_not_entitled` when one has a sensitive column the caller may not see,
and otherwise reported unchanged.

#### Scenario: A column-restricted caller is refused a group whose member rows return a hidden version column

- **GIVEN** a group pipeline reading `dial_usage_log`, and a caller holding `TABLE_READ` and the clearance on
  `dial_usage_log` whose only governing role's `columns` name every column the declaration declares, its group key
  and the identity column `event_id`, but not the version column `_ingested_at`
- **WHEN** it calls `POST /v1/pipelines/{name}/group-rows` for a group
- **THEN** the request is rejected with HTTP 403 and error code `column_not_entitled` naming `_ingested_at`, and
  neither phase issues a read
- **AND WHEN** the list also names `_ingested_at`
- **THEN** the group is returned

#### Scenario: An entitled caller receives body columns

- **WHEN** an entitled caller (or any caller in `none` mode) assembles a group for a pipeline whose
  projection includes `"dial_usage_log_payload.request_body"` or
  `"dial_usage_log_payload.response_body"`
- **THEN** those columns are present in the returned member rows, joined from the payload table by the
  grain key

#### Scenario: A non-entitled caller is rejected rather than served a stripped group

- **WHEN** a caller who may not see sensitive columns assembles the same group
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and no
  partially-projected group is returned

#### Scenario: Entitlement is enforced before the selection phase reads

- **WHEN** a caller who may not see sensitive columns assembles a group for a pipeline that binds a sensitive
  column, even though the selection phase would not project it
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and
  neither phase issues a read

#### Scenario: A member row whose payload has expired carries nulls

- **WHEN** an entitled caller assembles a group whose members include an event older than the payload
  table's retention
- **THEN** the member row is returned with its body columns null, and the assembly does not fail
