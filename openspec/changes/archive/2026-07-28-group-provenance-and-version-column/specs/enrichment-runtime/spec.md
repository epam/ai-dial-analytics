## MODIFIED Requirements

### Requirement: Rule-scoped versioned write-back endpoint

The system SHALL expose `POST /v1/rules/{id}/rows` accepting a non-empty `rows` array, symmetric
with the scan endpoint. The system SHALL resolve the rule's `target_enrichment` and validate that
each row's column-set is a subset of {the rule's declared output columns} ∪ {the grain key} ∪
{permitted provenance columns}; a row naming any other column SHALL be rejected with HTTP 422
`write_column_not_allowed`. The caller SHALL NOT name a table and SHALL NOT be able to write
outside its rule's declared columns.

The **permitted provenance columns** SHALL be `evaluator_version`, `model`, `enriched_at`,
`group_version`, and `truncated`, supplied by the caller and written as ordinary user columns.
`group_version` records the version of the input the row was computed from — for a `group` rule, the
maximum version-column value across the members the caller saw — and `truncated` records whether that
input had been trimmed, whether by the group-assembly caps or by the caller's own budget. The two exist
so a **truncated group's label is distinguishable from a complete group's**, and so a label's input
version is identifiable after the fact: without them a partial judgement reads as a full one, and only
the caller's private state knows which version of the group was judged.

Every permitted provenance name SHALL be **un-prefixed**: the `_` prefix is reserved for system columns,
which are not caller-settable at all, and a user-declared enrichment column may not begin with `_`, so a
provenance column lives in the user namespace and is declared on the target like any other column.

Provenance is informational lineage and is therefore **optional on the target**: a permitted provenance
column that the target enrichment does not physically carry SHALL be silently dropped from the write
(best-effort lineage) rather than failing it, so an enrichment created without provenance columns
still enriches successfully. This tolerance applies only to the permitted provenance names; a
non-provenance column outside the rule's declared set is still rejected as above, and the target's
physical-column check still applies to every remaining column. The version column `_updated_at`
SHALL be stamped by ClickHouse and SHALL NOT be accepted as caller input. After validation the
write SHALL delegate to the existing materialized-table write path. An unknown rule SHALL return
HTTP 404 `unknown_rule`.

#### Scenario: A well-formed write is accepted and collapses by grain

- **WHEN** `POST /v1/rules/{id}/rows` is called with rows whose columns are all within the rule's outputs plus grain and provenance
- **THEN** the rows are written to the target enrichment and a subsequent `final=1` read returns the latest version per grain key

#### Scenario: A provenance column absent from the target is skipped, not rejected

- **WHEN** `POST /v1/rules/{id}/rows` is called with rows carrying a permitted provenance column (e.g. `enriched_at`) that the target enrichment does not physically have
- **THEN** that provenance column is silently dropped and the remaining columns are written successfully (the write is not failed and no dead-letter is produced)

#### Scenario: A group rule records its input version and truncation

- **WHEN** a `group` rule writes its one row per group key with `group_version` and `truncated`, and the target enrichment carries both columns
- **THEN** both are written as ordinary columns and a query over the enrichment can tell a label computed from a trimmed group from one computed from a complete group

#### Scenario: Group provenance absent from the target is skipped

- **WHEN** the same write targets an enrichment that carries neither `group_version` nor `truncated`
- **THEN** both are silently dropped and the label is written successfully

#### Scenario: A write naming an undeclared column is rejected

- **WHEN** a row references a column that is neither a declared output of the rule, the grain key, nor a permitted provenance column
- **THEN** the request is rejected with HTTP 422 and error code `write_column_not_allowed`

#### Scenario: Caller-supplied `_updated_at` is rejected

- **WHEN** a write row attempts to set the system version column `_updated_at`
- **THEN** the request is rejected (system column is not caller-settable) and ClickHouse stamps the version on insert
