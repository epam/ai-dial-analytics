## MODIFIED Requirements

### Requirement: Pipeline and export paths refuse a hidden column

Every path that refuses `403 sensitive_column_not_entitled` SHALL also refuse a column hidden from the calling
identity, with HTTP 403 and machine code `column_not_entitled`, naming the column, and SHALL NOT report it as an
unknown column. Those paths refuse `sensitive_column_not_entitled` for a sensitive column the calling identity may not
read or see: pipeline authoring (`enrich` and `aggregate` registration, enable, patch and preview, and an aggregate's
refresh on request), the runner's data-plane endpoints under the calling identity, and catalog export of a table the bundle
carries under `tables` (an export of only a table's access carries none of its columns; see
[catalog-export-import](../catalog-export-import/spec.md), "Selective export"). A column that
is both hidden and sensitive without the clearance
SHALL be refused `column_not_entitled`. Every column a request references SHALL be judged for a column policy before
any is judged for the clearance: on `enrich` registration, enable and patch the column-policy check SHALL run where
each fragment of the declaration is resolved, and the clearance check of the resolved bound inputs and grain key SHALL
run once, after every other check, so a hidden column anywhere in the request answers `column_not_entitled`. A fragment
that fails to parse because it names a column hidden from the caller's schema is re-attributed where it is parsed, as
the enrichment-registry requirement states. Export SHALL never drop a hidden column
silently. Work running under the system identity SHALL NOT be refused for a column policy. A caller with no governing
role on a table (see "Column visibility by caller role") is not refused for its column policies, so an author holding
only `REGISTRY_ADMIN` may still reference that table's non-sensitive columns, as the pipeline-authoring rules allow.

When one request judges several tables — an export bundle, or a pipeline reading a source and its enrichments — and
both refusals apply, in one table or in different ones, the response SHALL be `column_not_entitled`: every table the
request judges SHALL be checked for policy-hidden columns before any is checked for the clearance, so the code does
not depend on the order the tables are visited in.

The columns a path reads SHALL be every column it returns or derives from. For the runner's scan these are, besides
the declaration's `filter`, `trigger.ready_when.signal` and bound inputs, the read source's `identity_column` and
`version_column`, the target's grain key, a group pipeline's group key and, for a row-trigger pipeline whose target is
aligned with the source it reads, the source's partition column, whose value every row returns as `__partition_v`,
because the scan's page returns them to the caller. For group assembly (`POST /v1/pipelines/{name}/group-rows` and
the group preview built on it) they are, besides its bound inputs, `filter` and `trigger.member_select`, the group key
and the read source's `version_column` and `identity_column`, which every member row returns. For a materialize they
are, besides its `filter`, its expressions (the alignment column of an aligned target included) and the target's grain
key, the read source's `version_column` and `identity_column`, because its `nextCursor` carries their values readably.
Each is judged by the exposed name of the catalog column it maps to (see "Per-role column policies"). A restricted
runner identity's governing roles must therefore allow those columns too — on the seeded `dial_usage_log`, `event_id`
and `_ingested_at`, and `request_time` for an aligned target — or every scan, group assembly or materialize it
requests is refused. A physical column the catalog does not expose cannot be named by a column policy and is not
judged for one.

A `lag_seconds` value — on a scan page, a materialize, an aggregate refresh on request, or `GET /v1/pipelines/{name}`
— is a duration derived from a version, not a column value: it SHALL NOT be a reason to judge a column, and SHALL be
returned to a caller from whom the version column is hidden. A `sql` enrich preview and an aggregate preview do not
judge the read source's `version_column` and `identity_column`, so they keep working for a caller from whom either is
hidden; for a caller from whom the read source's `version_column` is hidden — by a column policy, or as a sensitive
column it lacks the clearance for — an `enrichment_behind` note SHALL carry no instant read from that column: neither
the newest sampled version nor the enrichment's materialized position. The note SHALL still be raised when the
enrichment is behind, name the enrichment as its `subject`, and give the gap, a derived duration like `lag_seconds`.
The pipeline registry reads are not among the judged paths: `GET /v1/pipelines` (also the runner's sync feed) and
`GET /v1/pipelines/{name}` return a pipeline's cursor and position values (`state.cursor_version`,
`state.cursor_identity`, `state.materialized_through_version`, `state.materialized_through_identity`,
`state.clamp.cursor_version`, `state.clamp.cursor_identity`) to any `REGISTRY_READ` holder, as they do regardless of
table privileges and sensitive flags; column policies leave that registry-level disclosure as it is, and `REGISTRY_READ`
is the privilege that governs it, so the judgement above and the note's missing instants keep those values off the
judged paths' own responses but do not hide them from a caller that also holds `REGISTRY_READ`.

