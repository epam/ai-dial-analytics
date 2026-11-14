## MODIFIED Requirements

### Requirement: Export authorization

Export SHALL require `REGISTRY_READ` at the endpoint, and SHALL check each selected table with the table-access evaluator for
`TABLE_GET`.

A selected table, or one its closure reaches, that the caller may not get SHALL be `404` with `error` `not_found`; with no selection
such tables, and every object that needs one, SHALL be silently left out. A bundle table with a sensitive column the caller may not
see SHALL refuse the export with `403` and `error` `sensitive_column_not_entitled`, whether it was selected, reached by the closure or
exported as part of everything; columns are never silently dropped. An enrichment is gettable exactly when its source is, or through its own
`modify` (see [table-access-control](../table-access-control/spec.md)), so exporting everything includes the enrichments
of every source the caller may get, and one carrying a sensitive column the caller may not see — because it lacks the
source's clearance — refuses the export as above. The bundle SHALL NOT carry grants: no table's `access` lists
are exported, whatever the caller holds, because grants are each environment's own policy. When security is not enforced, every
check in this requirement SHALL be skipped.

#### Scenario: Sensitive column the caller cannot see

- **WHEN** a caller with `REGISTRY_READ` and `TABLE_GET` on `t`, but no sensitive clearance on it, exports `t`, which has a sensitive column
- **THEN** the response is `403` with `error` `sensitive_column_not_entitled`

#### Scenario: Table the caller cannot list

- **WHEN** the caller selects a table it may not get
- **THEN** the response is `404` with `error` `not_found`

#### Scenario: Grants never exported

- **WHEN** a caller holding `TABLE_GRANT` exports a table that has grants
- **THEN** the bundle entry carries no `access`

#### Scenario: Security mode none

- **WHEN** security mode is `none` and a caller with no claims exports everything
- **THEN** the response is `200`

#### Scenario: An enrichment of a gettable source joins the export

- **GIVEN** a caller holding `REGISTRY_READ` and `get` on a source with no sensitive column, without the source's
  clearance, and an enrichment of that source with a sensitive column, on which the caller holds nothing
- **WHEN** the caller exports everything
- **THEN** the response is `403` with `error` `sensitive_column_not_entitled`, naming the enrichment

### Requirement: Import order and no grants

Import SHALL apply objects in dependency order: source tables, then enrichment tables, then `enrich` pipelines, then `aggregate`
pipelines. Import SHALL write no grants: every table it creates has empty `access` lists, so until an operator grants access with
`PUT /v1/tables/{name}/access` only callers holding the global privileges can see or read a created source. A created enrichment has no read-side
access of its own: it is readable at once by every caller that may read its source, which may be a table the target
already had and had granted (see [table-access-control](../table-access-control/spec.md)).

#### Scenario: Imported table has no grants

- **WHEN** a table that has grants on the source is exported and imported
- **THEN** `GET /v1/tables/{name}/access` on the target returns five empty lists

#### Scenario: An imported enrichment of an existing source follows its grants

- **GIVEN** a target whose `dial_usage_log` grants `read` to `analytics-viewers`
- **WHEN** a bundle creating an enrichment of `dial_usage_log` is imported
- **THEN** a caller carrying only `analytics-viewers` reads the new enrichment's non-sensitive columns at once, and
  `GET /v1/tables/{name}/access` on the enrichment returns five empty lists
