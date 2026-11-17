## MODIFIED Requirements

### Requirement: An SQL declaration is governed for sensitive columns and enrichment positions as the form path is

The sensitive-column, lineage and enrichment-position rules of the form declaration SHALL apply to
an SQL declaration over **every** column its block references, in the select list, the `WHERE` and the
`GROUP BY`:

- The statement SHALL be resolved at the declaring caller's read entitlement. A column the caller cannot
  see because it is `sensitive` and the caller is not cleared SHALL be refused with HTTP 403
  `sensitive_column_not_entitled`, not reported as an unknown column. A column a column policy hides
  from the caller (see [table-access-control](../table-access-control/spec.md), "Column visibility by caller
  role") SHALL be refused with HTTP 403 `column_not_entitled` in the same way, and a column both hidden and
  sensitive without the clearance SHALL be refused `column_not_entitled`. The system identity a scheduled
  refresh runs under sees every column, so the remaining rules bite for it and for a cleared caller.
- When the statement cannot be planned at all, its text SHALL be checked for **protected** columns it
  mentions: a column of the source, or a qualified column of one of its active enrichments, that is hidden
  from the caller by a column policy, or is `sensitive` and the caller may not read it. A mentioned column
  hidden by a column policy SHALL be refused HTTP 403 `column_not_entitled`; otherwise a mentioned
  `sensitive` column the caller may not read SHALL be refused HTTP 403 `sensitive_column_not_entitled`;
  either rather than the 422 for the planning failure. Every table the check covers SHALL be checked for
  policy-hidden columns before any is checked for the clearance, so the code does not depend on the order
  the tables are visited in. The mention SHALL be found by an over-approximating token scan of the text,
  so the check fails closed: a protected name anywhere in an unplannable statement answers 403. A planning
  failure whose text mentions no protected column SHALL stay the 422 for the planning failure, for a
  column-restricted or uncleared caller too: this path does not re-attribute every failure the way a
  pipeline-scoped `filter` parse does (see table-access-control, "Pipeline and export paths refuse a hidden
  column"). With no declared `inputs`, the check SHALL run against every source table the text names.
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

#### Scenario: An unplannable statement naming a policy-hidden column is refused with 403

- **GIVEN** a caller from which a column policy hides source column `user_hash`, and which is not cleared for the
  `sensitive` source column `jwt_claims`
- **WHEN** it submits an SQL declaration that fails to parse and whose text mentions both `jwt_claims` and `user_hash`
- **THEN** the response is HTTP 403 `column_not_entitled`, naming `user_hash`
- **AND WHEN** it submits an SQL declaration that fails to parse and whose text mentions no protected column
- **THEN** the response is the 422 for the parse failure, not a 403

#### Scenario: A non-clampable enrichment is refused

- **WHEN** the statement reads a column of an enrichment written by a group-triggered pipeline, or by a
  pipeline the Enrichment Runner executes
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the enrichment and the
  reason

#### Scenario: An input with no cursor columns is refused

- **WHEN** the statement's input declares no version or identity column
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` stating that the input cannot
  be windowed