Where such a path parses a pipeline-scoped fragment of a stored or submitted declaration against the calling
identity's schema view — a `filter`, a `trigger.ready_when.signal`, a `prefer_sql`, an `enrich` pipeline's
expressions, and an aggregate spec's `filter` and `measures[].where` — a reference to a column hidden from that
identity fails to parse as an unknown column, and the failure does not say which column caused it. Such a parse failure
SHALL be re-attributed to `403 column_not_entitled` when any table the declaration reads — its read source and that
source's active enrichments — has a column hidden from the caller by a column policy; otherwise to
`403 sensitive_column_not_entitled` when one has a sensitive column the caller lacks the clearance for; and otherwise it
SHALL be reported unchanged (see [enrichment-runtime](../enrichment-runtime/spec.md),
[enrichment-registry](../enrichment-registry/spec.md) and [class1-enrichment](../class1-enrichment/spec.md)). Because
a column-restricted caller almost always has some hidden column, it receives every such parse failure of a declaration
over a table that restricts it as `403 column_not_entitled` — including one caused by a column the table no longer has
— while an unrestricted caller receives the parse diagnostic.

An aggregate's SQL statement is not re-attributed that way: it keeps its text-mention rule (see
[aggregate-pipeline](../aggregate-pipeline/spec.md), "An SQL declaration is governed for sensitive columns and
enrichment positions as the form path is"). When the statement cannot be planned, a protected column its text mentions
is refused — `403 column_not_entitled` for one hidden by a column policy, checked across every table the rule covers
before `403 sensitive_column_not_entitled` for an unentitled sensitive one — and every other planning failure stays
the `422` it is today, for a column-restricted caller too.

#### Scenario: An author cannot register a pipeline over a column it cannot see

- **GIVEN** a caller holding `REGISTRY_ADMIN` from whom column `c` of the input table is hidden
- **WHEN** it registers a pipeline whose declaration binds `c` as an input
- **THEN** the response is `403 column_not_entitled` naming `c`, and nothing is stored
- **AND WHEN** it registers a pipeline whose `filter` or expression reads `c`
- **THEN** the response is `403 column_not_entitled`, re-attributed from the parse failure, which names no column

#### Scenario: A runner scan over a hidden column is refused

- **GIVEN** a runner identity from whom a column the pipeline reads is hidden
- **WHEN** it requests a scan
- **THEN** the response is `403 column_not_entitled` and no row is read

#### Scenario: A restricted runner's list must name the scan's cursor columns

- **GIVEN** a row-grain pipeline reading `dial_usage_log`, whose target is keyed on `event_id` and aligned with
  `dial_usage_log` and whose declaration names only `project_id` and `model`, and a runner identity whose only
  governing role on `dial_usage_log` carries `read` and `columns` `["event_id", "model", "project_id",
  "request_time"]`
- **WHEN** it requests a scan
- **THEN** the response is `403 column_not_entitled` naming `_ingested_at`, and no row is read
- **AND WHEN** the list also names `_ingested_at`
- **THEN** the scan succeeds

#### Scenario: A restricted runner's list must name an aligned target's partition column

- **GIVEN** a row-grain pipeline reading `dial_usage_log`, whose target is keyed on `event_id` and aligned with
  `dial_usage_log` and whose declaration names only `project_id` and `model`, and a runner identity whose only
  governing role on `dial_usage_log` carries `read` and `columns` `["_ingested_at", "event_id", "model",
  "project_id"]`
- **WHEN** it requests a scan
- **THEN** the response is `403 column_not_entitled` naming `request_time`, and no row is read

#### Scenario: A restricted caller's list must name materialize's cursor columns

- **GIVEN** an enabled row-grain `sql` pipeline reading `dial_usage_log`, whose target is keyed on `event_id` and not
  aligned and whose expressions read only `project_id`, and a caller holding what materialize requires whose only
  governing role on `dial_usage_log` carries `read` and `columns` `["event_id", "project_id"]`
