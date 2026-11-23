## ADDED Requirements

### Requirement: A rollup target's clearance is its own

Who may read a rollup target and its sensitive columns SHALL be decided by the target's own grant lists, independently
of its input table's and of the enrichments it reads. A role MAY be cleared on the target without being cleared on any table the
pipeline reads, and registration, enable, refresh, grant edits and schema edits SHALL NOT compare the target's lists
with theirs. A rollup column derived from a `sensitive` input column SHALL still be declared `sensitive` (see
"Sensitive-column governance on the aggregate path").

#### Scenario: A rollup target may clear a role its input does not

- **GIVEN** an enabled aggregate pipeline whose target carries a `sensitive` column and whose input's `sensitive`
  list does not name `analysts`
- **WHEN** an operator grants `sensitive` on the rollup target to `analysts`
- **THEN** the request succeeds, and the pipeline's next refresh succeeds

#### Scenario: Flagging a column sensitive on such a target is accepted

- **GIVEN** the same rollup target, whose `sensitive` list names `analysts`
- **WHEN** a caller holding `TABLE_MODIFY` on the target and `TABLE_SENSITIVE` marks another target column `sensitive`
- **THEN** the request succeeds

## MODIFIED Requirements

### Requirement: An SQL declaration is governed for sensitive columns and enrichment positions as the form path is

The sensitive-column, lineage and enrichment-position rules of the form declaration SHALL apply to
an SQL declaration over **every** column its block references, in the select list, the `WHERE` and the
`GROUP BY`:

- The statement SHALL be resolved at the declaring caller's read entitlement. A column the caller cannot
  see because it is `sensitive` and the caller is not cleared SHALL be refused with HTTP 403
  `sensitive_column_not_entitled`, not reported as an unknown column. The system identity a scheduled
  refresh runs under sees every column, so the remaining rules bite for it and for a cleared caller.
- When the statement cannot be planned at all, and its text mentions a `sensitive` column of the source
  the caller may not read, the refusal SHALL be HTTP 403 `sensitive_column_not_entitled` rather than the
  422 for the planning failure. The mention SHALL be found by an over-approximating token scan of the
  text, so the check fails closed: a protected name anywhere in an unplannable statement answers 403. With
  no declared `inputs`, the check SHALL run against every source table the text names.
- A target column whose select expression reads a `sensitive` column, of the input or of an enrichment,
  SHALL itself be declared `sensitive`, or the declaration SHALL be rejected with HTTP 422
  `pipeline_validation_failed` naming both columns (see **Sensitive-column governance on the aggregate
  path**). Lineage SHALL come from the parsed, inlined select expressions.
- A column read **only in a `CASE WHEN` condition of a `CASE` inside an aggregate's arguments** in a
  measure is a row-membership read, as a form `measures[].where` is. It SHALL be entitlement-gated and
  checked for clampability and aggregatability like every other read, and SHALL be exempt from lineage: it
  decides which rows feed the aggregate, not the value written, so it does not require the target column
  to be `sensitive`. A `CASE` condition in a group key, or in a `CASE` above the aggregate, decides the
  value written and SHALL count for lineage.
- An enrichment the statement reads SHALL be clampable, and one written by a group-triggered pipeline or
  by a pipeline ADAS does not execute SHALL be refused with HTTP 422 naming the enrichment (see **An
  enrichment whose materialized position cannot be established is not aggregatable**).
- The input SHALL carry the version and identity cursor columns a refresh windows over.

#### Scenario: A sensitive column the caller may not see is refused with 403

- **WHEN** a caller not cleared for `sensitive` columns previews or arms an SQL declaration whose `WHERE`
  alone reads the `sensitive` column `jwt_claims`
- **THEN** the response is HTTP 403 `sensitive_column_not_entitled`, not a 422 for an unknown column

#### Scenario: A sensitive source column landing in a plain target column is refused

- **WHEN** a cleared caller arms an SQL declaration writing `max(user_email)` to a target column not
  declared `sensitive`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both columns

#### Scenario: A sensitive column read only in a CASE condition does not need a sensitive target

- **WHEN** a caller entitled to the `sensitive` column `user_email` arms an SQL declaration writing
  `sum(CASE WHEN user_email != '' THEN 1 ELSE 0 END)` to a target column not declared `sensitive`
- **THEN** the declaration is accepted, because the column decides row membership and no value of it is
  written

#### Scenario: A sensitive column in a group key's CASE condition needs a sensitive target

- **WHEN** a cleared caller arms an SQL declaration grouping by
  `CASE WHEN user_email LIKE '%@example.com%' THEN 'internal' ELSE 'external' END` into a target column not
  declared `sensitive`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming both columns

#### Scenario: An unplannable statement naming a protected column is refused with 403

- **WHEN** a caller not cleared for `sensitive` columns submits an SQL declaration that fails to parse and
  whose text mentions the `sensitive` source column `jwt_claims`
- **THEN** the response is HTTP 403 `sensitive_column_not_entitled`, not the 422 for the parse failure

#### Scenario: A non-clampable enrichment is refused

- **WHEN** the statement reads a column of an enrichment written by a group-triggered pipeline, or by a
  pipeline the Enrichment Runner executes
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the enrichment and the
  reason

#### Scenario: An input with no cursor columns is refused

- **WHEN** the statement's input declares no version or identity column
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the input cannot
  be windowed

## REMOVED Requirements

### Requirement: A rollup target may not grant a protected column more widely than its input

**Reason**: A derived table's audience is decided by its own grants (for an enrichment, its source's) and by whoever authors the pipeline, who must already be entitled to the sensitive columns it reads. That a role may read a derived table without being cleared on the table it derives from is intended: one table can hold raw data and another a corrected or reduced form of it, with different readers. Flagging both columns `sensitive` protects each with its own table's clearance. A rollup column derived from a `sensitive` input column must still be declared `sensitive` (see "Sensitive-column governance on the aggregate path"); only the rule comparing the two tables' clearance lists is removed.

**Migration**: None. `PUT /v1/tables/{name}/access` and both schema endpoints no longer answer `422 sensitive_grant_widens`; pipeline registration, enable and aggregate refresh no longer answer `422 pipeline_validation_failed` for a role a target grants more widely than its input; and grant edits, schema edits that mark a column sensitive, and `enrich` registration, enable and armed patch no longer answer `409 pipeline_declaration_unresolvable`. Clients that handled those refusals there can drop the handling.
