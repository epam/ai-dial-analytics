## MODIFIED Requirements

### Requirement: Sensitive-column entitlement crosses the grain join

The sensitive-column rule SHALL apply to an enrichment column exactly as it applies to a read-source
column. A `sensitive` column of an `ACTIVE` enrichment of the pipeline's read source SHALL be
referenceable only by a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on that enrichment — which are the
privileges it holds on the enrichment's **source** (see `table-access-control`, "An enrichment's read-side access is
its source's"), each globally or granted there — and a reference by any other caller SHALL be rejected with HTTP 403
`sensitive_column_not_entitled`.

Every enrichment of the read source therefore shares the read source's entitlement: a caller entitled to the read
source's sensitive columns is entitled to those of each of its enrichments, and one that is not is entitled to none.
The rule is kept as its own requirement because an enrichment column's entitlement is still decided on the table the
enrichment hangs off, which, for an enrichment reached through the read source's grain join, is the read source.

Because such a column is **hidden** from a non-entitled caller's schema view rather than forbidden
to it, a governed fragment naming one fails to parse for that caller. That failure SHALL be
re-attributed to HTTP 403 `sensitive_column_not_entitled` rather than reported as HTTP 422
`pipeline_validation_failed`: the pipeline is not malformed, the caller is not entitled, and the
contract promises the 403.

A column a column policy hides from the caller (see [table-access-control](../table-access-control/spec.md), "Column
visibility by caller role") — on the read source, or on an enrichment, named in the source's entries as
`<enrichment>.<column>` — is hidden from its schema view the same way. A reference to one SHALL be refused with HTTP 403
`column_not_entitled`, and a governed fragment's parse failure SHALL be re-attributed to `column_not_entitled` when the
read source or one of its active enrichments has a column hidden from the caller by a column policy, before it is
re-attributed to `sensitive_column_not_entitled` as above. A parse failure SHALL be reported as `422
pipeline_validation_failed` only to a caller from whom no column of those tables is hidden, by the clearance or by a
column policy. A caller with no governing role on the read source is not narrowed by its column policies (see
table-access-control, "Column visibility by caller role").

The pipeline governs **who may reference** the column, and nothing more. A derived enrichment column
SHALL NOT inherit sensitivity from an enrichment column any more than it does from a source column —
the existing "Runner identity and derived-column sensitivity posture" requirement applies unchanged,
and this capability SHALL NOT introduce an exception to it for a value that happens to arrive
through a grain join. Controlling a derived column's exposure remains the enrichment author's
decision, taken by declaring that target column `sensitive`.

#### Scenario: A sensitive enrichment column is accepted for an entitled author

- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on that enrichment's source (or any caller in
  `none` mode)
  registers a pipeline referencing a `sensitive` column of an `ACTIVE` enrichment of its read source
- **THEN** the pipeline is accepted

#### Scenario: A sensitive enrichment column is refused for a non-entitled author

- **WHEN** a caller who may not see sensitive columns registers the same declaration
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, not
  with HTTP 422 reporting an unknown column

#### Scenario: A derived column does not inherit an enrichment column's sensitivity

- **WHEN** a `type=sql` pipeline's output expression reads a `sensitive` enrichment column and binds
  the result to a target column that is not itself `sensitive`
- **THEN** the pipeline is accepted and the target column's `sensitive` flag is left as the table
  author declared it, exactly as for a `sensitive` column of the read source — an enrichment column
  reached through the grain join is governed no differently from one the source declares

#### Scenario: An enrichment's sensitive column is referenceable with the source's entitlement

- **GIVEN** a read source with an `ACTIVE` enrichment carrying a `sensitive` column
- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the read source registers a pipeline whose filter
  references `"<enrichment>.<column>"`
- **THEN** the entitlement check passes
- **AND WHEN** a caller holding `TABLE_READ` on the read source without the clearance registers the same pipeline
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: An enrichment column hidden by the source's column policy is refused

- **GIVEN** a read source with an `ACTIVE` enrichment `e`, and a caller holding `TABLE_READ` and the clearance on the
  read source whose only governing role's `columns` does not name `e.label`
- **WHEN** it registers a pipeline whose filter references `"e.label"`
- **THEN** the request is rejected with HTTP 403 and error code `column_not_entitled`, not with HTTP 422

### Requirement: Registry reads and writes are authorized by registry privileges

The **pipeline registry** SHALL be authorized by privilege rather than by application role.
Reading it, the runner's pipeline sync feed included, SHALL require `REGISTRY_READ`. An enrichment's
definition is authored on its pipeline as `transform`, so authoring it is `REGISTRY_ADMIN` on the
pipeline surface. The pipeline endpoints the sync feed and the ENRICH declaration share with the
aggregate kind are specified once, in [aggregate-pipeline](../aggregate-pipeline/spec.md).

`REGISTRY_READ` SHALL NOT confer any table privilege: an identity holding it may enumerate pipelines
without being able to read the tables they reference. Conversely a table privilege SHALL
NOT confer `REGISTRY_READ`.

Registry payloads name columns — a pipeline's `filter_sql`, `group_by`, `member_select` and
`ready_when`, and the `transform`'s request template — and the service SHALL NOT redact them.
`REGISTRY_READ` therefore discloses column names, consistent with column names not being secret;
column **values** remain governed by the table privileges, with one registry-level exception: a pipeline's cursor and
position members (`state.cursor_version`, `state.cursor_identity`, `state.materialized_through_version`,
`state.materialized_through_identity`, `state.clamp.cursor_version`, `state.clamp.cursor_identity`) carry version and
identity values of its input and are returned to every `REGISTRY_READ` holder regardless of table privileges, sensitive
flags and column policies; `REGISTRY_READ` is the privilege that governs them (see
[table-access-control](../table-access-control/spec.md), "Pipeline and export paths refuse a hidden column").

#### Scenario: Registry read does not confer table read

- **WHEN** a caller holding `REGISTRY_READ` and no table privilege lists pipelines and then queries the
  source one of those pipelines reads
- **THEN** the pipeline listing succeeds and the query reports an unknown entity

#### Scenario: Authoring requires REGISTRY_ADMIN

- **WHEN** a caller holding `REGISTRY_READ` but not `REGISTRY_ADMIN` registers a pipeline
- **THEN** the response is `403 forbidden`

#### Scenario: The sync feed needs only REGISTRY_READ

- **WHEN** an Enrichment Runner holding `REGISTRY_READ` and per-table grants requests the pipeline sync
  feed
- **THEN** the feed is returned, and the runner needs no bundle conferring global table read