- **WHEN** it calls `materialize`
- **THEN** the response is `403 column_not_entitled` naming `_ingested_at`, and no row is written
- **AND WHEN** the list also names `_ingested_at`
- **THEN** the call succeeds

#### Scenario: A restricted runner's list must name the version column group assembly returns

- **GIVEN** a group pipeline reading `dial_usage_log`, whose target is keyed on `chat_id`, whose `trigger.group_by` is
  `chat_id`, which declares no `filter` or `trigger.member_select` and whose bound inputs are only `chat_id` and
  `model`, and a runner identity whose only governing role on `dial_usage_log` carries `read` and `columns`
  `["chat_id", "event_id", "model"]`
- **WHEN** it calls `POST /v1/pipelines/{name}/group-rows` for a group
- **THEN** the response is `403 column_not_entitled` naming `_ingested_at`, and no row is read
- **AND WHEN** the list also names `_ingested_at`
- **THEN** the group is returned

#### Scenario: A preview keeps working when the version column is hidden, and its note omits the version

- **GIVEN** an enabled row-grain `sql` enrich pipeline and an aggregate pipeline, each reading `dial_usage_log` and a
  column of its enrichment `deployment_ref`, whose materialized position is behind the newest matching row, and a
  caller holding what preview requires whose only governing role on `dial_usage_log` carries `read` and `columns`
  naming every column either declaration reads (the enrich target's grain key included), but not `_ingested_at`
- **WHEN** it previews each pipeline
- **THEN** each preview succeeds and carries an `enrichment_behind` note whose `subject` is `deployment_ref` and whose
  message gives the gap but no instant
- **AND WHEN** the list also names `_ingested_at`
- **THEN** each note's message also gives the newest sampled version and the enrichment's materialized position

#### Scenario: lag_seconds is returned when the version column is hidden

- **GIVEN** an aggregate pipeline reading `dial_usage_log`, and a caller holding what an on-demand refresh requires
  whose only governing role on `dial_usage_log` carries `read` and `columns` naming every column the declaration reads,
  but not `_ingested_at`
- **WHEN** it refreshes the aggregate on request, and reads `GET /v1/pipelines/{name}`
- **THEN** both succeed and carry `lag_seconds`

#### Scenario: A policy-hidden column is reported before an unentitled sensitive one in another table

- **GIVEN** tables `t1`, with a sensitive column the caller lacks the clearance for, and `t2`, with a column hidden from
  the caller by a column policy, both gettable by the caller
- **WHEN** it exports both, in either selection order
- **THEN** the response is `403 column_not_entitled`

#### Scenario: A stored filter over a hidden column is refused, not reported malformed

- **GIVEN** a stored pipeline whose `filter` references column `c` of its read source, and a runner identity holding
  `read` and the clearance on that source from whom `c` is hidden by a column policy
- **WHEN** it requests a scan
- **THEN** the response is `403 column_not_entitled`, not `422`

#### Scenario: An unplannable aggregate statement is refused only for a column it mentions

- **GIVEN** an author from whom column `c` of the input table is hidden by a column policy
- **WHEN** it previews an aggregate SQL declaration that fails to plan and whose text mentions `c`
- **THEN** the response is `403 column_not_entitled` naming `c`
- **AND WHEN** the statement fails to plan and its text mentions no hidden or unentitled sensitive column
- **THEN** the response is the `422` for the planning failure

#### Scenario: An author holding only REGISTRY_ADMIN is not narrowed

- **GIVEN** a table on which role `X`'s entry carries `read` and `columns` `["a"]`, and a non-sensitive column `b`
- **WHEN** a caller holding only `REGISTRY_ADMIN`, carrying no role with an entry on the table, registers a pipeline
  whose declaration reads `b`
- **THEN** the registration is accepted

#### Scenario: An export with a hidden column is refused

- **WHEN** a caller holding `REGISTRY_READ` and `get` on a table, from whom one of its columns is hidden, exports it under `tables`
- **THEN** the response is `403 column_not_entitled`
- **AND WHEN** the caller, also holding `TABLE_GRANT`, exports only the table's access (`access_only` with `include_access`)
- **THEN** the response is `200`

#### Scenario: A scheduled refresh is unaffected

- **WHEN** an aggregate pipeline's scheduled refresh reads a column hidden from every role
- **THEN** it reads the column as before

